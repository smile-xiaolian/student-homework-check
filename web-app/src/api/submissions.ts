import { http } from './http'
import type { Submission } from '@/types/api'

export const submissionApi = {
  submit(assignmentId: string, onlineText: string | null, integrityConsent: boolean,
         idempotencyKey: string, fileObjectIds: string[] = []) {
    return http.post<Submission>(`/assignments/${assignmentId}/submissions`, {
      onlineText,
      integrityConsent,
      idempotencyKey,
      fileObjectIds,
    }, { headers: { 'Idempotency-Key': idempotencyKey } }).then((r) => r.data)
  },
  mine(assignmentId: string) {
    return http.get<Submission[]>(`/assignments/${assignmentId}/submissions/mine`).then((r) => r.data)
  },
  list(assignmentId: string) {
    return http.get<Submission[]>(`/assignments/${assignmentId}/submissions`).then((r) => r.data)
  },
  get(id: string) {
    return http.get<Submission>(`/submissions/${id}`).then((r) => r.data)
  },
  status(id: string) {
    return http.get(`/submissions/${id}/status`).then((r) => r.data)
  },
  setEffective(id: string) {
    return http.post<Submission>(`/submissions/${id}/effective`).then((r) => r.data)
  },
}
