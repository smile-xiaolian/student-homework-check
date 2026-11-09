import { http } from './http'
import type { LoginResponse, Me, SessionUser } from '@/types/api'

export const authApi = {
  login(loginName: string, password: string) {
    return http.post<LoginResponse>('/auth/login', { loginName, password }).then((r) => r.data)
  },
  logout() {
    return http.post<void>('/auth/logout')
  },
  async refresh(): Promise<LoginResponse> {
    const { data } = await http.post<LoginResponse>('/auth/refresh')
    return data
  },
  me() {
    return http.get<Me>('/me').then((r) => r.data)
  },
  updateProfile(payload: { displayName?: string; email?: string | null; phone?: string | null }) {
    return http.patch<Me>('/me', payload).then((r) => r.data)
  },
  changePassword(originalPassword: string, newPassword: string) {
    return http.put<void>('/me/password', { originalPassword, newPassword })
  },
}

export type { SessionUser }
