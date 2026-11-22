<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { assignmentApi } from '@/api/assignments'
import { reviewApi } from '@/api/reviews'
import { submissionApi } from '@/api/submissions'
import { fileApi } from '@/api/files'
import { errorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import ReviewFormPanel from '@/components/ReviewFormPanel.vue'
import type { Assignment, Pagination, ReviewQueueItem, Submission, SubmissionFile } from '@/types/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const assignmentId = String(route.params.id)

const loading = ref(true)
const assignment = ref<Assignment | null>(null)
const items = ref<ReviewQueueItem[]>([])
const pagination = ref<Pagination>({ page: 1, size: 20, total: 0, totalPages: 0, hasNext: false })
const selectedIds = ref<string[]>([])
const reviewVisible = ref(false)
const reviewTarget = ref<ReviewQueueItem | null>(null)
/** 下载 ZIP */
const zipping = ref(false)
/** 作业附件查看 */
const attachmentVisible = ref(false)
const attachmentLoading = ref(false)
const attachmentSubmission = ref<Submission | null>(null)

const filters = reactive({
  reviewStatus: '',
  checkLevel: '',
  late: '',
  keyword: '',
  sort: 'submittedAt',
})

const isAssistantOnly = computed(() => {
  const roles = auth.user?.roles ?? []
  return roles.includes('ASSISTANT') && !roles.includes('TEACHER') && !roles.includes('ADMIN')
})

const metrics = computed(() => {
  const page = items.value
  return [
    { label: '待评审', value: page.filter((item) => item.reviewStatus === 'NOT_STARTED').length, tone: 'gold' },
    { label: '已评审', value: page.filter((item) => ['DRAFT', 'SUBMITTED'].includes(item.reviewStatus)).length, tone: 'teal' },
    { label: '已发布', value: page.filter((item) => item.reviewStatus === 'PUBLISHED').length, tone: 'teal' },
    { label: '查重失败', value: page.filter((item) => item.checkLevel === 'SEVERE').length, tone: 'rose' },
    { label: '迟交', value: page.filter((item) => item.late).length, tone: 'rose' },
  ]
})

const reviewStatusLabel: Record<string, string> = {
  NOT_STARTED: '未开始', DRAFT: '草稿', SUBMITTED: '已提交', PUBLISHED: '已发布',
}

function reviewStatusType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'SUBMITTED') return 'primary'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}

function checkLevelType(level: string | null): 'success' | 'warning' | 'danger' | 'info' {
  if (level === 'PASS') return 'success'
  if (level === 'WARNING') return 'warning'
  if (level === 'SEVERE') return 'danger'
  return 'info'
}

