import { http } from './http'
import type { AdminUser, PageResult, SystemConfigItem } from '@/types/api'

export const adminApi = {
  users(params: Record<string, unknown> = {}) {
    return http.get<PageResult<AdminUser>>('/admin/users', { params }).then((r) => r.data)
  },
  user(id: string) {
    return http.get<AdminUser>(`/admin/users/${id}`).then((r) => r.data)
  },
  createUser(payload: Record<string, unknown>) {
    return http.post<AdminUser>('/admin/users', payload).then((r) => r.data)
  },
  updateUser(id: string, payload: Record<string, unknown>) {
    return http.patch<AdminUser>(`/admin/users/${id}`, payload).then((r) => r.data)
  },
  deactivate(id: string) { return http.post(`/admin/users/${id}/deactivate`) },
  activate(id: string) { return http.post(`/admin/users/${id}/activate`) },
  unlock(id: string) { return http.post(`/admin/users/${id}/unlock`) },
  resetPassword(id: string, newPassword: string) {
    return http.post(`/admin/users/${id}/reset-password`, { newPassword })
  },
  config() {
    return http.get<{ items: SystemConfigItem[] }>('/admin/config').then((r) => r.data)
  },
  updateConfig(items: Array<{ key: string; value: unknown; version: number }>) {
    return http.put<{ items: SystemConfigItem[] }>('/admin/config', { items }).then((r) => r.data)
  },
  monitoring() {
    return http.get<Record<string, unknown>>('/admin/monitoring/summary').then((r) => r.data)
  },
  audits(params: Record<string, unknown> = {}) {
    return http.get<PageResult<Record<string, unknown>>>('/admin/audits', { params }).then((r) => r.data)
  },
}
