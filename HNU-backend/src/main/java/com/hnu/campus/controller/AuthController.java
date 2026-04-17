package com.hnu.campus.controller;

import com.hnu.campus.dto.auth.LoginDTO;
import com.hnu.campus.dto.auth.LoginResponseDTO;
import com.hnu.campus.dto.auth.RegisterDTO;
import com.hnu.campus.dto.common.ApiResponse;
import com.hnu.campus.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import com.hnu.campus.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * Auth controller.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Register/login/refresh/verify-code endpoints")
public class AuthController {
    private static final String REFRESH_COOKIE_NAME = "hnu_refresh_token";
    private static final String CLIENT_INSTANCE_HEADER = "X-Client-Instance-Id";

    private final AuthService authService;

    @Value("${jwt.refresh-expire-seconds:2592000}")
    private long refreshExpireSeconds;

    @Value("${jwt.refresh-cookie-secure:false}")
    private boolean refreshCookieSecure;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register", description = "Register a new user")
    public ApiResponse<Long> register(@Valid @RequestBody RegisterDTO registerDTO) {
        Long userId = authService.register(registerDTO);
        return ApiResponse.success("Register success", userId);
    }

    @PostMapping("/login")
    @Operation(summary = "Login", description = "Login and return access token")
    public ApiResponse<LoginResponseDTO> login(@Valid @RequestBody LoginDTO loginDTO,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        LoginResponseDTO loginResponse = authService.login(
                loginDTO,
                extractClientInstanceId(request),
                extractClientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT)
        );
        writeRefreshCookie(response, loginResponse.getRefreshToken());
        loginResponse.setRefreshToken(null);
        return ApiResponse.success(loginResponse);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh token", description = "Refresh access token by refresh token")
    public ApiResponse<LoginResponseDTO> refresh(
            @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        LoginResponseDTO refreshResponse = authService.refresh(
                refreshToken,
                extractClientInstanceId(request),
                extractClientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT)
        );
        writeRefreshCookie(response, refreshResponse.getRefreshToken());
        refreshResponse.setRefreshToken(null);
        return ApiResponse.success(refreshResponse);
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout", description = "Clear refresh token and logout")
    public ApiResponse<Void> logout(
            @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        boolean shouldClearCookie = authService.logout(refreshToken, extractClientInstanceId(request));
        if (shouldClearCookie) {
            clearRefreshCookie(response);
        }
        return ApiResponse.success("Logout success");
    }

    @PostMapping("/send-verify-code")
    @Operation(summary = "Send verify code", description = "Send SMS verify code and store in Redis")
    public ApiResponse<Void> sendVerifyCode(@RequestParam String phone) {
        authService.sendVerifyCode(phone);
        return ApiResponse.success("Verify code sent");
    }

    private void writeRefreshCookie(HttpServletResponse response, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .path("/")
                .maxAge(Duration.ofSeconds(refreshExpireSeconds))
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .path("/")
                .maxAge(Duration.ZERO)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String extractClientInstanceId(HttpServletRequest request) {
        String clientInstanceId = request.getHeader(CLIENT_INSTANCE_HEADER);
        if (clientInstanceId == null || clientInstanceId.isBlank()) {
            throw new BusinessException(400, "Missing client instance id");
        }
        return clientInstanceId.trim();
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
