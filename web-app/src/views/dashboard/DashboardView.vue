<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { courseApi } from '@/api/courses'
import { notificationApi } from '@/api/notifications'
import { errorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { Assignment, Course, NotificationItem } from '@/types/api'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(true)
const courses = ref<Course[]>([])
const assignments = ref<Assignment[]>([])
const notices = ref<NotificationItem[]>([])

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const upcoming = computed(() => assignments.value
  .filter((item) => item.status === 'PUBLISHED')
  .sort((a, b) => +new Date(a.deadlineAt) - +new Date(b.deadlineAt))
  .slice(0, 5))

const stats = computed(() => [
  { label: auth.isManager ? '管理课程' : '在学课程', value: courses.value.filter((item) => item.status === 'ACTIVE').length, tone: 'teal' },
  { label: '进行中作业', value: assignments.value.filter((item) => item.status === 'PUBLISHED').length, tone: 'gold' },
  { label: '未读通知', value: notices.value.filter((item) => !item.read).length, tone: 'rose' },
])

function formatDate(value: string | null): string {
  if (!value) return '未设置'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function daysLeft(value: string): string {
  const diff = new Date(value).getTime() - Date.now()
  if (diff < 0) return '已截止'
  const days = Math.floor(diff / 86_400_000)
  if (days > 0) return `还剩 ${days} 天`
  return `还剩 ${Math.max(1, Math.floor(diff / 3_600_000))} 小时`
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [coursePage, noticePage] = await Promise.all([
      courseApi.list(1, 100),
      notificationApi.list({ page: 1, size: 6 }),
    ])
    courses.value = coursePage.items
    notices.value = noticePage.items
    const assignmentPages = await Promise.all(courses.value.map((course) =>
      courseApi.assignments(course.id).catch(() => [])))
    assignments.value = assignmentPages.flat()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page dashboard">
    <section class="hero">
      <div>
        <p class="kicker">{{ new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date()) }}</p>
        <h1>{{ greeting }}，{{ auth.user?.displayName }}</h1>
        <p>今天也从一件清晰的小事开始。课程动态与待办已经为你汇总。</p>
      </div>
      <button class="hero-action" type="button" @click="router.push('/courses')">
        <small>{{ auth.displayRole }}工作区</small>
        <strong>进入课程空间</strong><span>→</span>
      </button>
    </section>

    <el-skeleton :loading="loading" animated :rows="6">
      <template #default>
        <div class="stat-grid">
          <div v-for="stat in stats" :key="stat.label" class="stat-card" :class="stat.tone">
            <span>{{ stat.label }}</span><strong>{{ stat.value.toString().padStart(2, '0') }}</strong>
          </div>
          <div class="stat-card highlight">
            <span>当前身份</span><strong>{{ auth.displayRole }}</strong>
            <small>{{ auth.user?.userNo }}</small>
          </div>
        </div>

        <div class="split-grid dash-grid">
          <section class="surface surface-pad">
            <div class="section-head">
              <div><h2>近期作业</h2><p>按截止时间排序</p></div>
              <button type="button" @click="router.push('/courses')">全部课程 →</button>
            </div>
            <div v-if="upcoming.length" class="assignment-list">
              <button v-for="item in upcoming" :key="item.id" type="button" class="assignment-row"
                      @click="router.push(`/assignments/${item.id}`)">
                <span class="date-block">
                  <b>{{ new Date(item.deadlineAt).getDate().toString().padStart(2, '0') }}</b>
                  <small>{{ new Date(item.deadlineAt).getMonth() + 1 }}月</small>
                </span>
                <span class="assignment-copy">
                  <strong>{{ item.title }}</strong>
                  <small>{{ formatDate(item.deadlineAt) }} 截止 · 权重 {{ item.weight }}%</small>
                </span>
                <span class="deadline-chip" :class="{ urgent: daysLeft(item.deadlineAt).includes('小时') }">{{ daysLeft(item.deadlineAt) }}</span>
              </button>
            </div>
            <div v-else class="empty-line">暂无进行中的作业</div>
          </section>

          <aside class="surface surface-pad">
            <div class="section-head"><div><h2>最新通知</h2><p>站内消息与提醒</p></div>
              <button type="button" @click="router.push('/notifications')">查看全部</button>
            </div>
            <div v-if="notices.length" class="notice-list">
              <button v-for="item in notices" :key="item.id" type="button" class="notice-row"
                      @click="router.push('/notifications')">
                <i :class="{ unread: !item.read }" />
                <span><strong>{{ item.title }}</strong><small>{{ formatDate(item.createdAt) }}</small></span>
              </button>
            </div>
            <div v-else class="empty-line">暂无通知</div>
          </aside>
        </div>

        <section class="surface surface-pad quick-section">
          <div class="section-head"><div><h2>快捷入口</h2><p>第一轮迭代核心能力</p></div></div>
          <div class="quick-grid">
            <button type="button" @click="router.push('/courses')"><b>课程空间</b><span>查看花名册、邀请码与课程作业</span></button>
            <button type="button" @click="router.push('/notifications')"><b>通知中心</b><span>查收作业发布、查重完成与系统提醒</span></button>
            <button v-if="auth.canReview" type="button" @click="router.push('/courses')"><b>评审入口</b><span>从作业详情进入评审与成绩工作台</span></button>
            <button v-if="auth.hasRole('ADMIN')" type="button" @click="router.push('/admin/system')"><b>系统管理</b><span>参数、监控与审计日志</span></button>
          </div>
        </section>
      </template>
    </el-skeleton>
  </div>
</template>

<style scoped>
.hero { display: flex; justify-content: space-between; gap: 24px; align-items: flex-end; padding: 12px 0 27px; }
.kicker { color: #0f766e; font-size: 12px; font-weight: 800; letter-spacing: .06em; margin-bottom: 10px; }
.hero h1 { font-size: clamp(30px, 4vw, 44px); letter-spacing: -.05em; margin-bottom: 10px; }
.hero p { color: #687388; margin: 0; }
.hero-action { min-width: 210px; text-align: left; border: 0; border-radius: 17px; padding: 17px 18px; background: #0f766e; color: white; display: grid; grid-template-columns: 1fr auto; align-items: end; gap: 3px 18px; cursor: pointer; box-shadow: 0 14px 30px rgba(15,118,110,.18); }
.hero-action small { grid-column: 1 / -1; color: #a7ded6; font-size: 10px; letter-spacing: .08em; }
.hero-action strong { font-size: 14px; }
.hero-action span { font-size: 21px; }
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 15px; margin-bottom: 20px; }
.stat-card { background: white; border: 1px solid #e3e9ed; border-radius: 17px; padding: 19px 21px; min-height: 116px; display: flex; flex-direction: column; }
.stat-card > span { color: #778292; font-size: 12px; }
.stat-card strong { font-size: 38px; letter-spacing: -.06em; line-height: 1.25; margin-top: auto; }
.stat-card small { color: #86909e; font-size: 11px; }
.stat-card.teal { background: #e7f8f4; border-color: #cfeee7; }
.stat-card.teal strong { color: #0f766e; }
.stat-card.gold { background: #fff8e5; border-color: #f3e7b9; }
.stat-card.gold strong { color: #9a6a08; }
.stat-card.rose { background: #fff0ef; border-color: #f6dcd9; }
.stat-card.rose strong { color: #b34235; }
.stat-card.highlight { background: #143333; color: white; border-color: #143333; }
.stat-card.highlight > span, .stat-card.highlight small { color: #a7c2be; }
.stat-card.highlight strong { font-size: 26px; color: #f4c95d; }
.dash-grid { margin-bottom: 20px; }
.section-head { display: flex; justify-content: space-between; align-items: start; gap: 15px; margin-bottom: 17px; }
.section-head h2 { font-size: 18px; margin: 0 0 4px; letter-spacing: -.02em; }
.section-head p { color: #8a94a2; font-size: 12px; margin: 0; }
.section-head button { border: 0; background: transparent; color: #0f766e; font-size: 12px; cursor: pointer; padding: 4px 0; }
.assignment-list { display: flex; flex-direction: column; }
.assignment-row { display: flex; align-items: center; gap: 14px; width: 100%; text-align: left; border: 0; border-top: 1px solid #edf1f2; background: transparent; padding: 14px 0; cursor: pointer; }
.assignment-row:first-child { border-top: 0; padding-top: 5px; }
.assignment-row:hover .assignment-copy strong { color: #0f766e; }
.date-block { width: 45px; height: 47px; background: #f3f7f6; border-radius: 10px; display: grid; place-content: center; text-align: center; flex: 0 0 45px; }
.date-block b { font-size: 17px; line-height: 1; color: #24413e; }
.date-block small { color: #8b9996; font-size: 9px; margin-top: 4px; }
.assignment-copy { min-width: 0; flex: 1; }
.assignment-copy strong, .assignment-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.assignment-copy strong { font-size: 13px; margin-bottom: 5px; }
.assignment-copy small { color: #84909f; font-size: 11px; }
.deadline-chip { background: #f2f6f6; color: #63807b; padding: 5px 8px; border-radius: 7px; font-size: 10px; white-space: nowrap; }
.deadline-chip.urgent { background: #fff0ef; color: #b34235; }
.notice-list { display: flex; flex-direction: column; }
.notice-row { display: flex; gap: 10px; border: 0; border-top: 1px solid #edf1f2; background: transparent; text-align: left; padding: 12px 0; cursor: pointer; }
.notice-row:first-child { border-top: 0; padding-top: 5px; }
.notice-row i { width: 6px; height: 6px; border-radius: 50%; background: #d1d8de; margin-top: 6px; flex: 0 0 6px; }
.notice-row i.unread { background: #e45745; box-shadow: 0 0 0 3px #fdeae7; }
.notice-row strong, .notice-row small { display: block; }
.notice-row strong { font-size: 12px; line-height: 1.5; }
.notice-row small { color: #9aa3af; font-size: 10px; margin-top: 4px; }
.quick-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.quick-grid button { border: 1px solid #e5ebed; background: #fafcfc; text-align: left; border-radius: 12px; padding: 15px; cursor: pointer; transition: .15s ease; }
.quick-grid button:hover { background: #effaf7; border-color: #c8eae3; transform: translateY(-1px); }
.quick-grid b, .quick-grid span { display: block; }
.quick-grid b { font-size: 13px; margin-bottom: 6px; }
.quick-grid span { color: #83909d; font-size: 11px; line-height: 1.55; }
@media (max-width: 1050px) { .stat-grid { grid-template-columns: repeat(2, 1fr); } .quick-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 640px) { .hero { align-items: start; flex-direction: column; } .hero-action { width: 100%; } .stat-grid { grid-template-columns: 1fr 1fr; } .quick-grid { grid-template-columns: 1fr; } .deadline-chip { display: none; } }
</style>
