<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { assignmentApi } from '@/api/assignments'
import { libraryApi } from '@/api/libraries'
import { errorMessage } from '@/api/http'
import type { Assignment, CheckConfig, Library, RubricDimension } from '@/types/api'

const props = defineProps<{
  modelValue: boolean
  courseId: string
  assignment: Assignment | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved', value: Assignment | null): void
}>()

const formRef = ref<FormInstance>()
const saving = ref(false)

const now = new Date()
const localDeadline = new Date(now.getTime() + 7 * 86_400_000 - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)

const form = reactive({
  title: '', description: '', deadlineAt: localDeadline, allowLate: false,
  lateDeadlineAt: '', weight: 0, checkEnabled: true, allowMultiVersion: true,
  effectiveRule: 'LAST_SUBMISSION' as 'LAST_SUBMISSION' | 'MANUAL',
})
const checkMode = ref<'TEXT' | 'CODE'>('TEXT')
const textWarning = ref(20)
const textSevere = ref(50)
const structWarning = ref(30)
const structSevere = ref(60)

/** 查重配置：比对库（本班库恒启用 + 白名单库多选）与排除项 */
const classLibraryChecked = true
const whitelistLibraries = ref<Library[]>([])
const selectedWhitelistIds = ref<string[]>([])
const excludeReferences = ref(true)
const excludeTemplate = ref(true)
const checkConfigEtag = ref<string | undefined>(undefined)
const rubricEtag = ref<string | undefined>(undefined)

interface RubricRow {
  key: string
  name: string
  description: string
  maxScore: number
  levels: Array<{ label: string; score: number }>
}

function defaultRubricRows(): RubricRow[] {
  return [
    { key: crypto.randomUUID(), name: '内容完成度', description: '', maxScore: 50, levels: [] },
    { key: crypto.randomUUID(), name: '质量与规范', description: '', maxScore: 30, levels: [] },
    { key: crypto.randomUUID(), name: '思考与创新', description: '', maxScore: 20, levels: [] },
  ]
}

const rubricRows = ref<RubricRow[]>(defaultRubricRows())
const rubricTotal = computed(() => rubricRows.value.reduce((sum, row) => sum + (Number(row.maxScore) || 0), 0))
const fileTypeOptions = ['pdf', 'docx', 'doc', 'txt', 'md', 'java', 'py', 'c', 'cpp', 'h', 'js', 'ts', 'go', 'zip']
const restrictFileTypes = ref(false)
const selectedFileTypes = ref<string[]>([...fileTypeOptions])
const publishMode = ref<'manual' | 'scheduled'>('manual')
const scheduledPublishAt = ref('')

const isEdit = computed(() => Boolean(props.assignment))
const title = computed(() => isEdit.value ? '编辑作业' : '新建作业')
const assignmentRules: FormRules = {
  title: [{ required: true, message: '请输入作业标题', trigger: 'blur' }],
  deadlineAt: [{ required: true, message: '请选择截止时间', trigger: 'change' }],
}

function addRubricRow(): void {
  rubricRows.value.push({ key: crypto.randomUUID(), name: '', description: '', maxScore: 10, levels: [] })
}

function removeRubricRow(index: number): void {
  if (rubricRows.value.length > 1) rubricRows.value.splice(index, 1)
}

function addLevel(row: RubricRow): void {
  if (row.levels.length >= 10) {
    ElMessage.warning('每个评分维度最多设置 10 个等级')
    return
  }
  row.levels.push({ label: '', score: 0 })
}

function removeLevel(row: RubricRow, index: number): void {
  row.levels.splice(index, 1)
}

function toIso(value: string): string | undefined {
  return value ? new Date(value).toISOString() : undefined
}

function applyCheckConfig(config: CheckConfig): void {
  checkMode.value = config.mode === 'CODE' ? 'CODE' : 'TEXT'
  textWarning.value = Number(config.text_thresholds?.warning ?? 20)
  textSevere.value = Number(config.text_thresholds?.severe ?? 50)
  structWarning.value = Number(config.struct_thresholds?.warning ?? 30)
  structSevere.value = Number(config.struct_thresholds?.severe ?? 60)
  selectedWhitelistIds.value = (config.libraries ?? []).filter((id) => id !== 'CLASS')
  excludeReferences.value = config.exclude?.references ?? true
  excludeTemplate.value = config.exclude?.template ?? true
}

