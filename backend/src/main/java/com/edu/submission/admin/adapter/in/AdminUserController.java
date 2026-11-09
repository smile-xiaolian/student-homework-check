package com.edu.submission.admin.adapter.in;

import com.edu.submission.admin.adapter.in.dto.AdminUserCreateRequest;
import com.edu.submission.admin.adapter.in.dto.AdminUserUpdateRequest;
import com.edu.submission.admin.adapter.in.dto.ResetPasswordRequest;
import com.edu.submission.admin.application.AdminUserService;
import com.edu.submission.identity.adapter.in.dto.UserAccountView;
import com.edu.submission.shared.api.PageResult;
import com.edu.submission.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public PageResult<UserAccountView> list(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String role,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return adminUserService.list(q, role, status, page, size);
    }

    @GetMapping("/{id}")
    public UserAccountView get(@PathVariable UUID id) {
        return adminUserService.get(id);
    }

    @PostMapping
    public UserAccountView create(@Valid @RequestBody AdminUserCreateRequest request) {
        return adminUserService.create(CurrentUser.require(), request);
    }

    @PatchMapping("/{id}")
    public UserAccountView update(@PathVariable UUID id,
                                  @Valid @RequestBody AdminUserUpdateRequest request) {
        return adminUserService.update(CurrentUser.require(), id, request);
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        adminUserService.deactivate(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable UUID id) {
        adminUserService.activate(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<Void> unlock(@PathVariable UUID id) {
        adminUserService.unlock(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id,
                                              @Valid @RequestBody ResetPasswordRequest request) {
        adminUserService.resetPassword(CurrentUser.require(), id, request.newPassword());
        return ResponseEntity.noContent().build();
    }
}

