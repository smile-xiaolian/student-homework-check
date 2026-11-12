<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { courseApi } from '@/api/courses'
import { assignmentApi } from '@/api/assignments'
import { errorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import AssignmentEditDialog from '@/components/AssignmentEditDialog.vue'
import type { Assignment, Course, CourseMember } from '@/types/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const courseId = String(route.params.id)
const loading = ref(true)
const course = ref<Course | null>(null)
const members = ref<CourseMember[]>([])
const assignments = ref<Assignment[]>([])
const activeTab = ref('assignments')
const assignmentVisible = ref(false)
const editAssignment = ref<Assignment | null>(null)
const memberVisible = ref(false)
const memberForm = reactive({ userNo: '', memberRole: 'STUDENT' })

const canManage = computed(() => Boolean(course.value && (auth.hasRole('ADMIN') || course.value.ownerId === auth.user?.id)))
const canInstruct = computed(() => canManage.value || members.value.some((item) =>
  item.userId === auth.user?.id && item.status === 'ACTIVE' && item.memberRole === 'INSTRUCTOR'))
const activeStudents = computed(() => members.value.filter((item) => item.memberRole === 'STUDENT' && item.status === 'ACTIVE'))
const publishedCount = computed(() => assignments.value.filter((item) => item.status === 'PUBLISHED').length)
const teacherNames = computed(() => members.value.filter((item) => item.memberRole !== 'STUDENT').map((item) => item.displayName).join('、'))

function format(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [courseData, memberData, assignmentData] = await Promise.all([
      courseApi.get(courseId), courseApi.members(courseId), courseApi.assignments(courseId),
    ])
    course.value = courseData
    members.value = memberData
    assignments.value = assignmentData
  } catch (error) {
    ElMessage.error(errorMessage(error))
    router.replace('/courses')
  } finally {
    loading.value = false
  }
}

async function copyInvite(): Promise<void> {
  if (!course.value?.inviteCode) return
  await navigator.clipboard.writeText(course.value.inviteCode)
  ElMessage.success('邀请码已复制')
}

