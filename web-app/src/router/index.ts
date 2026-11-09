import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import type { Role } from '@/types/api'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    roles?: Role[]
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/auth/LoginView.vue'), meta: { public: true, title: '登录' } },
    {
      path: '/',
      component: () => import('@/layouts/AppLayout.vue'),
      children: [
        { path: '', name: 'dashboard', component: () => import('@/views/dashboard/DashboardView.vue'), meta: { title: '工作台' } },
        { path: 'courses', name: 'courses', component: () => import('@/views/course/CourseListView.vue'), meta: { title: '课程' } },
        { path: 'courses/:id', name: 'course-detail', component: () => import('@/views/course/CourseDetailView.vue'), meta: { title: '课程详情' } },
        { path: 'assignments/:id', name: 'assignment-detail', component: () => import('@/views/assignment/AssignmentDetailView.vue'), meta: { title: '作业详情' } },
        { path: 'submissions/:id/report', name: 'check-report', component: () => import('@/views/checking/CheckReportView.vue'), meta: { title: '查重报告' } },
        { path: 'notifications', name: 'notifications', component: () => import('@/views/notification/NotificationView.vue'), meta: { title: '通知中心' } },
        { path: 'profile', name: 'profile', component: () => import('@/views/profile/ProfileView.vue'), meta: { title: '个人中心' } },
        { path: 'admin/users', name: 'admin-users', component: () => import('@/views/admin/UserAdminView.vue'), meta: { title: '用户管理', roles: ['ADMIN'] } },
        { path: 'admin/system', name: 'admin-system', component: () => import('@/views/admin/SystemAdminView.vue'), meta: { title: '系统管理', roles: ['ADMIN'] } },
      ],
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue'), meta: { public: true, title: '页面不存在' } },
  ],
})

router.beforeEach(async (to) => {
  document.title = `${to.meta.title ?? '知行作业'} · 学生作业提交与查重系统`
  const auth = useAuthStore()
  if (to.meta.public) {
    if (to.name === 'login' && (await auth.ensureSession())) return { name: 'dashboard' }
    return true
  }
  if (!(await auth.ensureSession())) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.roles?.length && !auth.hasRole(...to.meta.roles)) {
    return { name: 'dashboard' }
  }
  return true
})

export default router
