import { http } from './http'
import type { Assignment } from '@/types/api'

export const assignmentApi = {
  get(id: string) {
    return http.get<Assignment>(`/assignments/${id}`).then((r) => r.data)
  },
  create(courseId: string, payload: Record<string, unknown>) {
    return http.post<Assignment>(`/courses/${courseId}/assignments`, payload).then((r) => r.data)
  },
  update(id: string, payload: Record<string, unknown>, etag?: string) {
    return http.patch<Assignment>(`/assignments/${id}`, payload, {
      headers: etag ? { 'If-Match': etag } : undefined,
    }).then((r) => r.data)
  },
  publish(id: string) {
    return http.post<Assignment>(`/assignments/${id}/publish`).then((r) => r.data)
  },
  checkConfig(id: string) {
    return http.get<Record<string, unknown>>(`/assignments/${id}/check-config`).then((r) => r.data)
  },
  updateCheckConfig(id: string, payload: Record<string, unknown>, etag?: string) {
    return http.put<Record<string, unknown>>(`/assignments/${id}/check-config`, payload, {
      headers: etag ? { 'If-Match': etag } : undefined,
    }).then((r) => r.data)
  },
  rubric(id: string) {
    return http.get<Record<string, unknown>>(`/assignments/${id}/rubric`).then((r) => r.data)
  },
  updateRubric(id: string, payload: Record<string, unknown>, etag?: string) {
    return http.put<Record<string, unknown>>(`/assignments/${id}/rubric`, payload, {
      headers: etag ? { 'If-Match': etag } : undefined,
    }).then((r) => r.data)
  },
  progress(id: string) {
    return http.get(`/analytics/assignments/${id}/submission-progress`).then((r) => r.data)
  },
  checkDistribution(id: string) {
    return http.get(`/analytics/assignments/${id}/check-distribution`).then((r) => r.data)
  },
}
