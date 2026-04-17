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
@TableName("auth_refresh_tokens")
public class AuthRefreshToken {
    @TableId(type = IdType.INPUT)
    private String id;

    private String sessionId;

    private String tokenHash;

    private String parentId;

    private LocalDateTime issuedAt;

    private LocalDateTime usedAt;

    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    private Boolean reuseDetected;
}
