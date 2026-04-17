package com.hnu.campus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hnu.campus.dto.auth.LoginDTO;
import com.hnu.campus.dto.auth.LoginResponseDTO;
import com.hnu.campus.dto.auth.RegisterDTO;
import com.hnu.campus.entity.AuthRefreshToken;
import com.hnu.campus.entity.AuthSession;
import com.hnu.campus.entity.User;
import com.hnu.campus.entity.UserSecurityState;
import com.hnu.campus.enums.AuthStatus;
import com.hnu.campus.enums.UserRole;
import com.hnu.campus.exception.BusinessException;
import com.hnu.campus.mapper.UserMapper;
import com.hnu.campus.security.AuthSessionSupport;
import com.hnu.campus.service.AuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {
    private static final String VERIFY_CODE_PREFIX = "verify_code:";

    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthSessionSupport authSessionSupport;

    public AuthServiceImpl(UserMapper userMapper,
                           StringRedisTemplate redisTemplate,
                           BCryptPasswordEncoder passwordEncoder,
                           AuthSessionSupport authSessionSupport) {
        this.userMapper = userMapper;
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = passwordEncoder;
        this.authSessionSupport = authSessionSupport;
    }

    @Override
    public Long register(RegisterDTO registerDTO) {
        String phone = registerDTO.getPhone();
        String key = VERIFY_CODE_PREFIX + phone;
        String cachedCode = redisTemplate.opsForValue().get(key);
        if (cachedCode == null || !cachedCode.equals(registerDTO.getVerifyCode())) {
            throw new BusinessException(400, "Invalid verify code");
        }

        User existing = userMapper.selectOne(new QueryWrapper<User>().eq("phone", phone));
        if (existing != null) {
            throw new BusinessException(400, "Phone already registered");
        }

        User user = User.builder()
                .phone(phone)
                .nickname(registerDTO.getNickname())
                .password(passwordEncoder.encode(registerDTO.getPassword()))
                .studentId(registerDTO.getStudentId())
                .campusCardUrl(registerDTO.getCampusCardUrl())
                .authStatus(AuthStatus.PENDING.getCode())
                .role(UserRole.STUDENT.getCode())
                .isMuted(false)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        userMapper.insert(user);
        redisTemplate.delete(key);
        return user.getId();
    }

    @Override
    @Transactional
    public LoginResponseDTO login(LoginDTO loginDTO, String clientInstanceId, String ip, String userAgent) {
        String phone = loginDTO.getPhone();
        String key = VERIFY_CODE_PREFIX + phone;
        String cachedCode = redisTemplate.opsForValue().get(key);
        if (cachedCode == null || !cachedCode.equals(loginDTO.getVerifyCode())) {
            throw new BusinessException(400, "Invalid verify code");
        }

        User user = userMapper.selectOne(new QueryWrapper<User>().eq("phone", phone));
        if (user == null) {
            throw new BusinessException(400, "Phone or password is incorrect");
        }
        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            throw new BusinessException(400, "Phone or password is incorrect");
        }
        if (!AuthStatus.APPROVED.getCode().equals(user.getAuthStatus())) {
            throw new BusinessException(403, "Account not approved");
        }

        UserSecurityState securityState = authSessionSupport.getOrCreateSecurityState(user.getId());
        AuthSession session = authSessionSupport.createSession(user.getId(), clientInstanceId, ip, userAgent);
        AuthSessionSupport.IssuedRefreshToken refreshToken = authSessionSupport.issueRefreshToken(session.getId(), null);
        String accessToken = authSessionSupport.createAccessToken(user, securityState, session.getId());

        LoginResponseDTO response = new LoginResponseDTO();
        response.setToken(accessToken);
        response.setRefreshToken(refreshToken.rawToken());
        response.setExpiresIn(authSessionSupport.getAccessExpireSeconds());
        response.setUserId(user.getId());
        response.setNickname(user.getNickname());
        response.setRole(user.getRole());
        redisTemplate.delete(key);
        return response;
    }

    @Override
    @Transactional
    public LoginResponseDTO refresh(String refreshToken, String clientInstanceId, String ip, String userAgent) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(401, "Missing refresh token");
        }

        AuthRefreshToken persistedToken = authSessionSupport.findRefreshToken(refreshToken);
        if (persistedToken == null) {
            throw new BusinessException(401, "Login expired, please re-login");
        }
        AuthSession session = authSessionSupport.findSession(persistedToken.getSessionId());
        if (authSessionSupport.isRefreshTokenReplay(persistedToken)) {
            authSessionSupport.flagRefreshTokenReuse(persistedToken.getId());
            if (session != null) {
                authSessionSupport.revokeSession(session.getId(), "refresh_token_reuse");
            }
            throw new BusinessException(401, "Refresh token reuse detected, please re-login");
        }
        if (!authSessionSupport.isRefreshTokenUsable(persistedToken) || session == null || !authSessionSupport.isSessionActive(session)) {
            if (session != null) {
                authSessionSupport.revokeSession(session.getId(), "refresh_token_invalid");
            }
            throw new BusinessException(401, "Login expired, please re-login");
        }
        if (!authSessionSupport.sessionMatchesClient(session, clientInstanceId)) {
            throw new BusinessException(401, "Session is bound to another tab");
        }

        User user = userMapper.selectById(session.getUserId());
        if (user == null) {
            throw new BusinessException(401, "User not found");
        }
        if (!AuthStatus.APPROVED.getCode().equals(user.getAuthStatus())) {
            throw new BusinessException(403, "Account not approved");
        }

        UserSecurityState securityState = authSessionSupport.getOrCreateSecurityState(user.getId());
        authSessionSupport.consumeRefreshToken(persistedToken.getId());
        authSessionSupport.touchSession(session.getId(), ip, userAgent);
        AuthSessionSupport.IssuedRefreshToken nextRefreshToken =
                authSessionSupport.issueRefreshToken(session.getId(), persistedToken.getId());
        String accessToken = authSessionSupport.createAccessToken(user, securityState, session.getId());

        LoginResponseDTO response = new LoginResponseDTO();
        response.setToken(accessToken);
        response.setRefreshToken(nextRefreshToken.rawToken());
        response.setExpiresIn(authSessionSupport.getAccessExpireSeconds());
        response.setUserId(user.getId());
        response.setNickname(user.getNickname());
        response.setRole(user.getRole());
        return response;
    }

    @Override
    public void sendVerifyCode(String phone) {
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1_000_000));
        String key = VERIFY_CODE_PREFIX + phone;
        redisTemplate.opsForValue().set(key, code, Duration.ofMinutes(5));
        log.info("Send verify code to {}: {}", phone, code);
    }

    @Override
    @Transactional
    public boolean logout(String refreshToken, String clientInstanceId) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return false;
        }
        AuthRefreshToken persistedToken = authSessionSupport.findRefreshToken(refreshToken);
        if (persistedToken == null) {
            return false;
        }
        AuthSession session = authSessionSupport.findSession(persistedToken.getSessionId());
        if (session == null || !authSessionSupport.sessionMatchesClient(session, clientInstanceId)) {
            return false;
        }
        authSessionSupport.revokeSession(session.getId(), "logout");
        return true;
    }
}