function applyRubric(dimensions: RubricDimension[]): void {
  rubricRows.value = dimensions.length
    ? dimensions.map((dim) => ({
        key: dim.key,
        name: dim.name ?? '',
        description: dim.description ?? '',
        maxScore: dim.maxScore ?? 10,
        levels: (dim.levels ?? []).map((level) => ({ label: level.label, score: Number(level.score) || 0 })),
      }))
    : defaultRubricRows()
}

function resetCheckConfig(): void {
  checkMode.value = 'TEXT'
  textWarning.value = 20
  textSevere.value = 50
  structWarning.value = 30
  structSevere.value = 60
  selectedWhitelistIds.value = []
  excludeReferences.value = true
  excludeTemplate.value = true
}

/** 拉取课程下的白名单比对库（供多选） */
async function loadWhitelistLibraries(): Promise<void> {
  try {
    const list = await libraryApi.list(props.courseId)
    whitelistLibraries.value = list.filter((lib) => lib.libraryType === 'WHITELIST')
  } catch {
    whitelistLibraries.value = []
  }
}

async function fillForm(): Promise<void> {
  const item = props.assignment
  checkConfigEtag.value = undefined
  rubricEtag.value = undefined
  if (item) {
    form.title = item.title
    form.description = item.description ?? ''
    form.deadlineAt = new Date(item.deadlineAt).toISOString().slice(0, 16)
    form.allowLate = item.allowLate
    form.lateDeadlineAt = item.lateDeadlineAt ? new Date(item.lateDeadlineAt).toISOString().slice(0, 16) : ''
    form.weight = item.weight
    form.checkEnabled = item.checkEnabled
    form.allowMultiVersion = item.allowMultiVersion
    form.effectiveRule = item.effectiveRule
    // 查重配置：优先使用作业详情携带的配置，否则回退到接口拉取（拿到 ETag 用于 If-Match）
    if (item.checkConfig) {
      applyCheckConfig(item.checkConfig)
      checkConfigEtag.value = `"row-${item.rowVersion}"`
    } else {
      try {
        const result = await assignmentApi.checkConfig(item.id)
        applyCheckConfig(result.data)
        checkConfigEtag.value = result.etag ?? `"row-${item.rowVersion}"`
      } catch {
        resetCheckConfig()
      }
    }
    // 量规：优先使用作业详情携带的量规，否则回退到接口拉取
    const dims = item.rubricDefinition?.dimensions ?? []
    if (dims.length) {
      applyRubric(dims)
      rubricEtag.value = `"row-${item.rowVersion}"`
    } else {
      try {
        const result = await assignmentApi.rubric(item.id)
        applyRubric(result.data.dimensions ?? [])
        rubricEtag.value = result.etag ?? `"row-${item.rowVersion}"`
      } catch {
        rubricRows.value = defaultRubricRows()
      }
    }
    if (item.allowedFileTypes?.length) {
      restrictFileTypes.value = true
      selectedFileTypes.value = [...item.allowedFileTypes]
    } else {
      restrictFileTypes.value = false
      selectedFileTypes.value = [...fileTypeOptions]
    }
  } else {
    form.title = ''
    form.description = ''
    form.deadlineAt = localDeadline
    form.allowLate = false
    form.lateDeadlineAt = ''
    form.weight = 0
    form.checkEnabled = true
    form.allowMultiVersion = true
    form.effectiveRule = 'LAST_SUBMISSION'
    resetCheckConfig()
    rubricRows.value = defaultRubricRows()
    restrictFileTypes.value = false
    selectedFileTypes.value = [...fileTypeOptions]
    publishMode.value = 'manual'
    scheduledPublishAt.value = ''
  }
}

