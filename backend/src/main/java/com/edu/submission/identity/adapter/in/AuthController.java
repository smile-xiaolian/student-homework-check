package com.edu.submission.identity.adapter.in;

import com.edu.submission.identity.adapter.in.dto.LoginRequest;
import com.edu.submission.identity.adapter.in.dto.LoginResponse;
import com.edu.submission.identity.application.AuthService;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.api.RequestIps;
import com.edu.submission.shared.security.CurrentUser;
import com.edu.submission.shared.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        AuthService.AuthResult result = authService.login(
                request.loginName(), request.password(), RequestIps.from(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken(), false).toString())
                .body(toResponse(result));
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest httpRequest) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw ApiException.unauthorized("AUTH_REFRESH_REQUIRED", "会话已失效", "请重新登录。");
        }
        AuthService.AuthResult result = authService.refresh(refreshToken, RequestIps.from(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken(), false).toString())
                .body(toResponse(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        authService.logout(CurrentUser.optional().orElse(null), RequestIps.from(httpRequest));
        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", true).toString())
                .build();
    }

    private LoginResponse toResponse(AuthService.AuthResult result) {
        return new LoginResponse(result.accessToken(), "Bearer", result.expiresIn(), result.user());
    }

    private ResponseCookie refreshCookie(String token, boolean clear) {
        Duration maxAge = clear ? Duration.ZERO : jwtService.refreshTokenDuration();
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(jwtService.refreshCookieSecure())
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
    }
}

