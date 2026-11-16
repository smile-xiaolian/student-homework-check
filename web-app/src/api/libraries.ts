import { http } from './http'
import type { Library, LibraryEntry } from '@/types/api'

export const libraryApi = {
  list(courseId: string) {
    return http.get<Library[]>(`/courses/${courseId}/libraries`).then((r) => r.data)
  },
  create(courseId: string, name: string) {
    return http.post<Library>(`/courses/${courseId}/libraries`, { name, libraryType: 'WHITELIST' }).then((r) => r.data)
  },
  remove(libraryId: string) {
    return http.delete(`/libraries/${libraryId}`).then((r) => r.data)
  },
  entries(libraryId: string) {
    return http.get<LibraryEntry[]>(`/libraries/${libraryId}/entries`).then((r) => r.data)
  },
  uploadEntry(libraryId: string, file: File) {
    const form = new FormData()
    form.append('file', file)
    return http.post<LibraryEntry>(`/libraries/${libraryId}/entries`, form, { timeout: 120_000 }).then((r) => r.data)
  },
  removeEntry(libraryId: string, entryId: string) {
    return http.delete(`/libraries/${libraryId}/entries/${entryId}`).then((r) => r.data)
  },
}
