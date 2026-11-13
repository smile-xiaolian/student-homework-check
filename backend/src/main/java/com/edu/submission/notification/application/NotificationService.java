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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final CourseMemberMapper courseMemberMapper;
    private final JdbcTemplate jdbcTemplate;

    public NotificationService(NotificationMapper notificationMapper,
                               CourseMemberMapper courseMemberMapper,
                               JdbcTemplate jdbcTemplate) {
        this.notificationMapper = notificationMapper;
        this.courseMemberMapper = courseMemberMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationView> list(UserPrincipal principal, Integer page, Integer size) {
        PageQuery query = PageQuery.of(page, size);
        // 关联 check_report：查重报告类通知补充所属提交 ID，供前端跳转到报告页
        List<NotificationView> items = jdbcTemplate.query("""
                        SELECT n.id, n.notification_type, n.title, n.content, n.target_type, n.target_id,
                               n.is_read, n.read_at, n.created_at, cr.submission_id AS submission_id
                        FROM app.notification n
                        LEFT JOIN app.check_report cr
                               ON n.target_type = 'CHECK_REPORT' AND cr.id = n.target_id
                        WHERE n.recipient_id = ?
                        ORDER BY n.created_at DESC
                        LIMIT ? OFFSET ?
                        """,
                (rs, index) -> new NotificationView(
                        rs.getObject("id", UUID.class),
                        rs.getString("notification_type"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getString("target_type"),
                        rs.getObject("target_id", UUID.class),
                        rs.getObject("submission_id", UUID.class),
                        rs.getBoolean("is_read"),
                        rs.getObject("read_at", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class)),
                principal.id(), query.size(), query.offset());
        Long total = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, principal.id()));
        return new PageResult<>(items,
                Pagination.of(query.page(), query.size(), total == null ? 0 : total));
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

