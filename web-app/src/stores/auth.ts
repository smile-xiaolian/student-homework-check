import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { getAccessToken, setAccessToken } from '@/api/token'
import type { Me, Role } from '@/types/api'

const roleLabels: Record<Role, string> = {
  STUDENT: '学生', TEACHER: '教师', ASSISTANT: '助教', ADMIN: '管理员',
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<Me | null>(null)
  const initialized = ref(false)
  const loading = ref(false)

  const primaryRole = computed<Role | null>(() => user.value?.roles?.[0] ?? null)
  const displayRole = computed(() => primaryRole.value ? roleLabels[primaryRole.value] : '')
  const isManager = computed(() => ['TEACHER', 'ADMIN'].includes(primaryRole.value ?? ''))
  const canReview = computed(() => ['TEACHER', 'ADMIN'].includes(primaryRole.value ?? ''))

  function applySession(me: Me): void {
    user.value = me
    initialized.value = true
  }

  async function login(loginName: string, password: string): Promise<void> {
    loading.value = true
    try {
      const result = await authApi.login(loginName.trim(), password)
      setAccessToken(result.accessToken)
      const me = await authApi.me()
      applySession(me)
    } finally {
      loading.value = false
    }
  }

  async function ensureSession(): Promise<boolean> {
    if (user.value) return true
    if (loading.value) {
      while (loading.value) await new Promise((resolve) => setTimeout(resolve, 30))
      return Boolean(user.value)
    }
    loading.value = true
    try {
      if (!getAccessToken()) {
        try {
          const refreshed = await authApi.refresh()
          setAccessToken(refreshed.accessToken)
        } catch {
          initialized.value = true
          return false
        }
      }
      applySession(await authApi.me())
      return true
    } catch {
      setAccessToken(null)
      initialized.value = true
      return false
    } finally {
      loading.value = false
    }
  }

  async function refreshProfile(): Promise<void> {
    const me = await authApi.me()
    applySession(me)
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      setAccessToken(null)
      user.value = null
    }
  }

  function hasRole(...roles: Role[]): boolean {
    const role = primaryRole.value
    return Boolean(role && roles.includes(role))
  }

  return {
    user, initialized, loading, primaryRole, displayRole, isManager, canReview,
    login, ensureSession, refreshProfile, logout, hasRole,
  }
})
