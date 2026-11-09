import { http } from './http'
import type { CheckDistribution, SubmissionProgress } from '@/types/api'

export const analyticsApi = {
  submissionProgress(assignmentId: string) {
    return http.get<SubmissionProgress>(`/analytics/assignments/${assignmentId}/submission-progress`).then((r) => r.data)
  },
  checkDistribution(assignmentId: string) {
    return http.get<CheckDistribution>(`/analytics/assignments/${assignmentId}/check-distribution`).then((r) => r.data)
  },
  courseScores(courseId: string) {
    return http.get(`/analytics/courses/${courseId}/total-scores`).then((r) => r.data)
  },
}
