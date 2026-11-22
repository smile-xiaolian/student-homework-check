<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { ElMessage, type UploadFile, type UploadInstance } from 'element-plus'
import { reviewApi } from '@/api/reviews'
import { fileApi } from '@/api/files'
import { errorMessage } from '@/api/http'
import type { Assignment, Review } from '@/types/api'

const props = defineProps<{
  submissionId: string
  assignment: Assignment
}>()

const emit = defineEmits<{
  (e: 'saved', review: Review | null): void
  (e: 'submitted', review: Review | null): void
}>()

const loading = ref(true)
const saving = ref(false)
const review = ref<Review | null>(null)
const rowVersion = ref<number | null>(null)

const reviewForm = reactive({ rawScore: 0, checkVerdict: 'ORIGINAL', comment: '' })
const dimensionScores = reactive<Record<string, number>>({})
const levelSelection = reactive<Record<string, string>>({})
const annotations = ref<Array<{ id: string; text: string; quote: string }>>([])
/** 评分方式二选一：DIMENSION 按维度评分（自动合计总分），RAW 直接输入原始得分 */
const scoreMode = ref<'DIMENSION' | 'RAW'>('DIMENSION')

/** 反馈附件（单文件，先上传得到 fileId，再随保存/更正提交） */
const feedbackFileId = ref<string | null>(null)
const feedbackFileName = ref<string | null>(null)
const feedbackUploading = ref(false)
const feedbackUploadPercent = ref(0)
const feedbackUploadRef = ref<UploadInstance>()

/** 成绩更正 */
const gradeDialogVisible = ref(false)
const gradeSaving = ref(false)
const gradeForm = reactive({ rawScore: 0, checkVerdict: 'ORIGINAL', comment: '', reason: '' })

const canCorrectGrade = computed(() => ['SUBMITTED', 'PUBLISHED'].includes(review.value?.status ?? ''))

const dimensions = computed(() => props.assignment.rubricDefinition?.dimensions ?? [])
const rubricTotal = computed(() => {
  const def = props.assignment.rubricDefinition
  const sum = dimensions.value.reduce((acc, dim) => acc + (dim.maxScore || 0), 0)
  return def?.totalScore && def.totalScore > 0 ? def.totalScore : sum
})
const dimensionTotal = computed(() =>
  Object.values(dimensionScores).reduce((acc, value) => acc + (Number(value) || 0), 0))
const rawMax = computed(() => (dimensions.value.length ? rubricTotal.value || 100 : 100))

function isConflict(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 412
}

function fillFromReview(data: Review): void {
  review.value = data
  rowVersion.value = data.rowVersion
  reviewForm.rawScore = data.rawScore ?? 0
  reviewForm.checkVerdict = data.checkVerdict ?? 'ORIGINAL'
  reviewForm.comment = data.comment ?? ''
  feedbackFileId.value = data.feedbackFileId ?? null
  feedbackFileName.value = data.feedbackFileName ?? null
  // 已保存过维度评分则以维度模式回显，否则按原始得分模式
  scoreMode.value = dimensions.value.length && (data.dimensionScores?.length ?? 0) > 0 ? 'DIMENSION' : 'RAW'
  const scoreMap = new Map<string, number>()
  for (const item of data.dimensionScores ?? []) scoreMap.set(item.criterionId, item.score)
  for (const dim of dimensions.value) {
    const value = scoreMap.get(dim.key)
    dimensionScores[dim.key] = value ?? 0
    if (dim.levels?.length && value != null) {
      const matched = dim.levels.find((level) => Math.abs(level.score - value) < 0.001)
      if (matched) levelSelection[dim.key] = matched.label
    }
  }
  annotations.value = (data.annotations ?? []).map((item) => ({
    id: item.id, text: item.text, quote: item.quote ?? '',
  }))
}

async function loadReview(): Promise<void> {
  loading.value = true
  try {
    fillFromReview(await reviewApi.get(props.submissionId))
  } catch {
    review.value = null
    rowVersion.value = null
    reviewForm.rawScore = 0
    reviewForm.checkVerdict = 'ORIGINAL'
    reviewForm.comment = ''
    feedbackFileId.value = null
    feedbackFileName.value = null
    scoreMode.value = dimensions.value.length ? 'DIMENSION' : 'RAW'
    for (const dim of dimensions.value) dimensionScores[dim.key] = 0
    annotations.value = []
  } finally {
    loading.value = false
  }
}

function onLevelChange(key: string): void {
  const dim = dimensions.value.find((item) => item.key === key)
  const level = dim?.levels?.find((item) => item.label === levelSelection[key])
  if (level) dimensionScores[key] = level.score
}

