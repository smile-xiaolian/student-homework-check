<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { assignmentApi } from '@/api/assignments'
import { courseApi } from '@/api/courses'
import { submissionApi } from '@/api/submissions'
import { reviewApi } from '@/api/reviews'
import { fileApi } from '@/api/files'
import { errorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { Assignment, Course, CourseMember, Review, Submission, SubmissionFile } from '@/types/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const assignmentId = String(route.params.id)
const loading = ref(true)
const submitting = ref(false)
const assignment = ref<Assignment | null>(null)
const course = ref<Course | null>(null)
const members = ref<CourseMember[]>([])
const submissions = ref<Submission[]>([])
const onlineText = ref('')
const uploadedFiles = ref<SubmissionFile[]>([])
const uploadInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const uploadPercent = ref(0)
const consent = ref(false)
const selected = ref<Submission | null>(null)
const reviewVisible = ref(false)
const attachmentVisible = ref(false)
const attachmentTitle = ref('')
const attachmentItems = ref<SubmissionFile[]>([])
const savingReview = ref(false)
const review = ref<Review | null>(null)
const reviewForm = reactive({ rawScore: 0, checkVerdict: 'ORIGINAL', comment: '' })
const showAllVersions = ref(false)

const canManage = computed(() => Boolean(course.value && (auth.hasRole('ADMIN') || course.value.ownerId === auth.user?.id)))
const canInstruct = computed(() => canManage.value || members.value.some((item) =>
  item.userId === auth.user?.id && item.status === 'ACTIVE' && item.memberRole === 'INSTRUCTOR'))
const canReview = computed(() => auth.canReview && Boolean(course.value))
const isStudent = computed(() => auth.hasRole('STUDENT'))
const effectiveSubmission = computed(() => submissions.value.find((item) => item.effective) ?? null)
const latestSubmission = computed(() => submissions.value[0] ?? null)
const deadlinePassed = computed(() => Boolean(assignment.value && Date.now() > new Date(assignment.value.deadlineAt).getTime()))
const lateWindowOpen = computed(() => Boolean(assignment.value?.allowLate && assignment.value.lateDeadlineAt
  && Date.now() <= new Date(assignment.value.lateDeadlineAt).getTime()))
const canSubmit = computed(() => Boolean(assignment.value?.status === 'PUBLISHED' && course.value?.status === 'ACTIVE'
  && (!deadlinePassed.value || lateWindowOpen.value)))
const requiresFreshConsent = computed(() => !latestSubmission.value?.integrityConsent)
const globalFileTypes = ['pdf', 'docx', 'doc', 'txt', 'md', 'java', 'py', 'c', 'cpp', 'h', 'js', 'ts', 'go']
const allowedFileTypes = computed(() => {
  const restricted = assignment.value?.allowedFileTypes ?? []
  return restricted.length ? restricted.filter((type) => globalFileTypes.includes(type)) : globalFileTypes
})
const fileAccept = computed(() => allowedFileTypes.value.map((type) => `.${type}`).join(','))

const reviewStats = computed(() => ({
  total: submissions.value.length,
  late: submissions.value.filter((item) => item.late).length,
  completed: submissions.value.filter((item) => ['CHECK_COMPLETED', 'REVIEWED', 'PUBLISHED'].includes(item.status)).length,
  failed: submissions.value.filter((item) => item.status === 'CHECK_FAILED').length,
}))

function format(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function statusText(status: string): string {
  return ({
    PENDING_SCAN: '待检查', SUBMITTED: '已提交', CHECKING: '查重中', CHECK_COMPLETED: '查重完成',
    CHECK_FAILED: '查重失败', REVIEWED: '已评审', PUBLISHED: '成绩已发布', CLOSED: '已关闭', WITHDRAWN: '已撤回',
  } as Record<string, string>)[status] ?? status
}

function statusType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (['CHECK_COMPLETED', 'REVIEWED', 'PUBLISHED'].includes(status)) return 'success'
  if (['CHECKING', 'PENDING_SCAN', 'SUBMITTED'].includes(status)) return 'warning'
  if (status === 'CHECK_FAILED') return 'danger'
  return 'info'
}

function canOpenReport(item: Submission): boolean {
  return ['CHECK_COMPLETED', 'REVIEWED', 'PUBLISHED'].includes(item.status)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    assignment.value = await assignmentApi.get(assignmentId)
    const [courseData, memberData] = await Promise.all([
      courseApi.get(assignment.value.courseId),
      courseApi.members(assignment.value.courseId),
    ])
    course.value = courseData
    members.value = memberData
    await loadSubmissions()
  } catch (error) {
    ElMessage.error(errorMessage(error))
    router.replace('/courses')
  } finally {
    loading.value = false
  }
}