function validate(): string | null {
  if (form.allowLate) {
    if (!form.lateDeadlineAt) return '允许补交时必须选择补交截止时间'
    if (new Date(form.lateDeadlineAt) <= new Date(form.deadlineAt)) return '补交截止时间必须晚于正常截止时间'
  }
  if (rubricRows.value.some((row) => !row.name.trim())) return '请为每个评分维度填写名称'
  if (rubricRows.value.some((row) => !(Number(row.maxScore) > 0) || Number(row.maxScore) > 100)) return '评分维度的满分必须为 1-100 之间的数字'
  if (rubricTotal.value <= 0) return '评分量规总分必须大于 0'
  for (const row of rubricRows.value) {
    for (const level of row.levels) {
      if (!level.label.trim()) return `维度「${row.name || '未命名'}」存在未命名的评分等级`
      const score = Number(level.score)
      if (!Number.isFinite(score) || score < 0 || score > Number(row.maxScore)) {
        return `维度「${row.name}」的评分等级「${level.label}」分值必须在 0 到满分 ${row.maxScore} 之间`
      }
    }
  }
  if (restrictFileTypes.value && !selectedFileTypes.value.length) return '限制文件类型时至少选择一种类型'
  if (checkMode.value === 'TEXT') {
    if (textWarning.value < 0 || textSevere.value > 100 || textWarning.value >= textSevere.value) return '文本阈值必须满足 0 ≤ 警告 < 严重 ≤ 100'
  } else {
    if (structWarning.value < 0 || structSevere.value > 100 || structWarning.value >= structSevere.value) return '代码阈值必须满足 0 ≤ 警告 < 严重 ≤ 100'
  }
  return null
}

/** 基础字段载荷（不含查重配置与量规，二者单独提交） */
function buildPayload(): Record<string, unknown> {
  const effectiveRule = form.allowMultiVersion ? form.effectiveRule : 'LAST_SUBMISSION'
  if (!form.allowMultiVersion) form.effectiveRule = effectiveRule
  return {
    title: form.title,
    description: form.description,
    deadlineAt: toIso(form.deadlineAt),
    allowLate: form.allowLate,
    lateDeadlineAt: form.allowLate ? toIso(form.lateDeadlineAt) : undefined,
    weight: Number(form.weight),
    checkEnabled: form.checkEnabled,
    allowMultiVersion: form.allowMultiVersion,
    effectiveRule,
    allowedFileTypes: restrictFileTypes.value ? [...selectedFileTypes.value] : undefined,
  }
}

function buildCheckConfigPayload(): Record<string, unknown> {
  return {
    mode: checkMode.value,
    text_thresholds: { warning: Number(textWarning.value), severe: Number(textSevere.value) },
    struct_thresholds: { warning: Number(structWarning.value), severe: Number(structSevere.value) },
    libraries: ['CLASS', ...selectedWhitelistIds.value],
    exclude: { references: excludeReferences.value, template: excludeTemplate.value },
  }
}

function buildRubricPayload(): Record<string, unknown> {
  return {
    totalScore: rubricTotal.value,
    dimensions: rubricRows.value.map((row) => ({
      key: row.key,
      name: row.name.trim(),
      description: row.description.trim() || undefined,
      maxScore: Number(row.maxScore),
      levels: row.levels.length
        ? row.levels.map((level) => ({ label: level.label.trim(), score: Number(level.score) }))
        : undefined,
    })),
  }
}

async function save(): Promise<void> {
  if (!(await formRef.value?.validate().catch(() => false))) return
  const issue = validate()
  if (issue) {
    ElMessage.warning(issue)
    return
  }
  if (!isEdit.value && publishMode.value === 'scheduled') {
    if (!scheduledPublishAt.value) {
      ElMessage.warning('请选择定时发布时间')
      return
    }
    if (new Date(scheduledPublishAt.value) <= new Date()) {
      ElMessage.warning('定时发布时间必须晚于当前时间')
      return
    }
  }
  const payload = buildPayload()
  if (!isEdit.value) {
    payload.scheduledPublishAt = publishMode.value === 'scheduled' ? toIso(scheduledPublishAt.value) : undefined
  }
  saving.value = true
  try {
    if (isEdit.value && props.assignment) {
      const item = props.assignment
      const pastDeadline = item.status === 'PUBLISHED' && Date.now() > new Date(item.deadlineAt).getTime()
      if (pastDeadline) {
        try {
          await ElMessageBox.confirm(
            '作业已截止，修改配置会影响迟交与查重结果，是否继续？',
            '请确认修改影响', { type: 'warning' },
          )
        } catch {
          return
        }
        payload.confirmImpact = true
      }
      // 基础字段更新与查重配置/量规更新分离，避免相互覆盖
      const updated = await assignmentApi.update(item.id, payload, `"row-${item.rowVersion}"`)
      if (form.checkEnabled) {
        await assignmentApi.updateCheckConfig(item.id, buildCheckConfigPayload(), checkConfigEtag.value)
      }
      await assignmentApi.updateRubric(item.id, buildRubricPayload(), rubricEtag.value)
      ElMessage.success('作业配置已更新')
      emit('saved', updated)
    } else {
      const created = await assignmentApi.create(props.courseId, payload)
      if (form.checkEnabled) {
        await assignmentApi.updateCheckConfig(created.id, buildCheckConfigPayload())
      }
      await assignmentApi.updateRubric(created.id, buildRubricPayload())
      ElMessage.success(publishMode.value === 'scheduled'
        ? '作业草稿已创建，到达设定时间后将自动发布'
        : '作业草稿已创建')
      emit('saved', null)
    }
    emit('update:modelValue', false)
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 412) {
      ElMessage.error('作业配置已被他人更新，请关闭后重新打开再试')
    } else {
      ElMessage.error(errorMessage(error))
    }
  } finally {
    saving.value = false
  }
}

