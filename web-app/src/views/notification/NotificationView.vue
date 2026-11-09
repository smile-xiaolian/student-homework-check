<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { notificationApi } from '@/api/notifications'
import { errorMessage } from '@/api/http'
import { useNotificationStore } from '@/stores/notification'
import type { NotificationItem, Pagination } from '@/types/api'

const store = useNotificationStore()
const loading = ref(false)
const filter = ref<'ALL' | 'UNREAD'>('ALL')
const items = ref<NotificationItem[]>([])
const pagination = ref<Pagination>({ page: 1, size: 20, total: 0, totalPages: 0, hasNext: false })
const selected = ref<NotificationItem | null>(null)

const displayed = computed(() => filter.value === 'UNREAD' ? items.value.filter((item) => !item.read) : items.value)

function typeLabel(type: string): string {
  return ({
    ASSIGNMENT_PUBLISHED: '作业发布', CHECK_COMPLETED: '查重完成', CHECK_HIGH_RISK: '高风险提醒',
    GRADE_PUBLISHED: '成绩发布', COURSE_ANNOUNCEMENT: '课程公告', SYSTEM: '系统通知',
  } as Record<string, string>)[type] ?? '通知'
}

function format(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

async function load(page = 1): Promise<void> {
  loading.value = true
  try {
    const result = await notificationApi.list({ page, size: 20 })
    items.value = result.items
    pagination.value = result.pagination
    await store.refreshUnread()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function open(item: NotificationItem): Promise<void> {
  selected.value = item
  if (!item.read) {
    try {
      await store.markRead(item.id)
      item.read = true
    } catch (error) {
      ElMessage.error(errorMessage(error))
    }
  }
}

async function readAll(): Promise<void> {
  try {
    await store.markAllRead()
    items.value.forEach((item) => { item.read = true })
    ElMessage.success('全部通知已标记为已读')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

watch(filter, () => { if (filter.value === 'UNREAD') selected.value = null })
onMounted(() => load())
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div><p class="eyebrow">INBOX</p><h1>通知中心</h1><p>作业发布、查重完成与系统提醒都汇聚在这里。</p></div>
      <el-button :disabled="store.unread === 0" @click="readAll">全部标为已读</el-button>
    </div>
    <div class="notice-layout">
      <section class="surface notice-list-panel">
        <div class="list-head">
          <div class="tabs"><button :class="{ active: filter === 'ALL' }" @click="filter = 'ALL'">全部</button><button :class="{ active: filter === 'UNREAD' }" @click="filter = 'UNREAD'">未读 <span v-if="store.unread">{{ store.unread }}</span></button></div>
          <span>{{ pagination.total }} 条消息</span>
        </div>
        <el-skeleton :loading="loading" animated :rows="7">
          <template #default>
            <div v-if="displayed.length" class="notice-items">
              <button v-for="item in displayed" :key="item.id" type="button" class="notice-item" :class="{ selected: selected?.id === item.id, unread: !item.read }" @click="open(item)">
                <span class="mark" />
                <span class="item-copy"><strong>{{ item.title }}</strong><small>{{ typeLabel(item.notificationType) }} · {{ format(item.createdAt) }}</small><p>{{ item.content }}</p></span>
                <span class="arrow">→</span>
              </button>
            </div>
            <div v-else class="empty-line">暂无{{ filter === 'UNREAD' ? '未读' : '' }}通知</div>
          </template>
        </el-skeleton>
        <el-pagination v-if="pagination.totalPages > 1" v-model:current-page="pagination.page" :page-size="pagination.size" :total="pagination.total" layout="prev, pager, next" class="pager" @current-change="load" />
      </section>
      <aside class="surface surface-pad detail-card">
        <template v-if="selected">
          <div class="detail-type">{{ typeLabel(selected.notificationType) }}</div>
          <h2>{{ selected.title }}</h2>
          <p class="detail-time">{{ format(selected.createdAt) }} · {{ selected.read ? '已读' : '未读' }}</p>
          <div class="detail-content">{{ selected.content }}</div>
        </template>
        <div v-else class="empty-detail"><span>◉</span><h3>选择一条通知</h3><p>通知正文会在这里展开。</p></div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.eyebrow { color: #0f766e; font-size: 11px; font-weight: 800; letter-spacing: .14em; margin-bottom: 9px; }
.notice-layout { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(280px, .8fr); gap: 18px; align-items: start; }
.notice-list-panel { overflow: hidden; }
.list-head { padding: 17px 20px 0; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #edf1f2; }
.list-head > span { color: #99a3ad; font-size: 10px; }
.tabs { display: flex; gap: 21px; }
.tabs button { padding: 0 0 13px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: #8a96a2; font-size: 13px; cursor: pointer; }
.tabs button.active { color: #0f766e; border-bottom-color: #0f766e; font-weight: 700; }
.tabs span { background: #ef4d3f; color: white; border-radius: 20px; padding: 1px 5px; font-size: 9px; vertical-align: 2px; }
.notice-item { display: flex; gap: 12px; width: 100%; border: 0; border-bottom: 1px solid #edf1f2; background: transparent; padding: 16px 20px; text-align: left; cursor: pointer; align-items: flex-start; }
.notice-item:hover, .notice-item.selected { background: #f7fcfb; }
.mark { width: 7px; height: 7px; border-radius: 50%; background: #d5dce1; margin-top: 6px; flex: 0 0 7px; }
.unread .mark { background: #e55c4d; box-shadow: 0 0 0 3px #fdeae7; }
.item-copy { min-width: 0; flex: 1; }
.item-copy strong { display: block; font-size: 13px; margin-bottom: 5px; }
.unread .item-copy strong { font-weight: 800; }
.item-copy small { display: block; color: #96a1ab; font-size: 10px; }
.item-copy p { color: #7e8a96; font-size: 11px; line-height: 1.55; margin: 7px 0 0; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.arrow { color: #adc0bb; font-size: 15px; margin-top: 2px; }
.pager { justify-content: center; padding: 16px; }
.detail-card { min-height: 340px; position: sticky; top: 24px; }
.detail-type { display: inline-block; background: #e4f6f1; color: #0f766e; padding: 4px 8px; border-radius: 5px; font-size: 10px; margin-bottom: 17px; }
.detail-card h2 { font-size: 22px; letter-spacing: -.03em; line-height: 1.4; margin-bottom: 9px; }
.detail-time { color: #97a2ad; font-size: 10px; margin-bottom: 21px; }
.detail-content { color: #52606d; font-size: 13px; line-height: 1.9; white-space: pre-wrap; }
.empty-detail { height: 300px; display: grid; place-content: center; text-align: center; color: #9ca7b1; }
.empty-detail > span { font-size: 37px; color: #b7c8c4; margin-bottom: 13px; }
.empty-detail h3 { color: #57646f; margin-bottom: 6px; }
.empty-detail p { font-size: 11px; margin: 0; }
@media (max-width: 800px) { .notice-layout { grid-template-columns: 1fr; } .detail-card { position: static; } }
</style>
