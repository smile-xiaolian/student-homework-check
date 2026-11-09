import { http } from './http'
import type { Grade, PageResult, Review, ReviewQueueItem } from '@/types/api'

export const reviewApi = {
  queue(assignmentId: string, params: Record<string, unknown> = {}) {
    return http.get<PageResult<ReviewQueueItem>>(`/assignments/${assignmentId}/review-queue`, { params }).then((r) => r.data)
  },
  get(submissionId: string) {
    return http.get<Review>(`/submissions/${submissionId}/review`).then((r) => r.data)
  },
  save(submissionId: string, payload: Record<string, unknown>) {
    return http.put<Review>(`/submissions/${submissionId}/review`, payload).then((r) => r.data)
  },
  submit(submissionId: string) {
    return http.post<Review>(`/submissions/${submissionId}/review/submit`).then((r) => r.data)
  },
  grade(submissionId: string) {
    return http.get<Grade>(`/submissions/${submissionId}/grade`).then((r) => r.data)
  },
  publishGrades(assignmentId: string, submissionIds: string[]) {
    return http.post(`/assignments/${assignmentId}/grades/publish`, { submissionIds }).then((r) => r.data)
  },
  grades(assignmentId: string) {
    return http.get<Grade[]>(`/assignments/${assignmentId}/grades`).then((r) => r.data)
  },
}
