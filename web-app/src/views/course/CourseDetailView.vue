<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { courseApi } from '@/api/courses'
import { assignmentApi } from '@/api/assignments'
import { errorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
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
const memberVisible = ref(false)
const assignmentRef = ref<FormInstance>()
const memberForm = reactive({ userNo: '', memberRole: 'STUDENT' })
const now = new Date()
const localDeadline = new Date(now.getTime() + 7 * 86_400_000 - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
const assignmentForm = reactive({
  title: '', description: '', deadlineAt: localDeadline, allowLate: false,
  lateDeadlineAt: '', weight: 0, checkEnabled: true, allowMultiVersion: true,
  effectiveRule: 'LAST_SUBMISSION',
})
const rubricRows = ref([
  { name: '内容完成度', maxScore: 50 },
  { name: '质量与规范', maxScore: 30 },
  { name: '思考与创新', maxScore: 20 },
])
const rubricTotal = computed(() => rubricRows.value.reduce((sum, row) => sum + (Number(row.maxScore) || 0), 0))
const fileTypeOptions = ['pdf', 'docx', 'doc', 'txt', 'md', 'java', 'py', 'c', 'cpp', 'h', 'js', 'ts', 'go', 'zip']
const restrictFileTypes = ref(false)
const selectedFileTypes = ref<string[]>([...fileTypeOptions])
const publishMode = ref<'manual' | 'scheduled'>('manual')
const scheduledPublishAt = ref('')
const assignmentRules: FormRules = {
  title: [{ required: true, message: '请输入作业标题', trigger: 'blur' }],
  deadlineAt: [{ required: true, message: '请选择截止时间', trigger: 'change' }],
}

function addRubricRow(): void {
  rubricRows.value.push({ name: '', maxScore: 10 })
}

function removeRubricRow(index: number): void {
  if (rubricRows.value.length > 1) rubricRows.value.splice(index, 1)
}

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

function toIso(value: string): string | undefined {
  return value ? new Date(value).toISOString() : undefined
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

async function createAssignment(): Promise<void> {
  if (!(await assignmentRef.value?.validate().catch(() => false))) return
  if (assignmentForm.allowLate) {
    if (!assignmentForm.lateDeadlineAt) {
      ElMessage.warning('允许补交时必须选择补交截止时间')
      return
    }
    if (new Date(assignmentForm.lateDeadlineAt) <= new Date(assignmentForm.deadlineAt)) {
      ElMessage.warning('补交截止时间必须晚于正常截止时间')
      return
    }
  }
  if (rubricRows.value.some((row) => !row.name.trim())) {
    ElMessage.warning('请为每个评分维度填写名称')
    return
  }
  if (rubricRows.value.some((row) => !(Number(row.maxScore) > 0) || Number(row.maxScore) > 100)) {
    ElMessage.warning('评分维度的满分必须为 1-100 之间的数字')
    return
  }
  if (rubricTotal.value <= 0) {
    ElMessage.warning('评分量规总分必须大于 0')
    return
  }
  if (restrictFileTypes.value && !selectedFileTypes.value.length) {
    ElMessage.warning('限制文件类型时至少选择一种类型')
    return
  }
  if (publishMode.value === 'scheduled') {
    if (!scheduledPublishAt.value) {
      ElMessage.warning('请选择定时发布时间')
      return
    }
    if (new Date(scheduledPublishAt.value) <= new Date()) {
      ElMessage.warning('定时发布时间必须晚于当前时间')
      return
    }
  }
  try {
    const payload = {
      title: assignmentForm.title,
      description: assignmentForm.description,
      deadlineAt: toIso(assignmentForm.deadlineAt),
      allowLate: assignmentForm.allowLate,
      lateDeadlineAt: assignmentForm.allowLate ? toIso(assignmentForm.lateDeadlineAt) : undefined,
      weight: Number(assignmentForm.weight),
      checkEnabled: assignmentForm.checkEnabled,
      allowMultiVersion: assignmentForm.allowMultiVersion,
      effectiveRule: assignmentForm.effectiveRule,
      checkConfig: assignmentForm.checkEnabled ? {
        mode: 'TEXT',
        text_thresholds: { warning: 20, severe: 50 },
        struct_thresholds: { warning: 30, severe: 60 },
        libraries: ['CLASS'],
        exclude: { references: true, template: true },
      } : undefined,
      rubricDefinition: {
        totalScore: rubricTotal.value,
        dimensions: rubricRows.value.map((row, index) => ({
          key: `dim${index + 1}`,
          name: row.name.trim(),
          maxScore: Number(row.maxScore),
        })),
      },
      allowedFileTypes: restrictFileTypes.value ? [...selectedFileTypes.value] : undefined,
      scheduledPublishAt: publishMode.value === 'scheduled' ? toIso(scheduledPublishAt.value) : undefined,
    }
    await assignmentApi.create(courseId, payload)
    ElMessage.success(publishMode.value === 'scheduled'
      ? '作业草稿已创建，到达设定时间后将自动发布'
      : '作业草稿已创建')
    assignmentVisible.value = false
    assignments.value = await courseApi.assignments(courseId)
  } catch (error) {
    ElMessage.error(errorMessage(error))
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
                  <el-button v-if="canInstruct && course.status === 'ACTIVE'" type="primary" @click="assignmentVisible = true">＋ 新建作业</el-button>
                </div>
                <div v-if="assignments.length" class="assignment-table">
                  <button v-for="item in assignments" :key="item.id" type="button" class="table-row" @click="router.push(`/assignments/${item.id}`)">
                    <span class="status-dot" :class="item.status.toLowerCase()" />
                    <span class="row-main"><strong>{{ item.title }}</strong><small>截止 {{ format(item.deadlineAt) }} · 权重 {{ item.weight }}%</small></span>
                    <span class="row-tags"><el-tag size="small" effect="plain" :type="item.status === 'PUBLISHED' ? 'success' : 'info'">{{ item.status === 'PUBLISHED' ? '已发布' : '草稿' }}</el-tag><el-tag v-if="item.status === 'DRAFT' && item.scheduledPublishAt" size="small" effect="plain" type="warning">定时 {{ format(item.scheduledPublishAt) }}</el-tag><span v-if="item.checkEnabled" class="check-tag">查重</span></span>
                    <el-button v-if="canInstruct && item.status === 'DRAFT'" size="small" plain @click.stop="publish(item)">发布</el-button>
                    <span class="arrow">→</span>
                  </button>
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

    <el-dialog v-model="assignmentVisible" title="新建作业" width="min(650px, 94vw)" destroy-on-close>
      <el-form ref="assignmentRef" :model="assignmentForm" :rules="assignmentRules" label-position="top">
        <el-form-item label="作业标题" prop="title"><el-input v-model="assignmentForm.title" placeholder="请输入作业标题" /></el-form-item>
        <el-form-item label="作业要求"><el-input v-model="assignmentForm.description" type="textarea" :rows="4" placeholder="说明任务内容、提交格式与评分要求" /></el-form-item>
        <div class="form-row">
          <el-form-item label="截止时间" prop="deadlineAt"><el-date-picker v-model="assignmentForm.deadlineAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" style="width: 100%" /></el-form-item>
          <el-form-item label="课程权重（%）"><el-input-number v-model="assignmentForm.weight" :min="0" :max="100" style="width: 100%" /></el-form-item>
        </div>
        <el-form-item label="提交策略">
          <div class="switch-grid">
            <el-checkbox v-model="assignmentForm.allowMultiVersion">允许多版本提交</el-checkbox>
            <el-checkbox v-model="assignmentForm.checkEnabled">启用自动查重</el-checkbox>
            <el-checkbox v-model="assignmentForm.allowLate">允许补交</el-checkbox>
          </div>
        </el-form-item>
        <el-form-item v-if="assignmentForm.allowLate" label="补交截止时间"><el-date-picker v-model="assignmentForm.lateDeadlineAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" style="width: 100%" /></el-form-item>
        <el-form-item label="评分量规">
          <div class="rubric-editor">
            <div v-for="(row, index) in rubricRows" :key="index" class="rubric-row">
              <el-input v-model="row.name" placeholder="评分维度名称，如 内容完成度" />
              <el-input-number v-model="row.maxScore" :min="1" :max="100" :precision="0" controls-position="right" />
              <el-button link type="danger" :disabled="rubricRows.length <= 1" @click="removeRubricRow(index)">删除</el-button>
            </div>
            <div class="rubric-foot">
              <el-button size="small" @click="addRubricRow">＋ 添加维度</el-button>
              <span>合计 <b>{{ rubricTotal }}</b> 分</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="提交文件类型">
          <div class="file-type-editor">
            <el-checkbox v-model="restrictFileTypes">限制本次作业可提交的文件类型</el-checkbox>
            <template v-if="restrictFileTypes">
              <el-checkbox-group v-model="selectedFileTypes" class="type-group">
                <el-checkbox v-for="type in fileTypeOptions" :key="type" :value="type">{{ type }}</el-checkbox>
              </el-checkbox-group>
              <p class="type-hint">仅允许学生上传勾选的类型（仍受系统全局白名单约束）。</p>
            </template>
            <p v-else class="type-hint">不限制时沿用系统配置的允许类型（PDF、Word、文本与常见源码文件）。</p>
          </div>
        </el-form-item>
        <el-form-item label="发布方式">
          <div class="publish-mode">
            <el-radio-group v-model="publishMode">
              <el-radio value="manual">保存草稿，稍后手动发布</el-radio>
              <el-radio value="scheduled">定时自动发布</el-radio>
            </el-radio-group>
            <el-date-picker
              v-if="publishMode === 'scheduled'"
              v-model="scheduledPublishAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              placeholder="选择发布时间"
              style="width: 100%"
            />
            <p v-if="publishMode === 'scheduled'" class="type-hint">到达设定时间后系统将自动发布并通知课程学生。</p>
          </div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="assignmentVisible = false">取消</el-button><el-button type="primary" @click="createAssignment">保存草稿</el-button></template>
    </el-dialog>

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
