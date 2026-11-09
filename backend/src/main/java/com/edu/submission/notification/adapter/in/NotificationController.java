package com.edu.submission.notification.adapter.in;

import com.edu.submission.notification.adapter.in.dto.NotificationView;
import com.edu.submission.notification.application.NotificationService;
import com.edu.submission.shared.api.PageResult;
import com.edu.submission.shared.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public PageResult<NotificationView> list(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return notificationService.list(CurrentUser.require(), page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notificationService.unreadCount(CurrentUser.require()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        notificationService.markRead(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        notificationService.markAllRead(CurrentUser.require());
        return ResponseEntity.noContent().build();
    }
}

