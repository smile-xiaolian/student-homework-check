<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { errorMessage } from '@/api/http'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const form = reactive({ loginName: '', password: '' })
const rules: FormRules = {
  loginName: [{ required: true, message: '请输入学工号或登录名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const demos = [
  { label: '学生', account: '2023001001', password: 'Student@123' },
  { label: '教师', account: 'teacher01', password: 'Teacher@123' },
  { label: '管理员', account: 'admin', password: 'Admin@123' },
]

function fillDemo(item: typeof demos[number]): void {
  form.loginName = item.account
  form.password = item.password
}

async function submit(): Promise<void> {
  if (!(await formRef.value?.validate().catch(() => false))) return
  try {
    await auth.login(form.loginName, form.password)
    ElMessage.success(`欢迎回来，${auth.user?.displayName ?? ''}`)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="login-page">
    <section class="story-panel">
      <div class="story-inner">
        <div class="wordmark"><span>知</span>知行作业</div>
        <p class="eyebrow">TEACHING &amp; INTEGRITY WORKSPACE</p>
        <h1>让每一次提交，<br />都经得起回看。</h1>
        <p class="intro">课程、作业、提交与原创性检测集中在同一个清晰的工作空间。把时间留给教学，把复杂交给系统。</p>
        <div class="feature-row">
          <div><b>01</b><span>课程与作业一站管理</span></div>
          <div><b>02</b><span>文本与代码双模查重</span></div>
          <div><b>03</b><span>评审、成绩与审计留痕</span></div>
        </div>
      </div>
      <div class="orb orb-a" /><div class="orb orb-b" />
    </section>
    <section class="form-panel">
      <div class="login-card">
        <div class="card-head">
          <span class="mini-mark">知</span>
          <p>欢迎使用</p>
          <h2>登录工作空间</h2>
          <small>使用学校分配的账号或本地演示账号登录</small>
        </div>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item label="学工号 / 登录名" prop="loginName">
            <el-input v-model="form.loginName" size="large" placeholder="请输入学工号或登录名" autocomplete="username" @keyup.enter="submit" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" size="large" type="password" show-password placeholder="请输入密码" autocomplete="current-password" @keyup.enter="submit" />
          </el-form-item>
          <el-button class="login-btn" type="primary" size="large" :loading="auth.loading" @click="submit">进入工作台</el-button>
        </el-form>
        <div class="demo-box">
          <span>演示账号</span>
          <button v-for="item in demos" :key="item.label" type="button" @click="fillDemo(item)">
            {{ item.label }}<small>{{ item.account }}</small>
          </button>
        </div>
        <p class="privacy">登录即表示你同意遵守课程提交规范与学术诚信要求</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page { min-height: 100vh; display: grid; grid-template-columns: minmax(420px, 1.08fr) minmax(380px, .92fr); background: #f7faf9; }
.story-panel { position: relative; overflow: hidden; background: radial-gradient(circle at 75% 12%, #1b5d58, transparent 38%), linear-gradient(145deg, #0b2828 5%, #123f3c 52%, #0d3433 100%); color: white; padding: 52px clamp(34px, 6vw, 86px); display: flex; align-items: center; }
.story-inner { position: relative; z-index: 2; max-width: 620px; }
.wordmark { display: flex; align-items: center; gap: 11px; font-size: 18px; font-weight: 800; letter-spacing: .04em; margin-bottom: clamp(50px, 12vh, 130px); }
.wordmark span, .mini-mark { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 12px; background: #f4c95d; color: #20331a; font-weight: 900; }
.eyebrow { color: #7dd3c7; font-size: 11px; letter-spacing: .2em; font-weight: 700; margin-bottom: 22px; }
h1 { font-size: clamp(42px, 5vw, 72px); line-height: 1.08; letter-spacing: -.055em; margin-bottom: 25px; font-weight: 800; }
.intro { color: #b5d3ce; max-width: 560px; line-height: 1.9; font-size: 15px; margin-bottom: 52px; }
.feature-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 18px; border-top: 1px solid rgba(255,255,255,.13); padding-top: 21px; }
.feature-row div { display: flex; flex-direction: column; gap: 7px; }
.feature-row b { color: #f4c95d; font-size: 11px; letter-spacing: .1em; }
.feature-row span { color: #b5d3ce; font-size: 12px; line-height: 1.5; }
.orb { position: absolute; border-radius: 50%; border: 1px solid rgba(125,211,199,.16); }
.orb-a { width: 520px; height: 520px; right: -240px; top: -150px; }
.orb-b { width: 760px; height: 760px; right: -360px; bottom: -430px; }
.form-panel { display: grid; place-items: center; padding: 36px 24px; }
.login-card { width: min(410px, 100%); }
.card-head { margin-bottom: 30px; }
.card-head .mini-mark { margin-bottom: 25px; background: #d6f7ef; color: #0f766e; }
.card-head p { color: #0f766e; font-size: 12px; font-weight: 800; letter-spacing: .12em; margin-bottom: 8px; }
.card-head h2 { font-size: 32px; letter-spacing: -.04em; margin-bottom: 8px; }
.card-head small { color: #7b8798; font-size: 13px; }
.login-btn { width: 100%; height: 47px; margin-top: 4px; font-weight: 700; border-radius: 10px; background: #0f766e; border-color: #0f766e; }
.logo { display: none; }
.demo-box { border: 1px solid #e1e8e7; background: #fff; border-radius: 14px; padding: 14px; margin-top: 22px; display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.demo-box > span { grid-column: 1 / -1; color: #7b8798; font-size: 11px; margin: 0 2px 2px; }
.demo-box button { border: 0; border-radius: 9px; background: #f3f8f7; padding: 9px 5px; color: #264b48; cursor: pointer; font-size: 12px; font-weight: 700; }
.demo-box button:hover { background: #e2f5f0; }
.demo-box small { display: block; font-weight: 400; color: #84918f; margin-top: 3px; }
.privacy { color: #98a2af; text-align: center; font-size: 11px; line-height: 1.6; margin-top: 18px; }
@media (max-width: 850px) {
  .login-page { display: block; background: linear-gradient(160deg, #0b2828, #123f3c 42%, #f7faf9 42%); }
  .story-panel { min-height: 280px; padding: 28px 24px 44px; align-items: flex-start; }
  .wordmark { margin-bottom: 42px; }
  h1 { font-size: 38px; }
  .intro, .feature-row { display: none; }
  .form-panel { padding: 24px 20px 44px; align-items: start; }
  .login-card { background: white; padding: 28px 22px; border-radius: 20px; box-shadow: 0 20px 60px rgba(15,42,42,.12); }
}
</style>
