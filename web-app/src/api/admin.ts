import { http } from './http'
import type { AdminUser, AuditLog, ImportResult, PageResult, SystemConfigItem } from '@/types/api'

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
    return http.get<PageResult<AuditLog>>('/admin/audits', { params }).then((r) => r.data)
  },
  auditExport(params: Record<string, unknown> = {}) {
    return http.get<Blob>('/admin/audits/export', { params, responseType: 'blob' }).then((r) => r.data)
  },
  /** Excel 批量导入用户（multipart） */
  importUsers(file: File) {
    const form = new FormData()
    form.append('file', file)
    return http.post<ImportResult>('/admin/users/import', form, { timeout: 120_000 }).then((r) => r.data)
  },
  /** 下载用户导入模板（xlsx） */
  importUsersTemplate() {
    return http.get<Blob>('/admin/users/import-template', { responseType: 'blob' }).then((r) => r.data)
  },
}
