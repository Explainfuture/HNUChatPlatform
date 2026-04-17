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
@TableName("user_security_state")
public class UserSecurityState {
    @TableId(type = IdType.INPUT)
    private Long userId;

    private Long authVersion;

    private Long credentialVersion;

    private LocalDateTime forceLogoutAfter;

    private LocalDateTime lastPasswordChangeAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
