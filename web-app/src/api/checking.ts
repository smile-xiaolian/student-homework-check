import { http } from './http'
import type { CheckReport, CheckSegment, CheckSource, CheckTask } from '@/types/api'

export const checkingApi = {
  task(id: string) {
    return http.get<CheckTask>(`/check-tasks/${id}`).then((r) => r.data)
  },
  currentReport(submissionId: string) {
    return http.get<CheckReport>(`/submissions/${submissionId}/check-reports/current`).then((r) => r.data)
  },
  reportHistory(submissionId: string) {
    return http.get<{ items: CheckReport[] }>(`/submissions/${submissionId}/check-reports`).then((r) => r.data)
  },
  report(id: string) {
    return http.get<CheckReport>(`/check-reports/${id}`).then((r) => r.data)
  },
  segments(id: string, params: { cursor?: string; size?: number } = {}) {
    return http.get<{ items: CheckSegment[]; nextCursor: string | null }>(`/check-reports/${id}/segments`, { params }).then((r) => r.data)
  },
  sources(id: string, params: { cursor?: string; size?: number } = {}) {
    return http.get<{ items: CheckSource[]; nextCursor: string | null }>(`/check-reports/${id}/sources`, { params }).then((r) => r.data)
  },
  recheck(submissionId: string, reason: string) {
    return http.post(`/submissions/${submissionId}/rechecks`, { reason }).then((r) => r.data)
  },
}
