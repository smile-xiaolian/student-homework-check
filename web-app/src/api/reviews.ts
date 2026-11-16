import { http } from './http'
import type { BatchScorePreview, Grade, PageResult, Review, ReviewQueueItem } from '@/types/api'

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
  /** 成绩更正：已提交/已发布的评审结果可修改，并重新触发通知 */
  modifyGrade(submissionId: string, payload: Record<string, unknown>) {
    return http.patch<Review>(`/submissions/${submissionId}/grade`, payload).then((r) => r.data)
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
  /** 批量打分（规则 ALL_MAX / BY_CHECK_LEVEL / FIXED） */
  batchScore(assignmentId: string, payload: Record<string, unknown>) {
    return http.post(`/assignments/${assignmentId}/grades/batch-score`, payload).then((r) => r.data)
  },
  /** 批量打分预览：返回每份提交的调整前后对照 */
  batchScorePreview(assignmentId: string, payload: Record<string, unknown>) {
    return http.post<BatchScorePreview>(`/assignments/${assignmentId}/grades/batch-score/preview`, payload).then((r) => r.data)
  },
  /** 下载所选提交附件 ZIP（application/zip 流式） */
  zipDownload(assignmentId: string, ids: string[]) {
    return http.get<Blob>(`/assignments/${assignmentId}/submissions/zip`, {
      params: { ids: ids.join(',') },
      responseType: 'blob',
      timeout: 120_000,
    }).then((r) => r.data)
  },
}
