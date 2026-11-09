import { http } from './http'
import type { SubmissionFile } from '@/types/api'

export const fileApi = {
  upload(assignmentId: string, file: File, onProgress?: (percent: number) => void) {
    const form = new FormData()
    form.append('file', file)
    return http.post<SubmissionFile>(`/assignments/${assignmentId}/files`, form, {
      timeout: 120_000,
      onUploadProgress: (event) => {
        if (onProgress && event.total) onProgress(Math.round((event.loaded / event.total) * 100))
      },
    }).then((response) => response.data)
  },
  async download(file: SubmissionFile): Promise<void> {
    const response = await http.get<Blob>(`/files/${file.id}/content`, {
      responseType: 'blob',
      timeout: 120_000,
    })
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = file.originalName
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
  },
}