async function loadSubmissions(): Promise<void> {
  if (isStudent.value) submissions.value = await submissionApi.mine(assignmentId)
  else if (canReview.value) submissions.value = await submissionApi.list(assignmentId, showAllVersions.value)
  else submissions.value = []
}

function chooseFiles(): void {
  uploadInput.value?.click()
}

async function onFilesSelected(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return
  if (uploadedFiles.value.length + files.length > 10) {
    ElMessage.warning('单次提交最多包含 10 个附件')
    return
  }
  const allowed = new Set(allowedFileTypes.value)
  uploading.value = true
  try {
    for (const file of files) {
      const extension = file.name.includes('.') ? file.name.split('.').pop()?.toLowerCase() ?? '' : ''
      if (!allowed.has(extension)) {
        ElMessage.error(`${file.name} 的文件类型不受支持`)
        continue
      }
      if (file.size > 100 * 1024 * 1024) {
        ElMessage.error(`${file.name} 超过 100MB 限制`)
        continue
      }
      try {
        uploadPercent.value = 0
        const uploaded = await fileApi.upload(assignmentId, file, (percent) => { uploadPercent.value = percent })
        uploadedFiles.value.push(uploaded)
      } catch (error) {
        ElMessage.error(`${file.name} 上传失败：${errorMessage(error)}`)
      }
    }
  } finally {
    uploading.value = false
    uploadPercent.value = 0
  }
}

