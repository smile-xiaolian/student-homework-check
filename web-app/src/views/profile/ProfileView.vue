<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/auth'
import { errorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const saving = ref(false)
const changing = ref(false)
const profile = reactive({
  displayName: auth.user?.displayName ?? '',
  email: auth.user?.email ?? '',
  phone: auth.user?.phone ?? '',
})
const password = reactive({ originalPassword: '', newPassword: '', confirmPassword: '' })

async function saveProfile(): Promise<void> {
  if (!profile.displayName.trim()) {
    ElMessage.warning('姓名不能为空')
    return
  }
  saving.value = true
  try {
    await authApi.updateProfile({
      displayName: profile.displayName.trim(),
      email: profile.email.trim() || null,
      phone: profile.phone.trim() || null,
    })
    await auth.refreshProfile()
    ElMessage.success('个人资料已更新')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

async function changePassword(): Promise<void> {
  if (password.newPassword.length < 8) {
    ElMessage.warning('新密码至少需要 8 位')
    return
  }
  if (password.newPassword !== password.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  changing.value = true
  try {
    await authApi.changePassword(password.originalPassword, password.newPassword)
    Object.assign(password, { originalPassword: '', newPassword: '', confirmPassword: '' })
    ElMessage.success('密码已修改')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    changing.value = false
  }
}
</script>

<template>
  <div class="page profile-page">
    <div class="page-head"><div><p class="eyebrow">ACCOUNT</p><h1>个人中心</h1><p>维护个人资料、登录密码与账号安全。</p></div></div>
    <div class="profile-layout">
      <aside class="surface profile-card">
        <div class="big-avatar">{{ auth.user?.displayName?.slice(0, 1) }}</div>
        <h2>{{ auth.user?.displayName }}</h2>
        <p>{{ auth.displayRole }} · {{ auth.user?.userNo }}</p>
        <div class="role-list"><span v-for="role in auth.user?.roles" :key="role">{{ role }}</span></div>
        <div class="identity-list">
          <div><span>学工号</span><b>{{ auth.user?.userNo }}</b></div>
          <div><span>登录名</span><b>{{ auth.user?.loginName }}</b></div>
          <div><span>账号状态</span><b class="active">{{ auth.user?.status === 'ACTIVE' ? '正常' : auth.user?.status }}</b></div>
        </div>
      </aside>
      <div class="forms">
        <section class="surface surface-pad">
          <h2>基本资料</h2><p class="section-note">姓名会展示在课程花名册与评审记录中。</p>
          <el-form label-position="top" class="form-grid">
            <el-form-item label="姓名"><el-input v-model="profile.displayName" /></el-form-item>
            <el-form-item label="邮箱"><el-input v-model="profile.email" placeholder="name@example.com" /></el-form-item>
            <el-form-item label="手机号"><el-input v-model="profile.phone" /></el-form-item>
            <el-form-item label="登录名"><el-input :model-value="auth.user?.loginName" disabled /></el-form-item>
          </el-form>
          <div class="form-actions"><el-button type="primary" :loading="saving" @click="saveProfile">保存资料</el-button></div>
        </section>
        <section class="surface surface-pad">
          <h2>修改密码</h2><p class="section-note">修改成功后，其他设备上的登录会话会失效。</p>
          <el-form label-position="top" class="form-grid">
            <el-form-item label="当前密码"><el-input v-model="password.originalPassword" type="password" show-password /></el-form-item>
            <span />
            <el-form-item label="新密码"><el-input v-model="password.newPassword" type="password" show-password placeholder="至少 8 位" /></el-form-item>
            <el-form-item label="确认新密码"><el-input v-model="password.confirmPassword" type="password" show-password /></el-form-item>
          </el-form>
          <div class="form-actions"><el-button :loading="changing" @click="changePassword">更新密码</el-button></div>
        </section>
        <section class="surface surface-pad permissions">
          <h2>权限摘要</h2><p class="section-note">权限由账号角色决定，如需调整请联系管理员。</p>
          <div class="permission-list"><span v-for="permission in auth.user?.permissions" :key="permission">{{ permission }}</span></div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.eyebrow { color: #0f766e; font-size: 11px; font-weight: 800; letter-spacing: .14em; margin-bottom: 9px; }
.profile-layout { display: grid; grid-template-columns: 275px minmax(0, 1fr); gap: 18px; align-items: start; }
.profile-card { padding: 30px 22px; text-align: center; }
.big-avatar { width: 78px; height: 78px; border-radius: 24px; background: #0f766e; color: #f4c95d; margin: 0 auto 17px; display: grid; place-items: center; font-size: 30px; font-weight: 900; box-shadow: 0 12px 25px rgba(15,118,110,.2); }
.profile-card h2 { font-size: 20px; margin-bottom: 6px; }
.profile-card > p { color: #8b96a1; font-size: 11px; margin-bottom: 15px; }
.role-list { display: flex; justify-content: center; gap: 6px; margin-bottom: 22px; }
.role-list span { background: #edf8f5; color: #0f766e; border-radius: 5px; padding: 4px 7px; font-size: 9px; }
.identity-list { border-top: 1px solid #edf1f2; text-align: left; }
.identity-list div { display: flex; justify-content: space-between; gap: 10px; padding: 11px 0; border-bottom: 1px solid #f0f3f4; font-size: 11px; }
.identity-list span { color: #909ba5; }
.identity-list b { color: #45535f; }
.identity-list b.active { color: #138466; }
.forms { display: flex; flex-direction: column; gap: 17px; }
.forms h2 { font-size: 18px; margin-bottom: 5px; }
.section-note { color: #8b96a1; font-size: 11px; margin-bottom: 20px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 18px; }
.form-actions { display: flex; justify-content: flex-end; padding-top: 4px; }
.permission-list { display: flex; flex-wrap: wrap; gap: 7px; }
.permission-list span { background: #f4f7f7; color: #68757f; border: 1px solid #e8eded; border-radius: 6px; padding: 5px 8px; font-size: 9px; font-family: Consolas, monospace; }
@media (max-width: 800px) { .profile-layout { grid-template-columns: 1fr; } .form-grid { grid-template-columns: 1fr; } .form-grid > span { display: none; } }
</style>
