import { http } from './http'
import type { Assignment, Course, CourseMember, PageResult } from '@/types/api'

export const courseApi = {
  list(page = 1, size = 100) {
    return http.get<PageResult<Course>>('/courses', { params: { page, size } }).then((r) => r.data)
  },
  get(id: string) {
    return http.get<Course>(`/courses/${id}`).then((r) => r.data)
  },
  create(payload: Record<string, unknown>) {
    return http.post<Course>('/courses', payload).then((r) => r.data)
  },
  update(id: string, payload: Record<string, unknown>, etag?: string) {
    return http.patch<Course>(`/courses/${id}`, payload, {
      headers: etag ? { 'If-Match': etag } : undefined,
    }).then((r) => r.data)
  },
  archive(id: string, etag?: string) {
    return http.post<Course>(`/courses/${id}/archive`, null, {
      headers: etag ? { 'If-Match': etag } : undefined,
    }).then((r) => r.data)
  },
  join(inviteCode: string) {
    return http.post<Course>('/courses/join', { inviteCode }).then((r) => r.data)
  },
  members(id: string) {
    return http.get<CourseMember[]>(`/courses/${id}/members`).then((r) => r.data)
  },
  addMember(id: string, payload: { userNo: string; memberRole: string }) {
    return http.post<CourseMember>(`/courses/${id}/members`, payload).then((r) => r.data)
  },
  rotateInviteCode(id: string) {
    return http.post<Course>(`/courses/${id}/invite-code/rotate`).then((r) => r.data)
  },
  assignments(courseId: string) {
    return http.get<Assignment[]>(`/courses/${courseId}/assignments`).then((r) => r.data)
  },
}