function addAnnotation(): void {
  annotations.value.push({ id: crypto.randomUUID(), text: '', quote: '' })
}

function removeAnnotation(index: number): void {
  annotations.value.splice(index, 1)
}

function buildPayload(): Record<string, unknown> {
  const byDimension = dimensions.value.length > 0 && scoreMode.value === 'DIMENSION'
  return {
    rawScore: byDimension ? Number(dimensionTotal.value) : Number(reviewForm.rawScore),
    dimensionScores: byDimension
      ? dimensions.value.map((dim) => ({
        criterionId: dim.key,
        score: Number(dimensionScores[dim.key] ?? 0),
      }))
      : [],
    annotations: annotations.value.map((item) => ({ id: item.id, text: item.text, quote: item.quote })),
    checkVerdict: reviewForm.checkVerdict,
    comment: reviewForm.comment,
    feedbackFileId: feedbackFileId.value ?? undefined,
    expectedRowVersion: rowVersion.value ?? undefined,
  }
}

async function save(submitAfter = false): Promise<void> {
  saving.value = true
  try {
    const saved = await reviewApi.save(props.submissionId, buildPayload())
    rowVersion.value = saved.rowVersion
    review.value = saved
    if (submitAfter) {
      const submitted = await reviewApi.submit(props.submissionId)
      rowVersion.value = submitted.rowVersion
      ElMessage.success('评审已提交')
      emit('submitted', submitted)
    } else {
      ElMessage.success('评审草稿已保存')
      emit('saved', saved)
    }
  } catch (error) {
    if (isConflict(error)) {
      ElMessage.error('该评审结果已被其他人更新，请关闭后重新打开。')
    } else {
      ElMessage.error(errorMessage(error))
    }
  } finally {
    saving.value = false
  }
}

/** 反馈附件上传（单文件，≤20MB，pdf/docx/png/jpg） */
function onFeedbackFileChange(uploadFile: UploadFile): void {
  const raw = uploadFile.raw
  if (!raw) return
  const extension = raw.name.includes('.') ? raw.name.split('.').pop()?.toLowerCase() ?? '' : ''
  if (!['pdf', 'docx', 'png', 'jpg', 'jpeg'].includes(extension)) {
    ElMessage.error('反馈附件仅支持 pdf、docx、png、jpg 格式')
    feedbackUploadRef.value?.clearFiles()
    return
  }
  if (raw.size > 20 * 1024 * 1024) {
    ElMessage.error('反馈附件不能超过 20MB')
    feedbackUploadRef.value?.clearFiles()
    return
  }
  void uploadFeedbackFile(raw)
}

async function uploadFeedbackFile(file: File): Promise<void> {
  feedbackUploading.value = true
  feedbackUploadPercent.value = 0
  try {
    const uploaded = await fileApi.upload(props.assignment.id, file, (percent) => { feedbackUploadPercent.value = percent })
    feedbackFileId.value = uploaded.id
    feedbackFileName.value = uploaded.originalName
    ElMessage.success('反馈附件已上传，保存评审后生效')
  } catch (error) {
    ElMessage.error(`反馈附件上传失败：${errorMessage(error)}`)
  } finally {
    feedbackUploading.value = false
    feedbackUploadPercent.value = 0
    feedbackUploadRef.value?.clearFiles()
  }
}

