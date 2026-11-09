import { ref } from 'vue'
import { defineStore } from 'pinia'
import { notificationApi } from '@/api/notifications'

export const useNotificationStore = defineStore('notification', () => {
  const unread = ref(0)

  async function refreshUnread(): Promise<void> {
    try {
      unread.value = (await notificationApi.unreadCount()).unreadCount
    } catch {
      unread.value = 0
    }
  }

  async function markRead(id: string): Promise<void> {
    await notificationApi.read(id)
    await refreshUnread()
  }

  async function markAllRead(): Promise<void> {
    await notificationApi.readAll()
    unread.value = 0
  }

  return { unread, refreshUnread, markRead, markAllRead }
})
