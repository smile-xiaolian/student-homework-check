import { http } from './http'
import type { NotificationItem, PageResult } from '@/types/api'

export const notificationApi = {
  list(params: { read?: boolean; page?: number; size?: number } = {}) {
    return http.get<PageResult<NotificationItem>>('/notifications', { params }).then((r) => r.data)
  },
  unreadCount() {
    return http.get<{ unreadCount: number }>('/notifications/unread-count').then((r) => r.data)
  },
  read(id: string) {
    return http.post(`/notifications/${id}/read`)
  },
  readAll() {
    return http.post('/notifications/read-all')
  },
}
