<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { courseApi } from '@/api/courses'
import { errorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { Course } from '@/types/api'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const courses = ref<Course[]>([])
const keyword = ref('')
const createVisible = ref(false)
const joinVisible = ref(false)
const createRef = ref<FormInstance>()
const createForm = reactive({ courseCode: '', name: '', semester: currentSemester(), description: '' })
const inviteCode = ref('')
const createRules: FormRules = {
  courseCode: [{ required: true, message: '请输入课程代码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入课程名称', trigger: 'blur' }],
  semester: [{ required: true, message: '请输入学期标识', trigger: 'blur' }],
}

function currentSemester(): string {
  const now = new Date()
  return `${now.getFullYear()}-${now.getMonth() >= 7 ? 'FALL' : 'SPRING'}`
}

const filtered = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return courses.value
  return courses.value.filter((course) =>
    course.name.toLowerCase().includes(value) || course.courseCode.toLowerCase().includes(value))
})

const canCreate = computed(() => auth.hasRole('TEACHER', 'ADMIN'))

async function load(): Promise<void> {
  loading.value = true
  try {
    courses.value = (await courseApi.list(1, 100)).items
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function createCourse(): Promise<void> {
  if (!(await createRef.value?.validate().catch(() => false))) return
  try {
    const course = await courseApi.create(createForm)
    ElMessage.success('课程已创建')
    createVisible.value = false
    Object.assign(createForm, { courseCode: '', name: '', semester: currentSemester(), description: '' })
    router.push(`/courses/${course.id}`)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function joinCourse(): Promise<void> {
  if (!inviteCode.value.trim()) {
    ElMessage.warning('请输入课程邀请码')
    return
  }
  try {
    const course = await courseApi.join(inviteCode.value.trim())
    ElMessage.success('已加入课程')
    joinVisible.value = false
    inviteCode.value = ''
    router.push(`/courses/${course.id}`)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div><p class="eyebrow">COURSE WORKSPACE</p><h1>课程空间</h1><p>查看课程、花名册与全部作业。</p></div>
      <div class="head-actions">
        <el-button v-if="!auth.isManager" @click="joinVisible = true">用邀请码加入</el-button>
        <el-button v-if="canCreate" type="primary" @click="createVisible = true">＋ 创建课程</el-button>
      </div>
    </div>

    <div class="toolbar panel-toolbar">
      <el-input v-model="keyword" placeholder="搜索课程名称或代码" clearable style="max-width: 320px" />
      <span class="count">{{ filtered.length }} 门课程</span>
    </div>

    <el-skeleton :loading="loading" animated :rows="8">
      <template #default>
        <div v-if="filtered.length" class="course-grid">
          <article v-for="(course, index) in filtered" :key="course.id" class="course-card" @click="router.push(`/courses/${course.id}`)">
            <div class="card-top">
              <span class="course-index">{{ (index + 1).toString().padStart(2, '0') }}</span>
              <el-tag size="small" :type="course.status === 'ACTIVE' ? 'success' : 'info'" effect="plain">
                {{ course.status === 'ACTIVE' ? '进行中' : course.status === 'ARCHIVED' ? '已归档' : '草稿' }}
              </el-tag>
            </div>
            <p class="code">{{ course.courseCode }}</p>
            <h2>{{ course.name }}</h2>
            <p class="desc">{{ course.description || '这门课程还没有填写简介。' }}</p>
            <div class="card-foot"><span>{{ course.semester }}</span><span>进入课程 →</span></div>
          </article>
        </div>
        <div v-else class="surface empty-state">
          <div class="empty-icon">▤</div>
          <h3>还没有可见课程</h3>
          <p>{{ auth.isManager ? '创建第一门课程，开始布置作业。' : '向老师获取邀请码后加入课程。' }}</p>
          <el-button v-if="canCreate" type="primary" @click="createVisible = true">创建课程</el-button>
          <el-button v-else-if="!auth.isManager" @click="joinVisible = true">加入课程</el-button>
        </div>
      </template>
    </el-skeleton>

    <el-dialog v-model="createVisible" title="创建课程" width="min(520px, 92vw)" destroy-on-close>
      <el-form ref="createRef" :model="createForm" :rules="createRules" label-position="top">
        <div class="form-row">
          <el-form-item label="课程代码" prop="courseCode"><el-input v-model="createForm.courseCode" placeholder="如 CS-2026-01" /></el-form-item>
          <el-form-item label="学期" prop="semester"><el-input v-model="createForm.semester" placeholder="2026-FALL" /></el-form-item>
        </div>
        <el-form-item label="课程名称" prop="name"><el-input v-model="createForm.name" placeholder="如 数据库系统原理" /></el-form-item>
        <el-form-item label="课程简介"><el-input v-model="createForm.description" type="textarea" :rows="4" placeholder="简要介绍课程内容与要求" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" @click="createCourse">创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="joinVisible" title="加入课程" width="min(430px, 92vw)">
      <p class="muted dialog-tip">输入老师提供的课程邀请码即可加入。</p>
      <el-input v-model="inviteCode" size="large" placeholder="课程邀请码" @keyup.enter="joinCourse" />
      <template #footer><el-button @click="joinVisible = false">取消</el-button><el-button type="primary" @click="joinCourse">加入</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.eyebrow { color: #0f766e; font-size: 11px; font-weight: 800; letter-spacing: .14em; margin-bottom: 9px; }
.head-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.panel-toolbar { justify-content: space-between; }
.count { color: #8a94a2; font-size: 12px; }
.course-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 17px; }
.course-card { position: relative; overflow: hidden; background: white; border: 1px solid #e2e9eb; border-radius: 18px; padding: 22px; min-height: 260px; cursor: pointer; transition: .18s ease; display: flex; flex-direction: column; }
.course-card::after { content: ''; position: absolute; width: 110px; height: 110px; border-radius: 50%; right: -54px; top: -55px; background: #dff7f1; opacity: .7; }
.course-card:hover { transform: translateY(-3px); box-shadow: 0 18px 35px rgba(15, 70, 65, .09); border-color: #b9ded7; }
.card-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 26px; position: relative; z-index: 1; }
.course-index { color: #a2ada9; font-size: 12px; font-weight: 700; letter-spacing: .12em; }
.code { color: #0f766e; font-size: 11px; font-weight: 800; letter-spacing: .08em; margin-bottom: 8px; }
.course-card h2 { font-size: 21px; line-height: 1.35; letter-spacing: -.03em; margin-bottom: 11px; }
.desc { color: #7a8796; font-size: 12px; line-height: 1.7; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.card-foot { margin-top: auto; padding-top: 22px; border-top: 1px solid #edf1f2; display: flex; justify-content: space-between; color: #8b96a3; font-size: 11px; }
.card-foot span:last-child { color: #155e57; font-weight: 700; }
.empty-state { text-align: center; padding: 60px 24px; }
.empty-icon { font-size: 44px; color: #b7c7c3; margin-bottom: 15px; }
.empty-state h3 { margin-bottom: 8px; }
.empty-state p { color: #7a8796; margin-bottom: 22px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.dialog-tip { font-size: 13px; margin-bottom: 16px; }
@media (max-width: 1100px) { .course-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 640px) { .course-grid { grid-template-columns: 1fr; } .form-row { grid-template-columns: 1fr; } }
</style>