async function downloadFeedback(): Promise<void> {
  if (!feedbackFileId.value) return
  try {
    await fileApi.downloadById(feedbackFileId.value, feedbackFileName.value || '反馈附件')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function openGradeDialog(): void {
  gradeForm.rawScore = review.value?.rawScore ?? 0
  gradeForm.checkVerdict = review.value?.checkVerdict ?? 'ORIGINAL'
  gradeForm.comment = review.value?.comment ?? ''
  gradeForm.reason = ''
  gradeDialogVisible.value = true
}

async function submitGradeCorrection(): Promise<void> {
  if (gradeForm.reason.trim().length < 8) {
    ElMessage.warning('请填写至少 8 个字的更正理由')
    return
  }
  gradeSaving.value = true
  try {
    const updated = await reviewApi.modifyGrade(props.submissionId, {
      rawScore: Number(gradeForm.rawScore),
      checkVerdict: gradeForm.checkVerdict,
      comment: gradeForm.comment,
      reason: gradeForm.reason.trim(),
      feedbackFileId: feedbackFileId.value ?? undefined,
      expectedRowVersion: rowVersion.value ?? undefined,
    })
    ElMessage.success('成绩已更正并重新通知')
    gradeDialogVisible.value = false
    review.value = updated
    rowVersion.value = updated.rowVersion
    emit('submitted', updated)
  } catch (error) {
    if (isConflict(error)) {
      ElMessage.error('该评审结果已被其他人更新，请关闭后重新打开。')
    } else {
      ElMessage.error(errorMessage(error))
    }
  } finally {
    gradeSaving.value = false
  }
}

onMounted(loadReview)
</script>

<template>
  <div class="review-form-panel" v-loading="loading">
    <template v-if="dimensions.length">
      <div class="score-mode">
        <span>评分方式</span>
        <el-radio-group v-model="scoreMode">
          <el-radio value="DIMENSION">按维度评分</el-radio>
          <el-radio value="RAW">直接输入原始得分</el-radio>
        </el-radio-group>
      </div>
      <template v-if="scoreMode === 'DIMENSION'">
        <div class="rubric-summary">
          <span>维度合计</span><b>{{ dimensionTotal.toFixed(1) }}</b>
          <span class="divider">/</span><span>量规总分</span><b>{{ rubricTotal.toFixed(1) }}</b>
        </div>
        <div class="dimension-list">
          <div v-for="dim in dimensions" :key="dim.key" class="dim-row">
            <div class="dim-info"><span>{{ dim.name }}</span><small>{{ dim.description }}</small></div>
            <div class="dim-input">
              <el-select v-if="dim.levels?.length" v-model="levelSelection[dim.key]" placeholder="选择等级"
                         style="width: 100%" @change="onLevelChange(dim.key)">
                <el-option v-for="level in dim.levels" :key="level.label" :label="`${level.label}（${level.score} 分）`" :value="level.label" />
              </el-select>
              <el-input-number v-else v-model="dimensionScores[dim.key]" :min="0" :max="dim.maxScore"
                               :step="0.5" :precision="1" controls-position="right" style="width: 100%" />
            </div>
            <span class="dim-max">/ {{ dim.maxScore }}</span>
          </div>
        </div>
      </template>
    </template>
    <p v-else class="muted small rubric-empty">该作业未配置评分量规，仅记录原始得分。</p>

    <el-form label-position="top" class="review-form">
      <el-form-item :label="dimensions.length && scoreMode === 'DIMENSION' ? '原始得分（按维度自动合计）' : '原始得分'">
        <el-input-number v-if="scoreMode === 'RAW' || !dimensions.length" v-model="reviewForm.rawScore"
                         :min="0" :max="rawMax" :precision="1" style="width: 100%" />
        <template v-else>
          <el-input :model-value="dimensionTotal.toFixed(1)" disabled />
          <p class="mode-hint">已按维度评分自动计算总分，如需手工给分请切换为「直接输入原始得分」。</p>
        </template>
      </el-form-item>
      <el-form-item label="查重结论">
        <el-select v-model="reviewForm.checkVerdict" style="width: 100%">
          <el-option label="原创" value="ORIGINAL" />
          <el-option label="疑似抄袭" value="SUSPECTED_PLAGIARISM" />
          <el-option label="需要面谈" value="INTERVIEW_REQUIRED" />
        </el-select>
      </el-form-item>
      <el-form-item label="评语">
        <el-input v-model="reviewForm.comment" type="textarea" :rows="4" placeholder="填写反馈与改进建议" />
      </el-form-item>
    </el-form>

    <div class="annotations-block">
      <div class="annotations-head">
        <span>在线批注</span>
        <el-button size="small" @click="addAnnotation">＋ 添加批注</el-button>
      </div>
      <div v-for="(ann, index) in annotations" :key="ann.id" class="annotation-item">
        <el-input v-model="ann.text" type="textarea" :rows="2" placeholder="批注内容" />
        <div class="annotation-quote">
          <el-input v-model="ann.quote" type="textarea" :rows="1" maxlength="200" show-word-limit
                    placeholder="请粘贴原文片段以便定位（≤200 字）" />
          <el-button link type="danger" @click="removeAnnotation(index)">删除</el-button>
        </div>
      </div>
      <p v-if="!annotations.length" class="muted small">暂无批注。可对具体引用片段添加批注，便于学生定位修改。</p>
    </div>

    <div class="feedback-block">
      <div class="feedback-head">
        <span>反馈附件</span>
        <el-upload ref="feedbackUploadRef" :show-file-list="false" :auto-upload="false" :limit="1"
                   accept=".pdf,.docx,.png,.jpg,.jpeg" :on-change="onFeedbackFileChange">
          <el-button size="small" :loading="feedbackUploading" :disabled="feedbackUploading">上传反馈附件</el-button>
        </el-upload>
      </div>
      <el-progress v-if="feedbackUploading" :percentage="feedbackUploadPercent" :stroke-width="6" />
      <div v-if="feedbackFileId" class="feedback-file">
        <el-button link type="primary" @click="downloadFeedback">{{ feedbackFileName || '反馈附件' }}</el-button>
        <el-button link type="danger" @click="feedbackFileId = null; feedbackFileName = null">移除</el-button>
      </div>
      <p v-else class="muted small">可上传一份 PDF、Word 或图片作为反馈材料（≤20MB），保存评审时随评审结果一并提交。</p>
    </div>

    <div class="drawer-actions">
      <el-button v-if="canCorrectGrade" type="warning" plain @click="openGradeDialog">更正成绩</el-button>
      <el-button :loading="saving" @click="save(false)">保存草稿</el-button>
      <el-button type="primary" :loading="saving" @click="save(true)">提交评审</el-button>
    </div>
  </div>

  <el-dialog v-model="gradeDialogVisible" title="更正成绩" width="min(520px, 94vw)">
    <el-form label-position="top">
      <el-form-item label="原始得分">
        <el-input-number v-model="gradeForm.rawScore" :min="0" :max="rawMax" :precision="1" style="width: 100%" />
      </el-form-item>
      <el-form-item label="查重结论">
        <el-select v-model="gradeForm.checkVerdict" style="width: 100%">
          <el-option label="原创" value="ORIGINAL" />
          <el-option label="疑似抄袭" value="SUSPECTED_PLAGIARISM" />
          <el-option label="需要面谈" value="INTERVIEW_REQUIRED" />
        </el-select>
      </el-form-item>
      <el-form-item label="评语">
        <el-input v-model="gradeForm.comment" type="textarea" :rows="3" placeholder="填写反馈与改进建议" />
      </el-form-item>
      <el-form-item label="更正理由">
        <el-input v-model="gradeForm.reason" type="textarea" :rows="3" maxlength="200" show-word-limit
                  placeholder="请说明更正原因（至少 8 个字），更正后将重新通知学生" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="gradeDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="gradeSaving" @click="submitGradeCorrection">确认更正并重新通知</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.review-form-panel { min-height: 200px; }
.rubric-summary { display: flex; align-items: baseline; gap: 8px; padding: 11px 13px; background: #f6f9f9; border: 1px solid #e6ecec; border-radius: 9px; margin-bottom: 14px; font-size: 12px; }
.rubric-summary span { color: #7d8996; }
.rubric-summary b { color: #0f766e; font-size: 18px; }
.rubric-summary .divider { color: #b7c1c8; }
.dimension-list { display: grid; gap: 8px; margin-bottom: 18px; }
.dim-row { display: grid; grid-template-columns: 1fr minmax(150px, 190px) auto; gap: 10px; align-items: center; border: 1px solid #e7eded; border-radius: 9px; padding: 9px 12px; background: #fbfcfc; }
.dim-info span { display: block; font-size: 12px; color: #354652; }
.dim-info small { display: block; color: #97a1ac; font-size: 10px; margin-top: 2px; line-height: 1.4; }
.dim-max { color: #97a1ac; font-size: 11px; white-space: nowrap; }
.rubric-empty { margin: 0 0 16px; }
.score-mode { display: flex; align-items: center; gap: 12px; padding: 10px 13px; background: #f6f9f9; border: 1px solid #e6ecec; border-radius: 9px; margin-bottom: 14px; font-size: 12px; }
.score-mode > span { color: #7d8996; white-space: nowrap; }
.mode-hint { margin: 6px 0 0; color: #8c97a3; font-size: 11px; line-height: 1.6; }
.review-form :deep(.el-form-item) { margin-bottom: 14px; }
.annotations-block { border-top: 1px solid #edf1f2; padding-top: 14px; margin-top: 4px; }
.annotations-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.annotations-head span { font-size: 13px; font-weight: 700; color: #354652; }
.annotation-item { display: grid; gap: 8px; padding: 10px 12px; border: 1px solid #e7eded; border-radius: 9px; background: #fbfcfc; margin-bottom: 9px; }
.annotation-quote { display: flex; align-items: center; gap: 6px; }
.annotation-quote :deep(.el-textarea__inner) { font-size: 11px; }
.feedback-block { border-top: 1px solid #edf1f2; padding-top: 14px; margin-top: 4px; }
.feedback-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.feedback-head span { font-size: 13px; font-weight: 700; color: #354652; }
.feedback-file { display: flex; align-items: center; gap: 6px; padding: 9px 12px; background: #f6f9f9; border: 1px solid #e6ecec; border-radius: 9px; }
.feedback-file :deep(.el-button) { font-size: 12px; }
.small { font-size: 11px; line-height: 1.7; }
.drawer-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
@media (max-width: 520px) { .dim-row { grid-template-columns: 1fr; } }
</style>
