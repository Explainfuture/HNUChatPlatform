package com.hnu.campus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("auth_sessions")
public class AuthSession {
    @TableId(type = IdType.INPUT)
    private String id;

    private Long userId;

    private String sessionScope;

    private String clientInstanceId;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime lastSeenAt;

    private LocalDateTime expiresAt;

    private LocalDateTime idleExpiresAt;

    private LocalDateTime revokedAt;

    private String revokeReason;

    private String lastIp;

    private String userAgent;
}