watch(() => props.modelValue, async (visible) => {
  if (visible) {
    await loadWhitelistLibraries()
    await fillForm()
  }
})
</script>

<template>
  <el-dialog :model-value="modelValue" :title="title" width="min(680px, 94vw)" destroy-on-close @update:model-value="emit('update:modelValue', $event)">
    <el-form ref="formRef" :model="form" :rules="assignmentRules" label-position="top">
      <el-form-item label="作业标题" prop="title"><el-input v-model="form.title" placeholder="请输入作业标题" /></el-form-item>
      <el-form-item label="作业要求"><el-input v-model="form.description" type="textarea" :rows="4" placeholder="说明任务内容、提交格式与评分要求" /></el-form-item>
      <div class="form-row">
        <el-form-item label="截止时间" prop="deadlineAt"><el-date-picker v-model="form.deadlineAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" style="width: 100%" /></el-form-item>
        <el-form-item label="课程权重（%）"><el-input-number v-model="form.weight" :min="0" :max="100" style="width: 100%" /></el-form-item>
      </div>
      <el-form-item label="提交策略">
        <div class="switch-grid">
          <el-checkbox v-model="form.allowMultiVersion">允许多版本提交</el-checkbox>
          <el-checkbox v-model="form.checkEnabled">启用自动查重</el-checkbox>
          <el-checkbox v-model="form.allowLate">允许补交</el-checkbox>
        </div>
      </el-form-item>
      <el-form-item v-if="form.allowLate" label="补交截止时间"><el-date-picker v-model="form.lateDeadlineAt" type="datetime" value-format="YYYY-MM-DDTHH:mm" style="width: 100%" /></el-form-item>
      <el-form-item v-if="form.allowMultiVersion" label="生效版本规则">
        <el-radio-group v-model="form.effectiveRule">
          <el-radio value="LAST_SUBMISSION">最后一次提交</el-radio>
          <el-radio value="MANUAL">学生手动指定</el-radio>
        </el-radio-group>
      </el-form-item>
      <template v-if="form.checkEnabled">
        <el-form-item label="查重模式">
          <el-radio-group v-model="checkMode">
            <el-radio value="TEXT">文本查重</el-radio>
            <el-radio value="CODE">代码查重</el-radio>
          </el-radio-group>
        </el-form-item>
        <div class="form-row">
          <el-form-item v-if="checkMode === 'TEXT'" label="文本警告阈值（%）"><el-input-number v-model="textWarning" :min="0" :max="100" style="width: 100%" /></el-form-item>
          <el-form-item v-if="checkMode === 'TEXT'" label="文本严重阈值（%）"><el-input-number v-model="textSevere" :min="0" :max="100" style="width: 100%" /></el-form-item>
          <el-form-item v-if="checkMode === 'CODE'" label="代码警告阈值（%）"><el-input-number v-model="structWarning" :min="0" :max="100" style="width: 100%" /></el-form-item>
          <el-form-item v-if="checkMode === 'CODE'" label="代码严重阈值（%）"><el-input-number v-model="structSevere" :min="0" :max="100" style="width: 100%" /></el-form-item>
        </div>
        <el-form-item label="比对库">
          <div class="library-editor">
            <el-checkbox :model-value="classLibraryChecked" disabled>本班库（同学互查，默认开启）</el-checkbox>
            <el-checkbox-group v-model="selectedWhitelistIds" class="lib-group">
              <el-checkbox v-for="lib in whitelistLibraries" :key="lib.id" :value="lib.id" :disabled="!lib.enabled">
                {{ lib.name }}
              </el-checkbox>
            </el-checkbox-group>
            <p v-if="!whitelistLibraries.length" class="type-hint">暂无白名单库，请先在比对库管理创建。</p>
          </div>
        </el-form-item>
        <el-form-item label="排除项">
          <div class="exclude-editor">
            <el-checkbox v-model="excludeReferences">
              <span>排除引用标注</span>
              <small>匹配到学生已规范标注的引用内容时不计入相似度。</small>
            </el-checkbox>
            <el-checkbox v-model="excludeTemplate">
              <span>排除模板/公共代码</span>
              <small>匹配到教师提供的公共模板或脚手架代码时不计入相似度。</small>
            </el-checkbox>
          </div>
        </el-form-item>
      </template>
      <el-form-item label="评分量规">
        <div class="rubric-editor">
          <div v-for="(row, index) in rubricRows" :key="row.key" class="rubric-card">
            <div class="rubric-row">
              <el-input v-model="row.name" placeholder="评分维度名称，如 内容完成度" />
              <el-input-number v-model="row.maxScore" :min="1" :max="100" :precision="0" controls-position="right" />
              <el-button link type="danger" :disabled="rubricRows.length <= 1" @click="removeRubricRow(index)">删除</el-button>
            </div>
            <el-input v-model="row.description" class="rubric-desc" placeholder="维度描述（可选），说明评分要点" />
            <div class="levels-block">
              <div class="levels-head">
                <span>评分等级</span>
                <el-button size="small" :disabled="row.levels.length >= 10" @click="addLevel(row)">＋ 添加等级</el-button>
              </div>
              <div v-for="(level, li) in row.levels" :key="li" class="level-row">
                <el-input v-model="level.label" placeholder="等级名称，如 优秀" />
                <el-input-number v-model="level.score" :min="0" :max="row.maxScore" :precision="0" controls-position="right" />
                <el-button link type="danger" @click="removeLevel(row, li)">删除</el-button>
              </div>
              <p v-if="!row.levels.length" class="muted small">未设置等级时，评审直接按原始分填写。</p>
            </div>
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
      <el-form-item v-if="!isEdit" label="发布方式">
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
    <template #footer><el-button @click="emit('update:modelValue', false)">取消</el-button><el-button type="primary" :loading="saving" @click="save">{{ isEdit ? '保存修改' : '保存草稿' }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.form-row { display: grid; grid-template-columns: 1.2fr .8fr; gap: 14px; }
