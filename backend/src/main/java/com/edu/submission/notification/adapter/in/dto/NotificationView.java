package com.edu.submission.notification.adapter.in.dto;

import com.edu.submission.notification.domain.Notification;
import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationView(
        UUID id,
        String notificationType,
        String title,
        String content,
        String targetType,
        UUID targetId,
        boolean read,
        OffsetDateTime readAt,
        OffsetDateTime createdAt
) {
    public static NotificationView from(Notification notification) {
        return new NotificationView(
                notification.getId(), notification.getNotificationType(), notification.getTitle(),
                notification.getContent(), notification.getTargetType(), notification.getTargetId(),
                Boolean.TRUE.equals(notification.getIsRead()), notification.getReadAt(),
                notification.getCreatedAt());
    }
}