async function rotateInvite(): Promise<void> {
  try {
    await ElMessageBox.confirm('轮换后旧邀请码立即失效，是否继续？', '轮换邀请码', { type: 'warning' })
    course.value = await courseApi.rotateInviteCode(courseId)
    ElMessage.success('邀请码已更新')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

async function archiveCourse(): Promise<void> {
  try {
    await ElMessageBox.confirm('归档后课程将变为只读，是否继续？', '归档课程', { type: 'warning' })
    course.value = await courseApi.archive(courseId)
    ElMessage.success('课程已归档')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

async function addMember(): Promise<void> {
  if (!memberForm.userNo.trim()) {
    ElMessage.warning('请输入学工号')
    return
  }
  try {
    await courseApi.addMember(courseId, memberForm)
    ElMessage.success('成员已添加')
    memberVisible.value = false
    memberForm.userNo = ''
    members.value = await courseApi.members(courseId)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function openCreateAssignment(): void {
  editAssignment.value = null
  assignmentVisible.value = true
}

function openEditAssignment(item: Assignment): void {
  editAssignment.value = item
  assignmentVisible.value = true
}

async function onAssignmentSaved(): Promise<void> {
  assignments.value = await courseApi.assignments(courseId)
}

async function removeAssignment(item: Assignment): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除作业「${item.title}」吗？删除后不可恢复。`,
      '删除作业', { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' },
    )
    await assignmentApi.remove(item.id)
    ElMessage.success('作业已删除')
    assignments.value = await courseApi.assignments(courseId)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

async function publish(item: Assignment): Promise<void> {
  try {
    const message = item.scheduledPublishAt
      ? `该作业已设定于 ${format(item.scheduledPublishAt)} 自动发布，立即发布将取消原定时。是否立即发布？`
      : '发布后将通知课程学生，是否继续？'
    await ElMessageBox.confirm(message, '发布作业', { type: 'info' })
    await assignmentApi.publish(item.id)
    ElMessage.success('作业已发布')
    assignments.value = await courseApi.assignments(courseId)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-skeleton :loading="loading" animated :rows="10">
      <template #default>
        <template v-if="course">
          <div class="breadcrumb"><button @click="router.push('/courses')">课程空间</button><span>/</span>{{ course.courseCode }}</div>
          <section class="course-hero">
            <div class="hero-main">
              <div class="tag-row"><el-tag effect="dark" type="success" size="small">{{ course.status === 'ACTIVE' ? '进行中' : '已归档' }}</el-tag><span>{{ course.semester }}</span></div>
              <h1>{{ course.name }}</h1>
              <p>{{ course.description || '暂无课程简介。' }}</p>
              <div class="hero-meta"><span>课程代码 {{ course.courseCode }}</span><span>授课团队 {{ teacherNames || '待添加' }}</span><span>{{ activeStudents.length }} 名学生</span></div>
            </div>
            <div v-if="canManage && course.inviteCode" class="invite-box">
              <small>课程邀请码</small><strong>{{ course.inviteCode }}</strong>
              <div><button @click="copyInvite">复制</button><button @click="rotateInvite">轮换</button></div>
            </div>
          </section>

          <div class="metric-row">
            <div><span>课程作业</span><b>{{ assignments.length }}</b></div>
            <div><span>已发布</span><b>{{ publishedCount }}</b></div>
            <div><span>学生人数</span><b>{{ activeStudents.length }}</b></div>
            <div><span>课程状态</span><b class="text-big">{{ course.status === 'ACTIVE' ? '可提交' : '只读' }}</b></div>
          </div>

          <section class="surface detail-panel">
            <el-tabs v-model="activeTab">
              <el-tab-pane label="作业" name="assignments">
                <div class="tab-toolbar">
                  <p>按发布时间与截止时间管理作业。</p>
                  <el-button v-if="canInstruct && course.status === 'ACTIVE'" type="primary" @click="openCreateAssignment">＋ 新建作业</el-button>
                </div>
                <div v-if="assignments.length" class="assignment-table">
                  <div v-for="item in assignments" :key="item.id" role="button" tabindex="0" class="table-row" @click="router.push(`/assignments/${item.id}`)">
                    <span class="status-dot" :class="item.status.toLowerCase()" />
                    <span class="row-main"><strong>{{ item.title }}</strong><small>截止 {{ format(item.deadlineAt) }} · 权重 {{ item.weight }}%</small></span>
                    <span class="row-tags"><el-tag size="small" effect="plain" :type="item.status === 'PUBLISHED' ? 'success' : 'info'">{{ item.status === 'PUBLISHED' ? '已发布' : '草稿' }}</el-tag><el-tag v-if="item.status === 'DRAFT' && item.scheduledPublishAt" size="small" effect="plain" type="warning">定时 {{ format(item.scheduledPublishAt) }}</el-tag><span v-if="item.checkEnabled" class="check-tag">查重</span></span>
                    <div v-if="canInstruct" class="row-actions" @click.stop>
                      <el-button v-if="item.status === 'DRAFT'" size="small" plain @click="publish(item)">发布</el-button>
                      <el-button size="small" plain @click="openEditAssignment(item)">编辑</el-button>
                      <el-button size="small" type="danger" plain @click="removeAssignment(item)">删除</el-button>
                    </div>
                    <span class="arrow">→</span>
                  </div>
                </div>
                <div v-else class="empty-line">还没有作业。{{ canInstruct ? '创建第一份作业吧。' : '' }}</div>
              </el-tab-pane>
              <el-tab-pane :label="`花名册 (${members.length})`" name="members">
                <div class="tab-toolbar"><p>课程教师、助教与学生名单。</p><el-button v-if="canInstruct" @click="memberVisible = true">＋ 添加成员</el-button></div>
                <el-table :data="members" style="width: 100%">
                  <el-table-column prop="userNo" label="学工号" width="150" />
                  <el-table-column prop="displayName" label="姓名" min-width="140" />
                  <el-table-column label="课程角色" width="130"><template #default="{ row }"><el-tag size="small" effect="plain" :type="row.memberRole === 'INSTRUCTOR' ? 'warning' : row.memberRole === 'ASSISTANT' ? 'success' : 'info'">{{ row.memberRole === 'INSTRUCTOR' ? '授课教师' : row.memberRole === 'ASSISTANT' ? '助教' : '学生' }}</el-tag></template></el-table-column>
                  <el-table-column label="加入时间" width="170"><template #default="{ row }">{{ format(row.joinedAt) }}</template></el-table-column>
                </el-table>
              </el-tab-pane>
              <el-tab-pane label="课程设置" name="settings">
                <div class="settings-list">
                  <div><b>课程状态</b><span>{{ course.status === 'ACTIVE' ? '课程进行中，可创建和提交作业。' : '课程已归档，仅可查看历史数据。' }}</span></div>
                  <div><b>创建时间</b><span>{{ format(course.createdAt) }}</span></div>
                  <div><b>最近更新</b><span>{{ format(course.updatedAt) }}</span></div>
                  <div v-if="canManage && course.status === 'ACTIVE'" class="danger-row"><span><b>归档课程</b><small>归档后不能再修改或提交，请谨慎操作。</small></span><el-button type="danger" plain @click="archiveCourse">归档课程</el-button></div>
                </div>
              </el-tab-pane>
            </el-tabs>
          </section>
        </template>
      </template>
    </el-skeleton>

    <AssignmentEditDialog v-model="assignmentVisible" :course-id="courseId" :assignment="editAssignment" @saved="onAssignmentSaved" />

    <el-dialog v-model="memberVisible" title="添加课程成员" width="min(440px, 92vw)">
      <el-form label-position="top">
        <el-form-item label="学工号"><el-input v-model="memberForm.userNo" placeholder="输入已注册用户的学工号" /></el-form-item>
        <el-form-item label="课程角色"><el-radio-group v-model="memberForm.memberRole"><el-radio value="STUDENT">学生</el-radio><el-radio value="ASSISTANT">助教</el-radio></el-radio-group></el-form-item>
      </el-form>
      <template #footer><el-button @click="memberVisible = false">取消</el-button><el-button type="primary" @click="addMember">添加</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.breadcrumb { display: flex; gap: 9px; align-items: center; color: #93a0ac; font-size: 12px; margin-bottom: 16px; }
.breadcrumb button { border: 0; background: transparent; color: #0f766e; cursor: pointer; padding: 0; font-size: 12px; }
.course-hero { position: relative; overflow: hidden; background: linear-gradient(130deg, #123d3a, #0f5f58 70%, #17766c); border-radius: 22px; color: white; padding: 32px; display: flex; justify-content: space-between; gap: 30px; margin-bottom: 17px; }
.course-hero::after { content: '知'; position: absolute; right: 3%; top: -60px; color: rgba(255,255,255,.045); font-size: 300px; font-weight: 900; line-height: 1; pointer-events: none; }
.hero-main { position: relative; z-index: 1; max-width: 700px; }
.tag-row { display: flex; gap: 10px; align-items: center; color: #a8d8d2; font-size: 11px; margin-bottom: 17px; }
.hero-main h1 { font-size: clamp(29px, 4vw, 42px); letter-spacing: -.04em; margin-bottom: 13px; }
.hero-main p { color: #b9d8d4; line-height: 1.8; max-width: 650px; margin-bottom: 22px; font-size: 13px; }
.hero-meta { display: flex; gap: 20px; flex-wrap: wrap; color: #91c3bd; font-size: 11px; }
.invite-box { position: relative; z-index: 1; background: rgba(255,255,255,.1); border: 1px solid rgba(255,255,255,.14); border-radius: 15px; padding: 16px; min-width: 170px; align-self: center; backdrop-filter: blur(8px); }
.invite-box small { display: block; color: #a8d8d2; font-size: 10px; margin-bottom: 8px; }
.invite-box strong { display: block; color: #f4c95d; font-size: 24px; letter-spacing: .12em; font-family: Consolas, monospace; }
.invite-box div { display: flex; gap: 7px; margin-top: 12px; }
.invite-box button { border: 1px solid rgba(255,255,255,.18); border-radius: 7px; padding: 4px 9px; background: transparent; color: white; font-size: 10px; cursor: pointer; }
.metric-row { display: grid; grid-template-columns: repeat(4, 1fr); background: white; border: 1px solid #e2e9eb; border-radius: 15px; padding: 15px 0; margin-bottom: 17px; }
.metric-row > div { padding: 2px 22px; border-left: 1px solid #edf1f2; }
.metric-row > div:first-child { border-left: 0; }
.metric-row span, .metric-row b { display: block; }
.metric-row span { color: #84909d; font-size: 11px; margin-bottom: 5px; }
.metric-row b { font-size: 25px; letter-spacing: -.04em; }
.metric-row b.text-big { font-size: 18px; margin-top: 5px; }
.detail-panel { padding: 10px 24px 24px; }
.tab-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 7px 0 14px; }
.tab-toolbar p { margin: 0; color: #7d8997; font-size: 12px; }
.assignment-table { border-top: 1px solid #edf1f2; }
.table-row { width: 100%; display: flex; align-items: center; gap: 12px; border: 0; border-bottom: 1px solid #edf1f2; background: transparent; text-align: left; padding: 15px 4px; cursor: pointer; }
.table-row:hover { background: #fbfdfc; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: #aab4be; flex: 0 0 8px; }
.status-dot.published { background: #25a582; box-shadow: 0 0 0 3px #dcf5ed; }
.status-dot.draft { background: #d1a434; }
.row-main { flex: 1; min-width: 0; }
.row-main strong, .row-main small { display: block; }
.row-main strong { font-size: 13px; margin-bottom: 5px; }
.row-main small { color: #8b96a3; font-size: 11px; }
.row-tags { display: flex; gap: 6px; align-items: center; }
.row-actions { display: flex; gap: 6px; align-items: center; flex-shrink: 0; }
.check-tag { color: #a2660a; background: #fff4d9; padding: 4px 6px; border-radius: 5px; font-size: 10px; }
.arrow { color: #97aaa6; font-size: 16px; margin-left: 4px; }
.settings-list > div { display: flex; justify-content: space-between; gap: 20px; padding: 16px 3px; border-bottom: 1px solid #edf1f2; font-size: 13px; }
.settings-list span { color: #7e8a98; text-align: right; }
.settings-list span b, .settings-list span small { display: block; }
.settings-list small { color: #9ba5af; margin-top: 6px; }
.danger-row { margin-top: 16px; border: 1px solid #f3d7d2 !important; background: #fffafa; border-radius: 12px; padding: 15px !important; align-items: center; }
.form-row { display: grid; grid-template-columns: 1.2fr .8fr; gap: 14px; }
.switch-grid { display: flex; gap: 20px; flex-wrap: wrap; }
.rubric-editor { width: 100%; display: grid; gap: 8px; }
.rubric-row { display: grid; grid-template-columns: 1fr 130px auto; gap: 8px; align-items: center; }
.rubric-foot { display: flex; justify-content: space-between; align-items: center; }
.rubric-foot span { color: #74818e; font-size: 12px; }
.rubric-foot b { color: #0f766e; font-size: 14px; }
.file-type-editor { width: 100%; }
.type-group { display: flex; flex-wrap: wrap; gap: 2px 14px; margin-top: 8px; }
.type-hint { margin: 8px 0 0; color: #8c97a3; font-size: 11px; line-height: 1.6; }
.publish-mode { width: 100%; display: grid; gap: 10px; }
@media (max-width: 760px) { .course-hero { flex-direction: column; padding: 24px; } .invite-box { align-self: stretch; } .metric-row { grid-template-columns: 1fr 1fr; } .metric-row > div { padding: 12px 17px; } .metric-row > div:nth-child(3) { border-left: 0; border-top: 1px solid #edf1f2; } .metric-row > div:nth-child(4) { border-top: 1px solid #edf1f2; } .form-row { grid-template-columns: 1fr; } .row-tags { display: none; } }
</style>