.switch-grid { display: flex; gap: 20px; flex-wrap: wrap; }
.library-editor { width: 100%; display: grid; gap: 8px; }
.lib-group { display: flex; flex-wrap: wrap; gap: 2px 16px; margin-top: 2px; }
.exclude-editor { width: 100%; display: grid; gap: 8px; }
.exclude-editor :deep(.el-checkbox) { align-items: flex-start; height: auto; white-space: normal; }
.exclude-editor :deep(.el-checkbox__label) { line-height: 1.5; display: grid; gap: 2px; }
.exclude-editor :deep(.el-checkbox__label small) { color: #97a1ac; font-size: 11px; }
.rubric-editor { width: 100%; display: grid; gap: 10px; }
.rubric-card { border: 1px solid #e7eded; border-radius: 10px; padding: 12px 13px; background: #fbfcfc; display: grid; gap: 9px; }
.rubric-row { display: grid; grid-template-columns: 1fr 130px auto; gap: 8px; align-items: center; }
.rubric-desc { width: 100%; }
.levels-block { border-top: 1px dashed #e7eded; padding-top: 9px; display: grid; gap: 7px; }
.levels-head { display: flex; justify-content: space-between; align-items: center; }
.levels-head span { font-size: 11px; color: #7d8996; }
.level-row { display: grid; grid-template-columns: 1fr 130px auto; gap: 8px; align-items: center; }
.rubric-foot { display: flex; justify-content: space-between; align-items: center; }
.rubric-foot span { color: #74818e; font-size: 12px; }
.rubric-foot b { color: #0f766e; font-size: 14px; }
.file-type-editor { width: 100%; }
.type-group { display: flex; flex-wrap: wrap; gap: 2px 14px; margin-top: 8px; }
.type-hint { margin: 8px 0 0; color: #8c97a3; font-size: 11px; line-height: 1.6; }
.publish-mode { width: 100%; display: grid; gap: 10px; }
.muted { color: #97a1ac; }
.small { font-size: 11px; line-height: 1.7; }
@media (max-width: 640px) { .form-row { grid-template-columns: 1fr; } .rubric-row, .level-row { grid-template-columns: 1fr; } }
</style>
