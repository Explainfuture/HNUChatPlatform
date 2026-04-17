package com.hnu.campus.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hnu.campus.entity.AuthRefreshToken;
import com.hnu.campus.entity.AuthSession;
import com.hnu.campus.entity.User;
import com.hnu.campus.entity.UserSecurityState;
import com.hnu.campus.mapper.AuthRefreshTokenMapper;
import com.hnu.campus.mapper.AuthSessionMapper;
import com.hnu.campus.mapper.UserSecurityStateMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class AuthSessionSupport {
    private static final String ROLE_CACHE_PREFIX = "user_role:";
    private static final String SESSION_SCOPE_TAB = "tab";
    private static final String SESSION_STATUS_ACTIVE = "active";
    private static final String SESSION_STATUS_REVOKED = "revoked";

    private final JwtUtil jwtUtil;
    private final AuthSessionMapper authSessionMapper;
    private final AuthRefreshTokenMapper authRefreshTokenMapper;
    private final UserSecurityStateMapper userSecurityStateMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${jwt.refresh-expire-seconds:2592000}")
    private long refreshExpireSeconds;

    @Value("${jwt.session-idle-expire-seconds:2592000}")
    private long sessionIdleExpireSeconds;

    public AuthSessionSupport(JwtUtil jwtUtil,
                              AuthSessionMapper authSessionMapper,
                              AuthRefreshTokenMapper authRefreshTokenMapper,
                              UserSecurityStateMapper userSecurityStateMapper,
                              StringRedisTemplate redisTemplate) {
        this.jwtUtil = jwtUtil;
        this.authSessionMapper = authSessionMapper;
        this.authRefreshTokenMapper = authRefreshTokenMapper;
        this.userSecurityStateMapper = userSecurityStateMapper;
        this.redisTemplate = redisTemplate;
    }

    public UserSecurityState getOrCreateSecurityState(Long userId) {
        UserSecurityState state = userSecurityStateMapper.selectById(userId);
        if (state != null) {
            return state;
        }
        LocalDateTime now = LocalDateTime.now();
        UserSecurityState nextState = UserSecurityState.builder()
                .userId(userId)
                .authVersion(1L)
                .credentialVersion(1L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            userSecurityStateMapper.insert(nextState);
            return nextState;
        } catch (Exception ignored) {
            return Objects.requireNonNull(userSecurityStateMapper.selectById(userId));
        }
    }

    public String createAccessToken(User user, UserSecurityState state, String sessionId) {
        cacheUserRole(user.getId(), user.getRole());
        return jwtUtil.generateToken(user.getId(), user.getRole(), state.getAuthVersion(), sessionId);
    }

    public long getAccessExpireSeconds() {
        return jwtUtil.getAccessExpireSeconds();
    }

    public AuthSession createSession(Long userId, String clientInstanceId, String ip, String userAgent) {
        LocalDateTime now = LocalDateTime.now();
        AuthSession session = AuthSession.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .sessionScope(SESSION_SCOPE_TAB)
                .clientInstanceId(clientInstanceId)
                .status(SESSION_STATUS_ACTIVE)
                .createdAt(now)
                .lastSeenAt(now)
                .expiresAt(now.plusSeconds(refreshExpireSeconds))
                .idleExpiresAt(now.plusSeconds(sessionIdleExpireSeconds))
                .lastIp(ip)
                .userAgent(userAgent)
                .build();
        authSessionMapper.insert(session);
        return session;
    }

    public IssuedRefreshToken issueRefreshToken(String sessionId, String parentId) {
        LocalDateTime now = LocalDateTime.now();
        String rawToken = generateOpaqueToken();
        AuthRefreshToken refreshToken = AuthRefreshToken.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .tokenHash(hashToken(rawToken))
                .parentId(parentId)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(refreshExpireSeconds))
                .reuseDetected(false)
                .build();
        authRefreshTokenMapper.insert(refreshToken);
        return new IssuedRefreshToken(rawToken, refreshToken);
    }

    public AuthRefreshToken findRefreshToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        return authRefreshTokenMapper.selectOne(
                new LambdaQueryWrapper<AuthRefreshToken>()
                        .eq(AuthRefreshToken::getTokenHash, hashToken(rawToken))
                        .last("LIMIT 1")
        );
    }

    public AuthSession findSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return authSessionMapper.selectById(sessionId);
    }

    public boolean isSessionActive(AuthSession session) {
        if (session == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return SESSION_STATUS_ACTIVE.equals(session.getStatus())
                && session.getRevokedAt() == null
                && (session.getExpiresAt() == null || !session.getExpiresAt().isBefore(now))
                && (session.getIdleExpiresAt() == null || !session.getIdleExpiresAt().isBefore(now));
    }

    public boolean sessionMatchesClient(AuthSession session, String clientInstanceId) {
        return session != null
                && clientInstanceId != null
                && clientInstanceId.equals(session.getClientInstanceId());
    }

    public boolean isRefreshTokenUsable(AuthRefreshToken token) {
        return token != null
                && token.getRevokedAt() == null
                && token.getUsedAt() == null
                && (token.getExpiresAt() == null || !token.getExpiresAt().isBefore(LocalDateTime.now()));
    }

    public boolean isRefreshTokenReplay(AuthRefreshToken token) {
        return token != null && token.getUsedAt() != null;
    }

    public void consumeRefreshToken(String tokenId) {
        LocalDateTime now = LocalDateTime.now();
        authRefreshTokenMapper.update(
                null,
                new LambdaUpdateWrapper<AuthRefreshToken>()
                        .eq(AuthRefreshToken::getId, tokenId)
                        .set(AuthRefreshToken::getUsedAt, now)
                        .set(AuthRefreshToken::getRevokedAt, now)
        );
    }

    public void flagRefreshTokenReuse(String tokenId) {
        LocalDateTime now = LocalDateTime.now();
        authRefreshTokenMapper.update(
                null,
                new LambdaUpdateWrapper<AuthRefreshToken>()
                        .eq(AuthRefreshToken::getId, tokenId)
                        .set(AuthRefreshToken::getReuseDetected, true)
                        .set(AuthRefreshToken::getRevokedAt, now)
        );
    }

    public void touchSession(String sessionId, String ip, String userAgent) {
        LocalDateTime now = LocalDateTime.now();
        authSessionMapper.update(
                null,
                new LambdaUpdateWrapper<AuthSession>()
                        .eq(AuthSession::getId, sessionId)
                        .set(AuthSession::getLastSeenAt, now)
                        .set(AuthSession::getIdleExpiresAt, now.plusSeconds(sessionIdleExpireSeconds))
                        .set(ip != null && !ip.isBlank(), AuthSession::getLastIp, ip)
                        .set(userAgent != null && !userAgent.isBlank(), AuthSession::getUserAgent, userAgent)
        );
    }

    public void revokeSession(String sessionId, String reason) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        authSessionMapper.update(
                null,
                new LambdaUpdateWrapper<AuthSession>()
                        .eq(AuthSession::getId, sessionId)
                        .set(AuthSession::getStatus, SESSION_STATUS_REVOKED)
                        .set(AuthSession::getRevokedAt, now)
                        .set(AuthSession::getRevokeReason, reason)
        );
        authRefreshTokenMapper.update(
                null,
                new LambdaUpdateWrapper<AuthRefreshToken>()
                        .eq(AuthRefreshToken::getSessionId, sessionId)
                        .isNull(AuthRefreshToken::getRevokedAt)
                        .set(AuthRefreshToken::getRevokedAt, now)
        );
    }

    public void revokeAllUserSessions(Long userId, String reason) {
        UserSecurityState state = getOrCreateSecurityState(userId);
        LocalDateTime now = LocalDateTime.now();
        userSecurityStateMapper.update(
                null,
                new LambdaUpdateWrapper<UserSecurityState>()
                        .eq(UserSecurityState::getUserId, userId)
                        .set(UserSecurityState::getAuthVersion, state.getAuthVersion() + 1)
                        .set(UserSecurityState::getForceLogoutAfter, now)
                        .set(UserSecurityState::getUpdatedAt, now)
        );
        clearUserRoleCache(userId);

        List<AuthSession> sessions = authSessionMapper.selectList(
                new LambdaQueryWrapper<AuthSession>().eq(AuthSession::getUserId, userId)
        );
        for (AuthSession session : sessions) {
            revokeSession(session.getId(), reason);
        }
    }

    public boolean isAuthVersionValid(Long userId, Long authVersion) {
        if (userId == null || authVersion == null) {
            return false;
        }
        UserSecurityState state = userSecurityStateMapper.selectById(userId);
        return state != null && Objects.equals(state.getAuthVersion(), authVersion);
    }

    public void cacheUserRole(Long userId, String role) {
        if (userId == null || role == null || role.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(ROLE_CACHE_PREFIX + userId, role);
    }

    public void clearUserRoleCache(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.delete(ROLE_CACHE_PREFIX + userId);
    }

    private String generateOpaqueToken() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record IssuedRefreshToken(String rawToken, AuthRefreshToken token) {
    }
}
