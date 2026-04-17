package com.hnu.campus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hnu.campus.dto.admin.AuthReviewDTO;
import com.hnu.campus.dto.admin.UserMuteDTO;
import com.hnu.campus.dto.user.UserInfoDTO;
import com.hnu.campus.entity.Post;
import com.hnu.campus.entity.User;
import com.hnu.campus.enums.AuthStatus;
import com.hnu.campus.enums.UserRole;
import com.hnu.campus.exception.BusinessException;
import com.hnu.campus.mapper.PostMapper;
import com.hnu.campus.mapper.UserMapper;
import com.hnu.campus.security.AuthSessionSupport;
import com.hnu.campus.security.CurrentUserContext;
import com.hnu.campus.service.AdminService;
import com.hnu.campus.service.CommentService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminServiceImpl implements AdminService {
    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final CommentService commentService;
    private final AuthSessionSupport authSessionSupport;

    public AdminServiceImpl(UserMapper userMapper,
                            PostMapper postMapper,
                            CommentService commentService,
                            AuthSessionSupport authSessionSupport) {
        this.userMapper = userMapper;
        this.postMapper = postMapper;
        this.commentService = commentService;
        this.authSessionSupport = authSessionSupport;
    }

    @Override
    public void reviewAuth(Long adminId, AuthReviewDTO reviewDTO) {
        ensureAdmin();
        AuthStatus status;
        try {
            status = AuthStatus.fromCode(reviewDTO.getAuthStatus());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "Invalid auth status");
        }
        if (status == AuthStatus.PENDING) {
            throw new BusinessException(400, "Auth status cannot be pending");
        }
        User user = userMapper.selectById(reviewDTO.getUserId());
        if (user == null) {
            throw new BusinessException(404, "User not found");
        }
        user.setAuthStatus(status.getCode());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
    }

    @Override
    public void forceDeletePost(Long adminId, Long postId) {
        ensureAdmin();
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(404, "Post not found");
        }
        post.setStatus("deleted");
        post.setUpdateTime(LocalDateTime.now());
        postMapper.updateById(post);
    }

    @Override
    public void muteUser(Long adminId, UserMuteDTO muteDTO) {
        ensureAdmin();
        if (adminId != null && adminId.equals(muteDTO.getUserId())) {
            throw new BusinessException(400, "Cannot mute self");
        }
        User user = userMapper.selectById(muteDTO.getUserId());
        if (user == null) {
            throw new BusinessException(404, "User not found");
        }
        user.setIsMuted(muteDTO.getIsMuted());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
    }

    @Override
    public List<UserInfoDTO> getPendingUsers(Long adminId, Integer page, Integer size) {
        ensureAdmin();
        Page<User> pageResult = userMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<User>().eq(User::getAuthStatus, AuthStatus.PENDING.getCode())
                        .orderByAsc(User::getCreateTime)
        );
        return pageResult.getRecords().stream().map(user -> {
            UserInfoDTO dto = new UserInfoDTO();
            dto.setId(user.getId());
            dto.setPhone(user.getPhone());
            dto.setNickname(user.getNickname());
            dto.setStudentId(user.getStudentId());
            dto.setCampusCardUrl(user.getCampusCardUrl());
            dto.setAuthStatus(user.getAuthStatus());
            dto.setRole(user.getRole());
            dto.setIsMuted(user.getIsMuted());
            dto.setCreateTime(user.getCreateTime());
            return dto;
        }).toList();
    }

    @Override
    public void deleteComment(Long adminId, Long commentId) {
        ensureAdmin();
        commentService.deleteCommentAsAdmin(commentId, adminId);
    }

    @Override
    public List<UserInfoDTO> getAllUsers(Long adminId, Integer page, Integer size) {
        ensureAdmin();
        Page<User> pageResult = userMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<User>()
                        .eq(User::getAuthStatus, AuthStatus.APPROVED.getCode())
                        .orderByAsc(User::getCreateTime)
        );
        return pageResult.getRecords().stream().map(user -> {
            UserInfoDTO dto = new UserInfoDTO();
            dto.setId(user.getId());
            dto.setPhone(user.getPhone());
            dto.setNickname(user.getNickname());
            dto.setStudentId(user.getStudentId());
            dto.setCampusCardUrl(user.getCampusCardUrl());
            dto.setAuthStatus(user.getAuthStatus());
            dto.setRole(user.getRole());
            dto.setIsMuted(user.getIsMuted());
            dto.setCreateTime(user.getCreateTime());
            return dto;
        }).toList();
    }

    @Override
    public void kickUser(Long adminId, Long userId) {
        ensureAdmin();
        if (userId == null) {
            throw new BusinessException(400, "User ID required");
        }
        if (adminId != null && adminId.equals(userId)) {
            throw new BusinessException(400, "Cannot kick self");
        }
        revokeUserSessions(userId);
    }

    private void ensureAdmin() {
        String role = CurrentUserContext.getRole();
        if (!UserRole.ADMIN.getCode().equals(role)) {
            throw new BusinessException(403, "Admin permission required");
        }
    }

    private void revokeUserSessions(Long userId) {
        authSessionSupport.revokeAllUserSessions(userId, "admin_kick");
    }
}
