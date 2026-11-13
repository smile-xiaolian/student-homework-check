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
        UUID submissionId,
        boolean read,
        OffsetDateTime readAt,
        OffsetDateTime createdAt
) {
    public static NotificationView from(Notification notification) {
        return from(notification, null);
    }

    /** submissionId 仅在 targetType=CHECK_REPORT 时有值：查重报告所属的提交，用于前端跳转报告页。 */
    public static NotificationView from(Notification notification, UUID submissionId) {
        return new NotificationView(
                notification.getId(), notification.getNotificationType(), notification.getTitle(),
                notification.getContent(), notification.getTargetType(), notification.getTargetId(),
                submissionId,
                Boolean.TRUE.equals(notification.getIsRead()), notification.getReadAt(),
                notification.getCreatedAt());
    }
}
