package com.edu.submission.admin.adapter.in;

import com.edu.submission.admin.adapter.in.dto.SystemConfigUpdateRequest;
import com.edu.submission.admin.adapter.in.dto.SystemConfigView;
import com.edu.submission.admin.application.AdminSystemService;
import com.edu.submission.shared.api.PageResult;
import com.edu.submission.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSystemController {

    private final AdminSystemService adminSystemService;

    public AdminSystemController(AdminSystemService adminSystemService) {
        this.adminSystemService = adminSystemService;
    }

    @GetMapping("/config")
    public Map<String, List<SystemConfigView>> config() {
        return Map.of("items", adminSystemService.config());
    }

    @PutMapping("/config")
    public Map<String, List<SystemConfigView>> updateConfig(
            @Valid @RequestBody SystemConfigUpdateRequest request) {
        return Map.of("items", adminSystemService.updateConfig(CurrentUser.require(), request));
    }

    @GetMapping("/monitoring/summary")
    public Map<String, Object> monitoring() {
        return adminSystemService.monitoring();
    }

    @GetMapping("/audits")
    public PageResult<Map<String, Object>> audits(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String result) {
        return adminSystemService.audits(page, size, action, actorId, result);
    }
}