function format(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function queueParams(): Record<string, unknown> {
  return {
    page: pagination.value.page,
    size: pagination.value.size,
    reviewStatus: filters.reviewStatus || undefined,
    checkLevel: filters.checkLevel || undefined,
    late: filters.late === '' ? undefined : filters.late === 'yes',
    keyword: filters.keyword || undefined,
    sort: filters.sort || undefined,
  }
}

async function loadQueue(): Promise<void> {
  loading.value = true
  try {
    const result = await reviewApi.queue(assignmentId, queueParams())
    items.value = result.items
    pagination.value = result.pagination
    selectedIds.value = []
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [assignmentData, queueResult] = await Promise.all([
      assignmentApi.get(assignmentId),
      reviewApi.queue(assignmentId, queueParams()),
    ])
    assignment.value = assignmentData
    items.value = queueResult.items
    pagination.value = queueResult.pagination
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function openReview(item: ReviewQueueItem): void {
  reviewTarget.value = item
  reviewVisible.value = true
}

/** 在评审台直接查看学生作业附件，无需跳转作业详情页 */
async function openAttachments(item: ReviewQueueItem): Promise<void> {
  attachmentSubmission.value = null
  attachmentVisible.value = true
  attachmentLoading.value = true
  try {
    attachmentSubmission.value = await submissionApi.get(item.submissionId)
  } catch (error) {
    ElMessage.error(errorMessage(error))
    attachmentVisible.value = false
  } finally {
    attachmentLoading.value = false
  }
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

function onSelectionChange(rows: ReviewQueueItem[]): void {
  selectedIds.value = rows.map((row) => row.submissionId)
}

function resetPage(): void {
  pagination.value.page = 1
  loadQueue()
}

function onPageChange(page: number): void {
  pagination.value.page = page
  loadQueue()
}

async function downloadZip(): Promise<void> {
  if (!selectedIds.value.length) {
    ElMessage.warning('请先勾选要下载的提交')
    return
  }
  zipping.value = true
  try {
    const blob = await reviewApi.zipDownload(assignmentId, selectedIds.value)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `submissions-${assignmentId}.zip`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
    ElMessage.success('已开始下载所选提交的 ZIP 压缩包')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    zipping.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="breadcrumb">
      <button @click="router.push(auth.hasRole('ADMIN') ? '/admin/courses' : '/courses')">{{ auth.hasRole('ADMIN') ? '课程与作业管理' : '课程空间' }}</button><span>/</span>
      <button v-if="assignment" @click="router.push(`/assignments/${assignmentId}`)">{{ assignment.title }}</button><span>/</span>评审工作台
    </div>

    <el-skeleton :loading="loading" animated :rows="10">
      <template #default>
        <template v-if="assignment">
          <div class="page-head workbench-head">
            <div>
              <p class="eyebrow">REVIEW WORKBENCH</p>
              <h1>评审工作台</h1>
              <p>{{ assignment.title }} · 共 {{ pagination.total }} 条待办记录</p>
            </div>
            <div class="head-actions">
              <el-button size="small" @click="router.push(`/assignments/${assignmentId}`)">返回作业详情</el-button>
              <el-button size="small" @click="loadQueue">刷新</el-button>
            </div>
          </div>

          <div class="metric-bar">
            <div v-for="metric in metrics" :key="metric.label" class="metric-card" :class="metric.tone">
              <span>{{ metric.label }}<small>当前页</small></span>
              <b>{{ metric.value }}</b>
            </div>
          </div>

          <section class="surface filter-bar">
            <el-select v-model="filters.reviewStatus" placeholder="评审状态" clearable style="width: 128px" @change="resetPage">
              <el-option label="未开始" value="NOT_STARTED" />
              <el-option label="草稿" value="DRAFT" />
              <el-option label="已提交" value="SUBMITTED" />
              <el-option label="已发布" value="PUBLISHED" />
            </el-select>
            <el-select v-model="filters.checkLevel" placeholder="查重等级" clearable style="width: 128px" @change="resetPage">
              <el-option label="通过" value="PASS" />
              <el-option label="警告" value="WARNING" />
              <el-option label="严重" value="SEVERE" />
            </el-select>
            <el-select v-model="filters.late" placeholder="是否迟交" clearable style="width: 128px" @change="resetPage">
              <el-option label="是" value="yes" />
              <el-option label="否" value="no" />
            </el-select>
            <el-input v-model="filters.keyword" placeholder="学生姓名 / 学号" clearable style="width: 180px"
                      @keyup.enter="resetPage" @clear="resetPage" />
            <el-select v-model="filters.sort" placeholder="排序" style="width: 128px" @change="resetPage">
              <el-option label="按相似度" value="checkScore" />
              <el-option label="按学号" value="submitterId" />
              <el-option label="按提交时间" value="submittedAt" />
              <el-option label="按状态" value="reviewStatus" />
            </el-select>
            <el-button @click="resetPage">查询</el-button>
            <el-button v-if="!isAssistantOnly" :loading="zipping" @click="downloadZip">下载 ZIP</el-button>
          </section>

          <section class="surface table-panel">
            <el-table v-loading="loading" :data="items" style="width: 100%" @selection-change="onSelectionChange">
              <el-table-column v-if="!isAssistantOnly" type="selection" width="46" />
              <el-table-column label="学号 / 姓名" min-width="150">
                <template #default="{ row }">
                  <strong class="student-name">{{ row.submitterName }}</strong>
                  <small class="student-no">{{ row.submitterId }}</small>
                </template>
              </el-table-column>
              <el-table-column label="版本" width="70"><template #default="{ row }"><span class="version">V{{ row.versionNo }}</span></template></el-table-column>
              <el-table-column label="提交时间" min-width="150"><template #default="{ row }"><span class="date-cell">{{ format(row.submittedAt) }}</span></template></el-table-column>
              <el-table-column label="查重相似度" width="105" align="right">
                <template #default="{ row }">
                  <span v-if="row.checkScore != null" :class="row.checkLevel === 'SEVERE' ? 'score-severe' : row.checkLevel === 'WARNING' ? 'score-warning' : 'score-pass'">{{ row.checkScore.toFixed(1) }}%</span>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="查重等级" width="100">
                <template #default="{ row }">
                  <el-tag v-if="row.checkLevel" size="small" effect="plain" :type="checkLevelType(row.checkLevel)">
                    {{ ({ PASS: '通过', WARNING: '警告', SEVERE: '严重' } as Record<string, string>)[row.checkLevel] ?? row.checkLevel }}
                  </el-tag>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="评审状态" width="100">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain" :type="reviewStatusType(row.reviewStatus)">{{ reviewStatusLabel[row.reviewStatus] ?? row.reviewStatus }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="成绩状态" width="100">
                <template #default="{ row }">
                  <el-tag v-if="row.gradeStatus === 'PUBLISHED'" size="small" type="success" effect="plain">已发布</el-tag>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="迟交" width="72">
                <template #default="{ row }">
                  <el-tag v-if="row.late" size="small" type="warning" effect="plain">迟交</el-tag>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="230" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openAttachments(row)">作业附件</el-button>
                  <el-button link type="primary" @click="router.push(`/submissions/${row.submissionId}/report`)">查重报告</el-button>
                  <el-button link type="primary" @click="openReview(row)">评审</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination v-if="pagination.totalPages > 1" v-model:current-page="pagination.page"
                           :page-size="pagination.size" :total="pagination.total"
                           layout="prev, pager, next, total" class="pager" @current-change="onPageChange" />
            <div v-if="!items.length && !loading" class="empty-line">暂无待评审的提交记录</div>
          </section>
        </template>
      </template>
    </el-skeleton>

    <el-dialog v-model="attachmentVisible" title="作业附件" width="min(600px, 94vw)" destroy-on-close>
      <div v-loading="attachmentLoading" class="attachment-body">
        <template v-if="attachmentSubmission">
          <div class="attachment-meta">
            <strong>{{ attachmentSubmission.submitterName }}</strong>
            <small>第 {{ attachmentSubmission.versionNo }} 版 · {{ format(attachmentSubmission.submittedAt) }}</small>
          </div>
          <div v-if="attachmentSubmission.onlineText" class="attachment-online">
            <span>在线文本</span>
            <pre>{{ attachmentSubmission.onlineText }}</pre>
          </div>
          <div v-if="attachmentSubmission.files?.length" class="attachment-list">
            <div v-for="file in attachmentSubmission.files" :key="file.id" class="attachment-row">
              <div class="attachment-file">
                <strong>{{ file.originalName }}</strong>
                <small>{{ file.mimeType }} · {{ formatBytes(file.sizeBytes) }}</small>
              </div>
              <el-button link type="primary" @click="downloadFile(file)">下载</el-button>
            </div>
          </div>
          <el-empty v-else description="该提交没有附件" />
        </template>
      </div>
    </el-dialog>

    <el-drawer v-model="reviewVisible" title="作业评审" size="min(560px, 94vw)" destroy-on-close>
      <template v-if="reviewTarget && assignment">
        <div class="review-student">
          <span>{{ reviewTarget.submitterName }}</span>
          <small>第 {{ reviewTarget.versionNo }} 版 · {{ format(reviewTarget.submittedAt) }}</small>
        </div>
        <ReviewFormPanel :submission-id="reviewTarget.submissionId" :assignment="assignment" @submitted="reviewVisible = false" />
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.breadcrumb { display: flex; gap: 9px; align-items: center; color: #93a0ac; font-size: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.breadcrumb button { border: 0; background: transparent; color: #0f766e; cursor: pointer; padding: 0; font-size: 12px; }
.eyebrow { color: #0f766e; font-size: 11px; font-weight: 800; letter-spacing: .14em; margin-bottom: 9px; }
.workbench-head { align-items: center; }
.head-actions { display: flex; gap: 8px; }
.metric-bar { display: grid; grid-template-columns: repeat(5, 1fr); gap: 11px; margin-bottom: 17px; }
.metric-card { background: white; border: 1px solid #e3e9ed; border-radius: 14px; padding: 14px 16px; }
.metric-card span { display: flex; justify-content: space-between; align-items: center; color: #7d8996; font-size: 11px; margin-bottom: 7px; }
.metric-card span small { color: #a7b0b9; font-size: 9px; }
.metric-card b { font-size: 26px; letter-spacing: -.04em; }
.metric-card.teal b { color: #0f766e; }
.metric-card.gold b { color: #9a6a08; }
.metric-card.rose b { color: #b34235; }
.filter-bar { display: flex; gap: 9px; align-items: center; flex-wrap: wrap; padding: 15px 18px; margin-bottom: 15px; }
.table-panel { overflow: hidden; padding-bottom: 6px; }
.student-name { display: block; font-size: 12px; color: #354652; }
.student-no { display: block; color: #97a1ac; font-size: 10px; margin-top: 2px; }
.version { font-family: Consolas, monospace; font-weight: 700; color: #0f766e; }
.date-cell { font-size: 11px; color: #5b6874; }
.pager { justify-content: center; padding: 16px; }
.review-student { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.review-student span { font-weight: 700; }
.review-student small { color: #8b96a2; font-size: 11px; }
.attachment-body { min-height: 120px; }
.attachment-meta { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.attachment-meta strong { font-size: 14px; }
.attachment-meta small { color: #8b96a2; font-size: 11px; }
.attachment-online { border: 1px solid #e7eded; border-radius: 9px; padding: 12px; background: #fbfcfc; margin-bottom: 14px; }
.attachment-online span { display: block; font-size: 11px; color: #7d8996; margin-bottom: 6px; }
.attachment-online pre { margin: 0; font-family: inherit; font-size: 12px; line-height: 1.7; color: #354652; white-space: pre-wrap; word-break: break-word; }
.attachment-list { display: grid; gap: 8px; }
.attachment-row { display: flex; justify-content: space-between; align-items: center; gap: 10px; border: 1px solid #e7eded; border-radius: 9px; padding: 10px 12px; background: #fbfcfc; }
.attachment-file strong, .attachment-file small { display: block; }
.attachment-file strong { font-size: 12px; color: #354652; }
.attachment-file small { font-size: 10px; color: #97a1ac; margin-top: 2px; }
@media (max-width: 900px) { .metric-bar { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 560px) { .metric-bar { grid-template-columns: 1fr; } }
</style>
