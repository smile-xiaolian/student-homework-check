import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { getAccessToken, setAccessToken } from './token'

interface RetryConfig extends InternalAxiosRequestConfig {
  _retry?: boolean
}

export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  code?: string
  detail?: string
  traceId?: string
  fieldErrors?: Array<{ field: string; message: string }>
}

export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 20_000,
  withCredentials: true,
})

http.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

let refreshPromise: Promise<string> | null = null

async function refreshAccessToken(): Promise<string> {
  const response = await axios.post<{ accessToken: string }>(
    '/api/v1/auth/refresh', null, { withCredentials: true, timeout: 15_000 })
  setAccessToken(response.data.accessToken)
  return response.data.accessToken
}

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ProblemDetail>) => {
    const config = error.config as RetryConfig | undefined
    const url = config?.url ?? ''
    const canRetry = config && !config._retry && error.response?.status === 401
      && !url.includes('/auth/login') && !url.includes('/auth/refresh')
    if (canRetry) {
      config._retry = true
      try {
        refreshPromise ??= refreshAccessToken().finally(() => { refreshPromise = null })
        const token = await refreshPromise
        config.headers.Authorization = `Bearer ${token}`
        return http.request(config)
      } catch {
        setAccessToken(null)
      }
    }
    return Promise.reject(error)
  },
)

export function errorMessage(error: unknown): string {
  if (axios.isAxiosError<ProblemDetail>(error)) {
    const problem = error.response?.data
    if (problem?.fieldErrors?.length) return problem.fieldErrors.map((item) => item.message).join('；')
    return problem?.detail || problem?.title || '请求失败，请稍后重试。'
  }
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}
