package com.edu.submission.identity.adapter.in;

import com.edu.submission.identity.adapter.in.dto.MeView;
import com.edu.submission.identity.adapter.in.dto.PasswordChangeRequest;
import com.edu.submission.identity.adapter.in.dto.ProfileUpdateRequest;
import com.edu.submission.identity.application.AuthService;
import com.edu.submission.shared.security.CurrentUser;
import com.edu.submission.shared.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping
    public MeView me() {
        return authService.me(CurrentUser.require());
    }

    @PatchMapping
    public MeView updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        UserPrincipal principal = CurrentUser.require();
        return authService.updateProfile(principal, request.displayName(), request.email(), request.phone());
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        authService.changePassword(CurrentUser.require(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}

