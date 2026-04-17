package com.hnu.campus.service;

import com.hnu.campus.dto.auth.LoginDTO;
import com.hnu.campus.dto.auth.LoginResponseDTO;
import com.hnu.campus.dto.auth.RegisterDTO;

public interface AuthService {
    Long register(RegisterDTO registerDTO);

    LoginResponseDTO login(LoginDTO loginDTO, String clientInstanceId, String ip, String userAgent);

    LoginResponseDTO refresh(String refreshToken, String clientInstanceId, String ip, String userAgent);

    boolean logout(String refreshToken, String clientInstanceId);

    void sendVerifyCode(String phone);
}
