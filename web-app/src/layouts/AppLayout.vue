<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import type { Role } from '@/types/api'

interface NavItem { label: string; path: string; icon: string; roles?: Role[] }

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()
const mobileOpen = ref(false)

const navItems: NavItem[] = [
  { label: '工作台', path: '/', icon: '◫' },
  { label: '课程空间', path: '/courses', icon: '▤' },
  { label: '通知中心', path: '/notifications', icon: '◉' },
  { label: '个人中心', path: '/profile', icon: '○' },
  { label: '用户管理', path: '/admin/users', icon: '♙', roles: ['ADMIN'] },
  { label: '系统管理', path: '/admin/system', icon: '⚙', roles: ['ADMIN'] },
]

const visibleNav = computed(() => navItems.filter((item) =>
  !item.roles || auth.hasRole(...item.roles)))

const activePath = computed(() => {
  if (route.path.startsWith('/courses')) return '/courses'
  if (route.path.startsWith('/admin/users')) return '/admin/users'
  if (route.path.startsWith('/admin/system')) return '/admin/system'
  return route.path
})

function navigate(path: string): void {
  mobileOpen.value = false
  router.push(path)
}

async function signOut(): Promise<void> {
  await auth.logout()
  ElMessage.success('已安全退出')
  router.replace('/login')
}

onMounted(() => notifications.refreshUnread())
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ open: mobileOpen }">
      <button class="brand" type="button" @click="navigate('/')">
        <span class="brand-mark">知</span>
        <span><strong>知行作业</strong><small>提交 · 查重 · 评审</small></span>
      </button>
      <nav class="nav-list" aria-label="主导航">
        <button v-for="item in visibleNav" :key="item.path" type="button"
                class="nav-item" :class="{ active: activePath === item.path }"
                @click="navigate(item.path)">
          <span class="nav-icon">{{ item.icon }}</span>{{ item.label }}
          <span v-if="item.path === '/notifications' && notifications.unread" class="unread-badge">
            {{ notifications.unread > 99 ? '99+' : notifications.unread }}
          </span>
        </button>
      </nav>
      <div class="sidebar-foot">
        <div class="user-card">
          <span class="avatar">{{ auth.user?.displayName?.slice(0, 1) }}</span>
          <span class="user-copy">
            <strong>{{ auth.user?.displayName }}</strong>
            <small>{{ auth.displayRole }} · {{ auth.user?.userNo }}</small>
          </span>
        </div>
        <button class="logout" type="button" @click="signOut">退出登录</button>
      </div>
    </aside>
    <div v-if="mobileOpen" class="sidebar-mask" @click="mobileOpen = false" />
    <main class="main-area">
      <header class="mobile-header">
        <button type="button" class="menu-trigger" @click="mobileOpen = true">☰</button>
        <strong>知行作业</strong>
        <span class="mini-avatar">{{ auth.user?.displayName?.slice(0, 1) }}</span>
      </header>
      <div class="content"><router-view /></div>
    </main>
  </div>
</template>

<style scoped>
.app-shell { min-height: 100vh; display: flex; background: #f4f7f8; }
.sidebar { width: 252px; flex: 0 0 252px; background: #102a2a; color: #e8f5f2; display: flex; flex-direction: column; padding: 22px 16px; min-height: 100vh; position: sticky; top: 0; }
.brand { background: none; border: 0; color: inherit; display: flex; align-items: center; gap: 12px; padding: 4px 8px 24px; cursor: pointer; text-align: left; }
.brand-mark { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 14px; background: #2dd4bf; color: #0f302e; font-size: 21px; font-weight: 900; box-shadow: 0 8px 20px rgba(45, 212, 191, .2); }
.brand strong { display: block; font-size: 17px; letter-spacing: .02em; }
.brand small { display: block; color: #9dc4be; font-size: 11px; margin-top: 3px; }
.nav-list { display: flex; flex-direction: column; gap: 5px; flex: 1; }
.nav-item { border: 0; background: transparent; color: #b9d4d0; padding: 11px 13px; border-radius: 11px; display: flex; gap: 11px; align-items: center; font-size: 14px; cursor: pointer; text-align: left; transition: .18s ease; }
.nav-item:hover { background: rgba(255,255,255,.07); color: white; }
.nav-item.active { background: #c9f7eb; color: #0f3f3b; font-weight: 700; }
.nav-icon { width: 20px; text-align: center; font-size: 15px; }
.unread-badge { margin-left: auto; min-width: 19px; height: 19px; padding: 0 6px; border-radius: 20px; background: #ef4444; color: white; font-size: 10px; line-height: 19px; text-align: center; }
.sidebar-foot { border-top: 1px solid rgba(255,255,255,.1); padding-top: 15px; }
.user-card { display: flex; align-items: center; gap: 10px; padding: 4px 4px 13px; }
.avatar, .mini-avatar { width: 35px; height: 35px; border-radius: 50%; background: #f4c95d; color: #49380a; display: grid; place-items: center; font-weight: 800; flex: 0 0 35px; }
.user-copy { min-width: 0; }
.user-copy strong, .user-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.user-copy strong { font-size: 13px; }
.user-copy small { color: #9dc4be; font-size: 11px; margin-top: 3px; }
.logout { width: 100%; border: 1px solid rgba(255,255,255,.16); background: transparent; color: #c3d9d5; border-radius: 9px; padding: 8px; cursor: pointer; }
.logout:hover { background: rgba(255,255,255,.07); color: white; }
.main-area { flex: 1; min-width: 0; }
.content { padding: 34px 38px 48px; }
.mobile-header { display: none; }
.sidebar-mask { display: none; }
@media (max-width: 860px) {
  .sidebar { position: fixed; z-index: 30; left: 0; transform: translateX(-105%); transition: transform .2s ease; min-height: 100vh; }
  .sidebar.open { transform: translateX(0); }
  .sidebar-mask { display: block; position: fixed; inset: 0; z-index: 20; background: rgba(5, 20, 20, .45); }
  .mobile-header { height: 58px; padding: 0 18px; display: flex; align-items: center; justify-content: space-between; background: white; border-bottom: 1px solid #e3e9ed; position: sticky; top: 0; z-index: 10; }
  .menu-trigger { border: 0; background: transparent; font-size: 20px; cursor: pointer; }
  .mini-avatar { width: 30px; height: 30px; flex-basis: 30px; font-size: 12px; }
  .content { padding: 22px 16px 36px; }
}
</style>
