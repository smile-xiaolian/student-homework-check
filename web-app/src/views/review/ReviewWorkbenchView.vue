<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { assignmentApi } from '@/api/assignments'
import { reviewApi } from '@/api/reviews'
import { errorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import ReviewFormPanel from '@/components/ReviewFormPanel.vue'
import type { Assignment, BatchScorePreview, Pagination, ReviewQueueItem } from '@/types/api'

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
const publishing = ref(false)

/** 批量打分 */
const batchDialogVisible = ref(false)
const batchSubmitting = ref(false)
const batchPreviewLoading = ref(false)
const batchPreview = ref<BatchScorePreview | null>(null)
const batchForm = reactive<{ rule: string; fixedScore: number; reason: string }>({
  rule: 'ALL_MAX', fixedScore: 0, reason: '',
})
/** 下载 ZIP */
const zipping = ref(false)

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

function onSelectionChange(rows: ReviewQueueItem[]): void {
  selectedIds.value = rows.map((row) => row.submissionId)
}

async function publishGrades(submissionIds: string[]): Promise<void> {
  if (!submissionIds.length) return
  publishing.value = true
  try {
    await reviewApi.publishGrades(assignmentId, submissionIds)
    ElMessage.success(`已发布 ${submissionIds.length} 份成绩`)
    await loadQueue()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    publishing.value = false
  }
}

async function batchPublish(): Promise<void> {
  const count = selectedIds.value.length
  if (!count) {
    ElMessage.warning('请先勾选要发布成绩的提交')
    return
  }
  try {
    await ElMessageBox.confirm(`确定发布所选 ${count} 份提交的成绩吗？发布后学生将可见最终得分。`, '批量发布成绩', { type: 'warning' })
    await publishGrades(selectedIds.value)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

async function publishOne(item: ReviewQueueItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定发布「${item.submitterName}」第 ${item.versionNo} 版的成绩吗？`, '发布成绩', { type: 'warning' })
    await publishGrades([item.submissionId])
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

function resetPage(): void {
  pagination.value.page = 1
  loadQueue()
}

function onPageChange(page: number): void {
  pagination.value.page = page
  loadQueue()
}

/** 批量打分：先预览再确认 */
function openBatchDialog(): void {
  if (!selectedIds.value.length) {
    ElMessage.warning('请先勾选要打分的提交')
    return
  }
  batchForm.rule = 'ALL_MAX'
  batchForm.fixedScore = 0
  batchForm.reason = ''
  batchPreview.value = null
  batchDialogVisible.value = true
}

function onBatchRuleChange(): void {
  batchPreview.value = null
}

function buildBatchPayload(): Record<string, unknown> {
  return {
    submissionIds: [...selectedIds.value],
    rule: batchForm.rule,
    fixedScore: batchForm.rule === 'FIXED' ? Number(batchForm.fixedScore) : undefined,
    reason: batchForm.reason.trim(),
  }
}

async function previewBatch(): Promise<void> {
  if (!selectedIds.value.length) {
    ElMessage.warning('请先勾选要打分的提交')
    return
  }
  if (batchForm.reason.trim().length < 8) {
    ElMessage.warning('请填写至少 8 个字的批量打分理由')
    return
  }
  if (batchForm.rule === 'FIXED' && (Number(batchForm.fixedScore) < 0 || Number(batchForm.fixedScore) > 100)) {
    ElMessage.warning('固定分必须在 0-100 之间')
    return
  }
  batchPreviewLoading.value = true
  try {
    batchPreview.value = await reviewApi.batchScorePreview(assignmentId, buildBatchPayload())
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    batchPreviewLoading.value = false
  }
}

async function confirmBatch(): Promise<void> {
  if (!batchPreview.value) return
  batchSubmitting.value = true
  try {
    await reviewApi.batchScore(assignmentId, buildBatchPayload())
    ElMessage.success(`已批量打分 ${selectedIds.value.length} 份提交`)
    batchDialogVisible.value = false
    batchPreview.value = null
    await loadQueue()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    batchSubmitting.value = false
  }
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
      <button @click="router.push('/courses')">课程空间</button><span>/</span>
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
            <el-button v-if="!isAssistantOnly" :loading="batchPreviewLoading || batchSubmitting" @click="openBatchDialog">批量打分</el-button>
            <el-button v-if="!isAssistantOnly" :loading="zipping" @click="downloadZip">下载 ZIP</el-button>
            <el-button v-if="!isAssistantOnly" type="primary" :loading="publishing" @click="batchPublish">批量发布成绩</el-button>
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
                  <el-button link type="primary" @click="router.push(`/assignments/${assignmentId}`)">查看提交</el-button>
                  <el-button link type="primary" @click="router.push(`/submissions/${row.submissionId}/report`)">查重报告</el-button>
                  <el-button link type="primary" @click="openReview(row)">评审</el-button>
                  <el-button v-if="!isAssistantOnly" link type="success" @click="publishOne(row)">发布成绩</el-button>
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

    <el-drawer v-model="reviewVisible" title="作业评审" size="min(560px, 94vw)" destroy-on-close>
      <template v-if="reviewTarget && assignment">
        <div class="review-student">
          <span>{{ reviewTarget.submitterName }}</span>
          <small>第 {{ reviewTarget.versionNo }} 版 · {{ format(reviewTarget.submittedAt) }}</small>
        </div>
        <ReviewFormPanel :submission-id="reviewTarget.submissionId" :assignment="assignment" @submitted="reviewVisible = false" />
      </template>
    </el-drawer>

    <el-dialog v-model="batchDialogVisible" title="批量打分" width="min(660px, 94vw)">
      <el-form label-position="top">
        <el-form-item label="打分规则">
          <el-radio-group v-model="batchForm.rule" @change="onBatchRuleChange">
            <el-radio value="ALL_MAX">满分（100）</el-radio>
            <el-radio value="BY_CHECK_LEVEL">按查重等级</el-radio>
            <el-radio value="FIXED">固定分</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="batchForm.rule === 'FIXED'" label="固定分数">
          <el-input-number v-model="batchForm.fixedScore" :min="0" :max="100" :precision="1" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="批量打分理由">
          <el-input v-model="batchForm.reason" type="textarea" :rows="3" maxlength="200" show-word-limit
                    placeholder="请说明批量打分理由（至少 8 个字），将随成绩记录留痕" />
        </el-form-item>
      </el-form>

      <div v-if="batchPreview" class="batch-preview">
        <div class="batch-preview-head">
          <span>预览：将影响 <b>{{ batchPreview.previews.length }}</b> 份成绩</span>
          <el-button size="small" :loading="batchPreviewLoading" @click="previewBatch">重新预览</el-button>
        </div>
        <el-table :data="batchPreview.previews" size="small" max-height="260" style="width: 100%">
          <el-table-column prop="submitterName" label="学生" min-width="110" />
          <el-table-column label="查重等级" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.checkLevel" size="small" effect="plain" :type="checkLevelType(row.checkLevel)">
                {{ ({ PASS: '通过', WARNING: '警告', SEVERE: '严重' } as Record<string, string>)[row.checkLevel] ?? row.checkLevel }}
              </el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="调整前" width="80" align="right">
            <template #default="{ row }"><span>{{ row.before ?? '—' }}</span></template>
          </el-table-column>
          <el-table-column label="调整后" width="80" align="right">
            <template #default="{ row }"><strong class="after-score">{{ row.after ?? '—' }}</strong></template>
          </el-table-column>
        </el-table>
      </div>
      <p v-else class="muted small batch-hint">点击「预览影响」查看将调整的成绩范围，确认无误后再执行。</p>

      <template #footer>
        <el-button @click="batchDialogVisible = false">取消</el-button>
        <el-button :loading="batchPreviewLoading" @click="previewBatch">预览影响</el-button>
        <el-button type="primary" :loading="batchSubmitting" :disabled="!batchPreview" @click="confirmBatch">确认打分</el-button>
      </template>
    </el-dialog>
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
.batch-preview { border: 1px solid #e7eded; border-radius: 10px; padding: 12px; background: #fbfcfc; margin-top: 6px; }
.batch-preview-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.batch-preview-head span { font-size: 12px; color: #536171; }
.batch-preview-head b { color: #0f766e; }
.after-score { color: #0f766e; }
.batch-hint { margin: 8px 0 0; }
@media (max-width: 900px) { .metric-bar { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 560px) { .metric-bar { grid-template-columns: 1fr; } }
</style>
