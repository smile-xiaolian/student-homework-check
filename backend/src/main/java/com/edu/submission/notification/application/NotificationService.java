package com.edu.submission.notification.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edu.submission.course.adapter.out.CourseMemberMapper;
import com.edu.submission.notification.adapter.in.dto.NotificationView;
import com.edu.submission.notification.adapter.out.NotificationMapper;
import com.edu.submission.notification.domain.Notification;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.api.PageQuery;
import com.edu.submission.shared.api.PageResult;
import com.edu.submission.shared.api.Pagination;
import com.edu.submission.shared.ids.Ids;
import com.edu.submission.shared.security.UserPrincipal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final CourseMemberMapper courseMemberMapper;

    public NotificationService(NotificationMapper notificationMapper,
                               CourseMemberMapper courseMemberMapper) {
        this.notificationMapper = notificationMapper;
        this.courseMemberMapper = courseMemberMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationView> list(UserPrincipal principal, Integer page, Integer size) {
        PageQuery query = PageQuery.of(page, size);
        List<Notification> notifications = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, principal.id())
                .orderByDesc(Notification::getCreatedAt)
                .last("LIMIT " + query.size() + " OFFSET " + query.offset()));
        long total = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, principal.id()));
        return new PageResult<>(notifications.stream().map(NotificationView::from).toList(),
                Pagination.of(query.page(), query.size(), total));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UserPrincipal principal) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, principal.id())
                .eq(Notification::getIsRead, false));
    }

    @Transactional
    public void markRead(UserPrincipal principal, UUID notificationId) {
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification == null || !notification.getRecipientId().equals(principal.id())) {
            throw ApiException.notFound("NOTIFICATION_NOT_FOUND", "通知不存在", "通知可能已被删除。");
        }
        if (Boolean.TRUE.equals(notification.getIsRead())) {
            return;
        }
        notification.setIsRead(true);
        notification.setReadAt(OffsetDateTime.now(ZoneOffset.UTC));
        notificationMapper.updateById(notification);
    }

    @Transactional
    public void markAllRead(UserPrincipal principal) {
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getRecipientId, principal.id())
                .eq(Notification::getIsRead, false)
                .set(Notification::getIsRead, true)
                .set(Notification::getReadAt, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Transactional
    public void notifyUsers(Collection<UUID> recipientIds, String type, String title,
                            String content, String targetType, UUID targetId) {
        for (UUID recipientId : recipientIds.stream().distinct().toList()) {
            Notification notification = new Notification();
            notification.setId(Ids.newUuidV7());
            notification.setRecipientId(recipientId);
            notification.setNotificationType(type);
            notification.setTitle(title);
            notification.setContent(content);
            notification.setTargetType(targetType);
            notification.setTargetId(targetId);
            notification.setIsRead(false);
            notificationMapper.insert(notification);
        }
    }

    @Transactional
    public void notifyCourseStudents(UUID courseId, String type, String title,
                                     String content, String targetType, UUID targetId) {
        notifyUsers(courseMemberMapper.findActiveStudentIds(courseId), type, title, content,
                targetType, targetId);
    }
}