function removeUploadedFile(index: number): void {
  uploadedFiles.value.splice(index, 1)
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function downloadFile(file: SubmissionFile): Promise<void> {
  try {
    await fileApi.download(file)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function openAttachments(item: Submission): void {
  attachmentTitle.value = `${item.submitterName} · V${item.versionNo} 的附件`
  attachmentItems.value = item.files ?? []
  attachmentVisible.value = true
}

async function submit(): Promise<void> {
  const text = onlineText.value.trim() ? onlineText.value : null
  if (!text && !uploadedFiles.value.length) {
    ElMessage.warning('请填写在线文本或上传至少一个文件')
    return
  }
  if (uploading.value) {
    ElMessage.warning('请等待附件上传完成')
    return
  }
  if (requiresFreshConsent.value && !consent.value) {
    ElMessage.warning('请先阅读并确认学术诚信声明')
    return
  }
  submitting.value = true
  try {
    const key = crypto.randomUUID()
    await submissionApi.submit(assignmentId, text, consent.value || !requiresFreshConsent.value, key,
      uploadedFiles.value.map((file) => file.id))
    ElMessage.success(assignment.value?.checkEnabled ? '提交成功，查重任务已进入队列' : '提交成功')
    onlineText.value = ''
    uploadedFiles.value = []
    await loadSubmissions()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    submitting.value = false
  }
}

async function setEffective(item: Submission): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定将第 ${item.versionNo} 版设为生效版本吗？`, '切换生效版本', { type: 'info' })
    await submissionApi.setEffective(item.id)
    ElMessage.success('生效版本已切换')
    await loadSubmissions()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

async function openReview(item: Submission): Promise<void> {
  selected.value = item
  reviewVisible.value = true
  review.value = null
  try {
    review.value = await reviewApi.get(item.id)
    reviewForm.rawScore = review.value.rawScore ?? 0
    reviewForm.checkVerdict = review.value.checkVerdict ?? 'ORIGINAL'
    reviewForm.comment = review.value.comment ?? ''
  } catch {
    reviewForm.rawScore = 0
    reviewForm.checkVerdict = 'ORIGINAL'
    reviewForm.comment = ''
  }
}

async function saveReview(submitAfter = false): Promise<void> {
  if (!selected.value) return
  savingReview.value = true
  try {
    await reviewApi.save(selected.value.id, {
      rawScore: Number(reviewForm.rawScore), dimensionScores: [], annotations: [],
      checkVerdict: reviewForm.checkVerdict, comment: reviewForm.comment,
    })
    if (submitAfter) {
      await reviewApi.submit(selected.value.id)
      ElMessage.success('评审已提交')
      reviewVisible.value = false
      await loadSubmissions()
    } else {
      ElMessage.success('评审草稿已保存')
    }
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    savingReview.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-skeleton :loading="loading" animated :rows="12">
      <template #default>
        <template v-if="assignment && course">
          <div class="breadcrumb"><button @click="router.push('/courses')">课程空间</button><span>/</span><button @click="router.push(`/courses/${course.id}`)">{{ course.name }}</button><span>/</span>作业详情</div>
          <div class="page-head assignment-head">
            <div>
              <div class="title-tags"><el-tag size="small" effect="plain" :type="assignment.status === 'PUBLISHED' ? 'success' : 'info'">{{ assignment.status === 'PUBLISHED' ? '进行中' : '草稿' }}</el-tag><span v-if="assignment.checkEnabled" class="check-tag">自动查重</span><span class="weight">权重 {{ assignment.weight }}%</span></div>
              <h1>{{ assignment.title }}</h1>
              <p>{{ course.courseCode }} · {{ course.name }}</p>
            </div>
            <div class="deadline-card" :class="{ urgent: deadlinePassed && !lateWindowOpen }">
              <small>截止时间</small><strong>{{ format(assignment.deadlineAt) }}</strong>
              <span v-if="assignment.allowLate">补交至 {{ format(assignment.lateDeadlineAt) }}</span>
            </div>
          </div>

          <div class="split-grid assignment-layout">
            <div>
              <section class="surface surface-pad requirements">
                <h2>作业要求</h2>
                <p class="description">{{ assignment.description || '教师尚未填写作业说明。' }}</p>
                <div class="rule-grid">
                  <div><span>提交方式</span><b>文件或在线文本</b></div>
                  <div><span>生效规则</span><b>{{ assignment.effectiveRule === 'MANUAL' ? '学生手动指定' : '最后一次提交' }}</b></div>
                  <div><span>多版本</span><b>{{ assignment.allowMultiVersion ? '允许' : '不允许' }}</b></div>
                  <div><span>迟交</span><b>{{ assignment.allowLate ? '允许，按规则扣分' : '不允许' }}</b></div>
                </div>
              </section>

              <section v-if="isStudent" class="surface surface-pad submit-panel">
                <div class="section-title"><div><h2>提交作业</h2><p>每次成功提交都会生成新的版本记录。</p></div><span v-if="effectiveSubmission" class="effective-tag">当前生效 V{{ effectiveSubmission.versionNo }}</span></div>
                <template v-if="canSubmit">
                  <el-input v-model="onlineText" type="textarea" :rows="10" maxlength="1000000" show-word-limit placeholder="在此粘贴或输入作业正文……" />
                  <div class="file-uploader">
                    <input ref="uploadInput" class="hidden-input" type="file" multiple :accept="fileAccept" @change="onFilesSelected" />
                    <div class="file-upload-head">
                      <el-button size="small" :loading="uploading" @click="chooseFiles">选择附件</el-button>
                      <span>{{ assignment.allowedFileTypes?.length ? `本次作业仅允许上传：${allowedFileTypes.join('、')}，` : '支持 PDF、Word、文本与常见源码文件，' }}单个不超过 100MB，最多 10 个。</span>
                    </div>
                    <el-progress v-if="uploading" :percentage="uploadPercent" :stroke-width="6" />
                    <div v-if="uploadedFiles.length" class="uploaded-list">
                      <div v-for="(file, index) in uploadedFiles" :key="file.id" class="uploaded-item">
                        <div><strong>{{ file.originalName }}</strong><small>{{ formatBytes(file.sizeBytes) }} · 已就绪</small></div>
                        <el-button link type="danger" @click="removeUploadedFile(index)">移除</el-button>
                      </div>
                    </div>
                  </div>
                  <el-checkbox v-if="requiresFreshConsent" v-model="consent" class="consent">我确认本作业为独立完成，引用内容已规范标注，并知悉学校学术诚信规定。</el-checkbox>
                  <div class="submit-actions"><span>{{ deadlinePassed ? '当前处于补交期，提交将标记为迟交。' : '截止前可多次提交，保留全部历史版本。' }}</span><el-button type="primary" :loading="submitting || uploading" @click="submit">提交并查重</el-button></div>
                </template>
                <el-alert v-else title="当前作业不可提交" type="warning" :closable="false" description="作业尚未发布、已关闭，或已超过允许的提交时间。" />
              </section>

              <section class="surface surface-pad history-panel">
                <div class="section-title">
                  <div><h2>{{ isStudent ? '我的提交历史' : '学生提交' }}</h2><p>{{ isStudent ? '查重完成后可查看报告并等待评审。' : (showAllVersions ? '按提交时间展示全部学生与全部版本。' : '每位学生仅展示最新生效版本；如需切换生效版本，请打开“全部版本”。') }}</p></div>
                  <div v-if="!isStudent" class="section-actions">
                    <label class="all-versions-toggle"><el-switch v-model="showAllVersions" size="small" @change="loadSubmissions" /><span>全部版本</span></label>
                    <el-button size="small" @click="loadSubmissions">刷新</el-button>
                  </div>
                </div>
                <div v-if="!isStudent" class="review-metrics">
                  <span><b>{{ reviewStats.total }}</b>{{ showAllVersions ? '总提交' : '已交学生' }}</span><span><b>{{ reviewStats.late }}</b>迟交</span><span><b>{{ reviewStats.completed }}</b>可评审</span><span><b>{{ reviewStats.failed }}</b>查重失败</span>
                </div>
                <el-table v-if="submissions.length" :data="submissions" style="width: 100%">
                  <el-table-column v-if="!isStudent" prop="submitterName" label="学生" min-width="105" />
                  <el-table-column label="版本" width="72"><template #default="{ row }"><span class="version">V{{ row.versionNo }}</span></template></el-table-column>
                  <el-table-column label="状态" width="112"><template #default="{ row }"><el-tag size="small" effect="plain" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag></template></el-table-column>
                  <el-table-column label="提交时间" min-width="150"><template #default="{ row }"><span class="date-cell">{{ format(row.submittedAt) }}<small v-if="row.late">迟交</small></span></template></el-table-column>
                  <el-table-column label="生效" width="70"><template #default="{ row }"><el-tag v-if="row.effective" size="small" type="success" effect="plain">生效</el-tag><span v-else class="muted">—</span></template></el-table-column>
                  <el-table-column label="操作" width="270" fixed="right">
                    <template #default="{ row }">
                      <el-button v-if="row.files?.length" link type="primary" @click="openAttachments(row)">附件 {{ row.files.length }}</el-button>
                      <el-button v-if="canOpenReport(row)" link type="primary" @click="router.push(`/submissions/${row.id}/report`)">查重报告</el-button>
                      <el-button v-if="canReview && canOpenReport(row)" link type="primary" @click="openReview(row)">评审</el-button>
                      <el-button v-if="canInstruct && assignment.effectiveRule === 'MANUAL' && !row.effective" link @click="setEffective(row)">设为生效</el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <div v-else class="empty-line">暂无提交记录</div>
              </section>
            </div>

            <aside>
              <section class="surface surface-pad side-card">
                <h3>评分量规</h3>
                <div v-if="assignment.rubricDefinition?.dimensions?.length" class="rubric-list">
                  <div v-for="item in assignment.rubricDefinition.dimensions" :key="item.key"><span>{{ item.name }}</span><b>{{ item.maxScore }} 分</b><small v-if="item.description">{{ item.description }}</small></div>
                  <div class="rubric-total"><span>合计</span><b>{{ assignment.rubricDefinition.totalScore ?? assignment.rubricDefinition.dimensions.reduce((sum, item) => sum + item.maxScore, 0) }} 分</b></div>
                </div>
                <p v-else class="muted small">尚未配置评分量规。</p>
              </section>
              <section class="surface surface-pad side-card">
                <h3>提交提示</h3>
                <ul class="tips">
                  <li>查重结果仅作为原创性辅助提示，最终由教师人工确认。</li>
                  <li>查重失败不会影响提交有效性，系统会自动重试。</li>
                  <li>请勿直接复制他人的文字、代码或未标注引用内容。</li>
                </ul>
              </section>
            </aside>
          </div>
        </template>
      </template>
    </el-skeleton>

    <el-drawer v-model="reviewVisible" title="作业评审" size="min(520px, 94vw)" destroy-on-close>
      <template v-if="selected">
        <div class="review-student"><span>{{ selected.submitterName }}</span><small>第 {{ selected.versionNo }} 版 · {{ format(selected.submittedAt) }}</small></div>
        <div class="review-content">{{ selected.onlineText || '该提交没有在线文本。' }}</div>
        <div v-if="selected.files?.length" class="review-files">
          <span>提交附件</span>
          <el-button v-for="file in selected.files" :key="file.id" link type="primary" @click="downloadFile(file)">
            {{ file.originalName }}（{{ formatBytes(file.sizeBytes) }}）
          </el-button>
        </div>
        <el-form label-position="top" class="review-form">
          <el-form-item label="原始得分"><el-input-number v-model="reviewForm.rawScore" :min="0" :max="100" :precision="1" style="width: 100%" /></el-form-item>
          <el-form-item label="查重结论"><el-select v-model="reviewForm.checkVerdict" style="width: 100%"><el-option label="原创" value="ORIGINAL" /><el-option label="疑似抄袭" value="SUSPECTED_PLAGIARISM" /><el-option label="需要面谈" value="INTERVIEW_REQUIRED" /></el-select></el-form-item>
          <el-form-item label="评语"><el-input v-model="reviewForm.comment" type="textarea" :rows="4" placeholder="填写反馈与改进建议" /></el-form-item>
        </el-form>
        <div class="drawer-actions"><el-button :loading="savingReview" @click="saveReview(false)">保存草稿</el-button><el-button type="primary" :loading="savingReview" @click="saveReview(true)">提交评审</el-button></div>
      </template>
    </el-drawer>

    <el-drawer v-model="attachmentVisible" :title="attachmentTitle" size="min(480px, 92vw)">
      <div v-if="attachmentItems.length" class="attachment-drawer">
        <div v-for="file in attachmentItems" :key="file.id" class="attachment-row">
          <div><strong>{{ file.originalName }}</strong><small>{{ file.mimeType }} · {{ formatBytes(file.sizeBytes) }}</small></div>
          <el-button link type="primary" @click="downloadFile(file)">下载</el-button>
        </div>
      </div>
      <el-empty v-else description="该版本没有附件" />
    </el-drawer>
  </div>
</template>

<style scoped>
.breadcrumb { display: flex; gap: 9px; align-items: center; color: #93a0ac; font-size: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.breadcrumb button { border: 0; background: transparent; color: #0f766e; cursor: pointer; padding: 0; font-size: 12px; }
.assignment-head h1 { font-size: clamp(28px, 4vw, 42px); }
.title-tags { display: flex; gap: 9px; align-items: center; margin-bottom: 13px; }
.check-tag { background: #fff2d3; color: #9b660a; padding: 4px 7px; border-radius: 5px; font-size: 10px; }
.weight { color: #7f8b98; font-size: 11px; }
.deadline-card { min-width: 210px; background: #fff9ea; border: 1px solid #f0e2b8; border-radius: 14px; padding: 14px 17px; }
.deadline-card small, .deadline-card strong, .deadline-card span { display: block; }
.deadline-card small { color: #9d7d32; font-size: 10px; margin-bottom: 5px; }
.deadline-card strong { color: #604a17; font-size: 13px; }
.deadline-card span { color: #a18a54; font-size: 10px; margin-top: 5px; }
.deadline-card.urgent { background: #fff0ef; border-color: #f3d3ce; }
.deadline-card.urgent strong { color: #9f3127; }
.assignment-layout { align-items: start; }
.requirements { margin-bottom: 17px; }
.requirements h2, .submit-panel h2, .history-panel h2 { font-size: 18px; margin-bottom: 14px; }
.description { color: #536171; white-space: pre-wrap; line-height: 1.9; font-size: 14px; }
.rule-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1px; background: #edf1f2; border: 1px solid #edf1f2; border-radius: 10px; overflow: hidden; margin-top: 22px; }
.rule-grid div { background: #fbfcfc; padding: 12px 14px; }
.rule-grid span, .rule-grid b { display: block; }
.rule-grid span { color: #8a95a1; font-size: 10px; margin-bottom: 5px; }
.rule-grid b { font-size: 12px; }
.submit-panel { margin-bottom: 17px; }
.section-title { display: flex; justify-content: space-between; gap: 12px; align-items: start; margin-bottom: 14px; }
.section-title h2 { margin-bottom: 4px; }
.section-title p { color: #8c97a3; font-size: 11px; margin: 0; }
.section-actions { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.all-versions-toggle { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-size: 11px; color: #74818e; white-space: nowrap; }
.effective-tag { background: #e3f7f1; color: #17766c; border-radius: 7px; padding: 6px 9px; font-size: 10px; white-space: nowrap; }
.hidden-input { display: none; }
.file-uploader { margin-top: 12px; padding: 13px; border: 1px dashed #cfdadb; border-radius: 10px; background: #fbfdfd; }
.file-upload-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.file-upload-head span { color: #85919e; font-size: 11px; line-height: 1.5; }
.uploaded-list { display: grid; gap: 7px; margin-top: 10px; }
.uploaded-item { display: flex; justify-content: space-between; align-items: center; gap: 12px; background: #fff; border: 1px solid #e7eded; border-radius: 8px; padding: 9px 11px; }
.uploaded-item strong, .uploaded-item small { display: block; }
.uploaded-item strong { font-size: 12px; color: #354652; }
.uploaded-item small { margin-top: 3px; color: #93a0ac; font-size: 10px; }
.consent { margin: 14px 0 2px; white-space: normal; height: auto; align-items: flex-start; }
.consent :deep(.el-checkbox__label) { line-height: 1.6; font-size: 12px; }
.submit-actions { display: flex; justify-content: space-between; align-items: center; gap: 15px; margin-top: 15px; }
.submit-actions span { color: #8995a1; font-size: 11px; }
.history-panel { margin-bottom: 20px; }
.review-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 9px; margin-bottom: 16px; }
.review-metrics span { background: #f6f9f9; border-radius: 9px; padding: 10px 12px; font-size: 10px; color: #85919e; display: flex; flex-direction: column; gap: 3px; }
.review-metrics b { font-size: 20px; color: #24413e; }
.version { font-family: Consolas, monospace; font-weight: 700; color: #0f766e; }
.date-cell small { display: block; color: #b04b33; font-size: 9px; margin-top: 2px; }
.side-card { margin-bottom: 15px; }
.side-card h3 { font-size: 15px; margin-bottom: 15px; }
.rubric-list > div { display: grid; grid-template-columns: 1fr auto; gap: 5px 12px; border-bottom: 1px solid #edf1f2; padding: 10px 0; font-size: 12px; }
.rubric-list b { color: #43525f; }
.rubric-list small { grid-column: 1 / -1; color: #97a1ac; line-height: 1.5; }
.rubric-total { font-weight: 700; border-bottom: 0 !important; }
.small { font-size: 12px; line-height: 1.7; }
.tips { padding-left: 17px; margin: 0; color: #74818e; font-size: 11px; line-height: 1.85; }
.tips li { margin-bottom: 5px; }
.review-student { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.review-student span { font-weight: 700; }
.review-student small { color: #8b96a2; font-size: 11px; }
.review-content { white-space: pre-wrap; max-height: 260px; overflow: auto; background: #f6f9f9; border: 1px solid #e6ecec; border-radius: 10px; padding: 15px; line-height: 1.75; font-size: 12px; color: #4f5d69; margin-bottom: 20px; }
.review-files { display: grid; gap: 5px; margin: -8px 0 18px; padding: 11px 13px; background: #fffaf0; border: 1px solid #f1e5c9; border-radius: 9px; }
.review-files > span { color: #8b743f; font-size: 10px; font-weight: 700; }
.review-files :deep(.el-button) { justify-content: flex-start; font-size: 11px; }
.attachment-drawer { display: grid; gap: 8px; }
.attachment-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 12px 13px; border: 1px solid #e8eeee; border-radius: 9px; background: #fbfcfc; }
.attachment-row strong, .attachment-row small { display: block; }
.attachment-row strong { font-size: 12px; color: #354652; }
.attachment-row small { margin-top: 4px; color: #93a0ac; font-size: 10px; }
.drawer-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 6px; }
@media (max-width: 640px) { .deadline-card { width: 100%; } .submit-actions { align-items: stretch; flex-direction: column; } .review-metrics { grid-template-columns: 1fr 1fr; } .rule-grid { grid-template-columns: 1fr; } .file-upload-head { align-items: flex-start; flex-direction: column; } }
</style>
