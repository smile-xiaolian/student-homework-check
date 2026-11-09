<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api/admin'
import { errorMessage } from '@/api/http'
import type { AdminUser, Pagination, Role } from '@/types/api'

const loading = ref(false)
const users = ref<AdminUser[]>([])
const pagination = ref<Pagination>({ page: 1, size: 20, total: 0, totalPages: 0, hasNext: false })
const query = reactive({ q: '', role: '', status: '' })
const createVisible = ref(false)
const createForm = reactive({ userNo: '', loginName: '', displayName: '', email: '', phone: '', role: 'STUDENT' as Role, password: '' })
const editVisible = ref(false)
const editing = ref<AdminUser | null>(null)
const editForm = reactive({ displayName: '', email: '', phone: '', role: 'STUDENT' as Role })

const roleLabel: Record<Role, string> = { STUDENT: '学生', TEACHER: '教师', ASSISTANT: '助教', ADMIN: '管理员' }
const statusLabel: Record<string, string> = { ACTIVE: '正常', LOCKED: '已锁定', DISABLED: '已停用', PENDING: '待激活' }

function format(value: string | null): string {
  if (!value) return '从未登录'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

async function load(page = 1): Promise<void> {
  loading.value = true
  try {
    const result = await adminApi.users({ page, size: 20, q: query.q || undefined, role: query.role || undefined, status: query.status || undefined })
    users.value = result.items
    pagination.value = result.pagination
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function createUser(): Promise<void> {
  if (!createForm.userNo.trim() || !createForm.displayName.trim() || createForm.password.length < 8) {
    ElMessage.warning('请填写学工号、姓名，并设置至少 8 位密码')
    return
  }
  try {
    await adminApi.createUser({ ...createForm, loginName: createForm.loginName || undefined, email: createForm.email || undefined, phone: createForm.phone || undefined })
    ElMessage.success('用户已创建')
    createVisible.value = false
    Object.assign(createForm, { userNo: '', loginName: '', displayName: '', email: '', phone: '', role: 'STUDENT', password: '' })
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function openEdit(user: AdminUser): void {
  editing.value = user
  editForm.displayName = user.displayName
  editForm.email = user.email ?? ''
  editForm.phone = user.phone ?? ''
  editForm.role = user.roles[0] ?? 'STUDENT'
  editVisible.value = true
}

async function saveEdit(): Promise<void> {
  if (!editing.value) return
  try {
    await adminApi.updateUser(editing.value.id, editForm)
    ElMessage.success('用户资料已更新')
    editVisible.value = false
    await load(pagination.value.page)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function action(user: AdminUser, type: 'deactivate' | 'activate' | 'unlock'): Promise<void> {
  const labels = { deactivate: '停用', activate: '启用', unlock: '解锁' }
  try {
    await ElMessageBox.confirm(`确定${labels[type]}用户“${user.displayName}”吗？`, `${labels[type]}用户`, { type: 'warning' })
    if (type === 'deactivate') await adminApi.deactivate(user.id)
    if (type === 'activate') await adminApi.activate(user.id)
    if (type === 'unlock') await adminApi.unlock(user.id)
    ElMessage.success(`已${labels[type]}`)
    await load(pagination.value.page)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

async function resetPassword(user: AdminUser): Promise<void> {
  try {
    const result = await ElMessageBox.prompt(`为“${user.displayName}”设置新密码`, '重置密码', {
      inputType: 'password', inputPlaceholder: '至少 8 位', inputValidator: (value) => value.length >= 8 || '密码至少 8 位',
    })
    await adminApi.resetPassword(user.id, result.value)
    ElMessage.success('密码已重置')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

onMounted(() => load())
</script>

<template>
  <div class="page">
    <div class="page-head"><div><p class="eyebrow">ADMINISTRATION</p><h1>用户管理</h1><p>维护账号、角色与访问状态。</p></div><el-button type="primary" @click="createVisible = true">＋ 新建用户</el-button></div>
    <section class="surface">
      <div class="filter-bar">
        <el-input v-model="query.q" placeholder="姓名 / 学工号 / 登录名" clearable style="max-width: 250px" @keyup.enter="load(1)" />
        <el-select v-model="query.role" placeholder="全部角色" clearable style="width: 125px"><el-option v-for="(label, role) in roleLabel" :key="role" :label="label" :value="role" /></el-select>
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 125px"><el-option v-for="(label, status) in statusLabel" :key="status" :label="label" :value="status" /></el-select>
        <el-button @click="load(1)">查询</el-button><span class="total">共 {{ pagination.total }} 个账号</span>
      </div>
      <el-table v-loading="loading" :data="users" style="width: 100%">
        <el-table-column prop="userNo" label="学工号" width="140" />
        <el-table-column prop="displayName" label="姓名" min-width="120" />
        <el-table-column prop="loginName" label="登录名" min-width="130" />
        <el-table-column label="角色" width="100"><template #default="{ row }"><el-tag size="small" effect="plain">{{ roleLabel[(row.roles?.[0] ?? 'STUDENT') as Role] }}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><span class="status" :class="row.status.toLowerCase()">{{ statusLabel[row.status] ?? row.status }}</span></template></el-table-column>
        <el-table-column label="最近登录" width="135"><template #default="{ row }">{{ format(row.lastLoginAt) }}</template></el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.status === 'DISABLED'" link type="primary" @click="action(row, 'activate')">启用</el-button>
            <el-button v-else link type="warning" @click="action(row, 'deactivate')">停用</el-button>
            <el-button v-if="row.status === 'LOCKED'" link type="warning" @click="action(row, 'unlock')">解锁</el-button>
            <el-button link @click="resetPassword(row)">重置密码</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="pagination.totalPages > 1" v-model:current-page="pagination.page" :page-size="pagination.size" :total="pagination.total" layout="prev, pager, next" class="pager" @current-change="load" />
    </section>

    <el-dialog v-model="createVisible" title="新建用户" width="min(570px, 94vw)">
      <el-form label-position="top" class="form-grid">
        <el-form-item label="学工号"><el-input v-model="createForm.userNo" /></el-form-item>
        <el-form-item label="登录名（可选）"><el-input v-model="createForm.loginName" placeholder="默认使用学工号" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="createForm.displayName" /></el-form-item>
        <el-form-item label="角色"><el-select v-model="createForm.role" style="width: 100%"><el-option v-for="(label, role) in roleLabel" :key="role" :label="label" :value="role" /></el-select></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="createForm.email" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="createForm.phone" /></el-form-item>
        <el-form-item label="初始密码" class="full"><el-input v-model="createForm.password" type="password" show-password placeholder="至少 8 位" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" @click="createUser">创建用户</el-button></template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑用户" width="min(520px, 94vw)">
      <el-form label-position="top" class="form-grid">
        <el-form-item label="姓名"><el-input v-model="editForm.displayName" /></el-form-item>
        <el-form-item label="角色"><el-select v-model="editForm.role" style="width: 100%"><el-option v-for="(label, role) in roleLabel" :key="role" :label="label" :value="role" /></el-select></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="editForm.email" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="editForm.phone" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="editVisible = false">取消</el-button><el-button type="primary" @click="saveEdit">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.eyebrow { color: #0f766e; font-size: 11px; font-weight: 800; letter-spacing: .14em; margin-bottom: 9px; }
.surface { overflow: hidden; }
.filter-bar { display: flex; gap: 9px; align-items: center; padding: 17px 19px; flex-wrap: wrap; border-bottom: 1px solid #edf1f2; }
.total { margin-left: auto; color: #909ba5; font-size: 10px; }
.status { font-size: 11px; }
.status.active { color: #15805f; }.status.locked { color: #b77710; }.status.disabled { color: #a1a9b2; text-decoration: line-through; }
.pager { justify-content: center; padding: 16px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.form-grid .full { grid-column: 1 / -1; }
@media (max-width: 600px) { .form-grid { grid-template-columns: 1fr; } .form-grid .full { grid-column: auto; } }
</style>
