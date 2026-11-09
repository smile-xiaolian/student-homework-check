package com.edu.submission.notification.adapter.out;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.submission.notification.domain.Notification;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}

