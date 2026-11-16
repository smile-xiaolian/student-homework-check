import { http } from './http'
import type { Assignment, CheckConfig, RubricDefinition } from '@/types/api'

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
  close(id: string) {
    return http.post<Assignment>(`/assignments/${id}/close`).then((r) => r.data)
  },
  archive(id: string) {
    return http.post<Assignment>(`/assignments/${id}/archive`).then((r) => r.data)
  },
  updateResultsVisibility(id: string, visible: boolean) {
    return http.put<Assignment>(`/assignments/${id}/results-visibility`, { visible }).then((r) => r.data)
  },
  remove(id: string) {
    return http.delete(`/assignments/${id}`)
  },
  checkConfig(id: string) {
    return http.get<CheckConfig>(`/assignments/${id}/check-config`).then((r) => ({
      data: r.data,
      etag: r.headers?.etag as string | undefined,
    }))
  },
  updateCheckConfig(id: string, payload: Record<string, unknown>, etag?: string) {
    return http.put<CheckConfig>(`/assignments/${id}/check-config`, payload, {
      headers: etag ? { 'If-Match': etag } : undefined,
    }).then((r) => r.data)
  },
  rubric(id: string) {
    return http.get<RubricDefinition>(`/assignments/${id}/rubric`).then((r) => ({
      data: r.data,
      etag: r.headers?.etag as string | undefined,
    }))
  },
  updateRubric(id: string, payload: Record<string, unknown>, etag?: string) {
    return http.put<RubricDefinition>(`/assignments/${id}/rubric`, payload, {
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
