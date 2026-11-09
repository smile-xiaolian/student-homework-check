<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { checkingApi } from '@/api/checking'
import { submissionApi } from '@/api/submissions'
import { errorMessage } from '@/api/http'
import type { CheckReport, CheckSegment, CheckSource, CheckTask, Submission } from '@/types/api'

const route = useRoute()
const router = useRouter()
const submissionId = String(route.params.id)
const loading = ref(true)
const report = ref<CheckReport | null>(null)
const task = ref<CheckTask | null>(null)
const submission = ref<Submission | null>(null)
const segments = ref<CheckSegment[]>([])
const sources = ref<CheckSource[]>([])
let pollTimer: number | null = null

const scoreTone = computed(() => report.value?.level === 'SEVERE' ? 'severe' : report.value?.level === 'WARNING' ? 'warning' : 'pass')
const levelText = computed(() => ({ PASS: '通过', WARNING: '警告', SEVERE: '严重', UNKNOWN: '待确认' } as Record<string, string>)[report.value?.level ?? 'UNKNOWN'])
const taskStage = computed(() => task.value?.stage || ({
  QUEUED: '排队等待中', PREPROCESSING: '正在预处理文本', COMPARING: '正在比对比对库',
  REPORTING: '正在生成报告', SUCCEEDED: '查重完成', FAILED: '查重失败', CANCELLED: '任务已取消',
} as Record<string, string>)[task.value?.status ?? ''] || '等待查重')

function format(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(value))
}

function sourceLabel(type: string): string {
  return ({ CLASS: '本班库', HISTORICAL: '历届库', INTERNET: '互联网', LITERATURE: '文献库', WHITELIST: '白名单' } as Record<string, string>)[type] ?? type
}

function highlightParts(segment: CheckSegment): Array<{ text: string; highlighted: boolean }> {
  const ranges = [...segment.highlightRanges].sort((a, b) => a.start - b.start)
  const parts: Array<{ text: string; highlighted: boolean }> = []
  let cursor = 0
  for (const range of ranges) {
    const start = Math.max(cursor, Math.min(segment.originalText.length, range.start))
    const end = Math.max(start, Math.min(segment.originalText.length, range.end))
    if (start > cursor) parts.push({ text: segment.originalText.slice(cursor, start), highlighted: false })
    if (end > start) parts.push({ text: segment.originalText.slice(start, end), highlighted: true })
    cursor = end
  }
  if (cursor < segment.originalText.length) parts.push({ text: segment.originalText.slice(cursor), highlighted: false })
  return parts
}

async function loadReport(): Promise<void> {
  try {
    report.value = await checkingApi.currentReport(submissionId)
    const [segmentPage, sourcePage] = await Promise.all([
      checkingApi.segments(report.value.id, { size: 100 }),
      checkingApi.sources(report.value.id, { size: 100 }),
    ])
    segments.value = segmentPage.items
    sources.value = sourcePage.items
  } catch {
    report.value = null
  }
  try {
    submission.value = await submissionApi.get(submissionId)
    if (!report.value && submission.value.checkTaskId) {
      task.value = await checkingApi.task(submission.value.checkTaskId)
      schedulePoll()
    }
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function schedulePoll(): void {
  if (pollTimer) window.clearTimeout(pollTimer)
  if (!task.value || ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.value.status)) return
  pollTimer = window.setTimeout(async () => {
    if (!submission.value?.checkTaskId) return
    try {
      task.value = await checkingApi.task(submission.value.checkTaskId)
      if (task.value.status === 'SUCCEEDED') await loadReport()
      else schedulePoll()
    } catch { schedulePoll() }
  }, 3500)
}

async function recheck(): Promise<void> {
  try {
    await checkingApi.recheck(submissionId, '教师复核')
    ElMessage.success('重查任务已创建')
    await loadReport()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

onMounted(async () => {
  loading.value = true
  await loadReport()
  loading.value = false
})
onBeforeUnmount(() => { if (pollTimer) window.clearTimeout(pollTimer) })
</script>

<template>
  <div class="page report-page">
    <div class="breadcrumb"><button @click="router.back()">返回作业</button><span>/</span>原创性检测报告</div>
    <el-skeleton :loading="loading" animated :rows="10">
      <template #default>
        <template v-if="report">
          <section class="report-head" :class="scoreTone">
            <div class="head-copy">
              <p class="eyebrow">SIMILARITY REPORT</p>
              <h1>原创性检测报告</h1>
              <p>报告版本 V{{ report.reportVersion }} · {{ format(report.generatedAt) }} 生成</p>
              <div class="head-meta"><span>算法 {{ report.algorithmVersion }}</span><span>{{ report.sourceCount }} 个相似来源</span><span>摘要 {{ report.reportDigest.slice(0, 12) }}…</span></div>
            </div>
            <div class="score-ring" :style="{ '--score': `${report.overallScore * 3.6}deg` }">
              <div><strong>{{ report.overallScore.toFixed(1) }}</strong><small>总体相似度</small></div>
            </div>
            <div class="level-badge"><span>{{ levelText }}</span><small>阈值判定</small></div>
          </section>

          <div class="summary-grid">
            <div class="surface summary-card"><span>文本相似度</span><b>{{ report.textScore?.toFixed(1) ?? '—' }}<small>分</small></b><i><em :style="{ width: `${report.textScore ?? 0}%` }" /></i></div>
            <div class="surface summary-card"><span>结构相似度</span><b>{{ report.structureScore?.toFixed(1) ?? '—' }}<small>分</small></b><i><em :style="{ width: `${report.structureScore ?? 0}%` }" /></i></div>
            <div class="surface summary-card"><span>最高来源相似度</span><b>{{ report.topSourceSimilarity?.toFixed(1) ?? '—' }}<small>分</small></b><i><em :style="{ width: `${report.topSourceSimilarity ?? 0}%` }" /></i></div>
            <div class="surface summary-card"><span>主要来源</span><b class="text-value">{{ sourceLabel(report.topSourceType ?? '—') }}</b><small class="note">仅作辅助提示，最终判定以教师确认为准。</small></div>
          </div>

          <div class="split-grid report-grid">
            <section class="surface surface-pad">
              <div class="section-head"><div><h2>分段比对</h2><p>高亮部分表示与比对源存在相似内容。</p></div><el-button v-if="report" size="small" @click="recheck">申请重查</el-button></div>
              <div v-if="segments.length" class="segment-list">
                <article v-for="segment in segments" :key="segment.segmentId" class="segment">
                  <header><span>{{ segment.section || '正文片段' }}<small v-if="segment.pageNo">第 {{ segment.pageNo }} 页</small></span><b>{{ segment.similarity.toFixed(1) }}%</b></header>
                  <p><template v-for="(part, index) in highlightParts(segment)" :key="index"><mark v-if="part.highlighted">{{ part.text }}</mark><template v-else>{{ part.text }}</template></template></p>
                </article>
              </div>
              <div v-else class="empty-line">该报告没有需要高亮的分段。</div>
            </section>
            <aside class="surface surface-pad source-panel">
              <div class="section-head"><div><h2>相似来源</h2><p>按相似度由高到低排列。</p></div></div>
              <div v-if="sources.length" class="source-list">
                <div v-for="source in sources" :key="source.sourceId" class="source-item">
                  <div class="source-top"><span>{{ sourceLabel(source.sourceType) }}</span><b>{{ source.similarity.toFixed(1) }}%</b></div>
                  <strong>{{ source.title }}</strong>
                  <small>{{ source.locator || `匹配 ${source.matchedChars} 字` }}</small>
                  <p v-if="source.excerpt">{{ source.excerpt }}</p>
                </div>
              </div>
              <div v-else class="empty-line">暂未发现相似来源。</div>
            </aside>
          </div>
          <p class="disclaimer">本报告由自动算法生成，相似度仅作为原创性辅助判断依据，不直接等同于抄袭结论。报告生成后业务字段不可修改；如对结果有异议，可向教师申请复核。</p>
        </template>
        <template v-else-if="task">
          <section class="pending-panel surface">
            <div class="pulse-ring"><span>{{ task.progress || 0 }}%</span></div>
            <p class="eyebrow">CHECKING IN PROGRESS</p>
            <h1>{{ taskStage }}</h1>
            <p>{{ task.status === 'FAILED' ? (task.error?.detail || '查重任务执行失败，系统将自动重试。') : '报告生成后会自动刷新，你也可以稍后返回此页查看。' }}</p>
            <el-progress :percentage="task.progress || 0" :status="task.status === 'FAILED' ? 'exception' : undefined" :stroke-width="7" />
            <div v-if="task.status === 'FAILED'" class="pending-actions"><el-button type="primary" @click="recheck">申请重新查重</el-button></div>
          </section>
        </template>
        <section v-else class="surface pending-panel">
          <div class="empty-icon">◎</div><h1>暂无查重报告</h1>
          <p>该提交可能未开启查重，或报告尚未生成。</p>
          <el-button @click="router.back()">返回作业</el-button>
        </section>
      </template>
    </el-skeleton>
  </div>
</template>

<style scoped>
.breadcrumb { display: flex; gap: 9px; color: #93a0ac; font-size: 12px; margin-bottom: 16px; }
.breadcrumb button { border: 0; background: transparent; color: #0f766e; font-size: 12px; padding: 0; cursor: pointer; }
.report-head { position: relative; overflow: hidden; display: flex; align-items: center; gap: 38px; border-radius: 23px; padding: 32px 38px; background: #113d39; color: white; margin-bottom: 18px; min-height: 225px; }
.report-head.warning { background: linear-gradient(125deg, #4b3c14, #73591e); }
.report-head.severe { background: linear-gradient(125deg, #4c1d1b, #7d2b27); }
.report-head::after { content: ''; position: absolute; width: 520px; height: 520px; border-radius: 50%; border: 1px solid rgba(255,255,255,.09); right: -260px; top: -270px; }
.head-copy { flex: 1; position: relative; z-index: 1; }
.eyebrow { color: #99d5cd; font-size: 10px; font-weight: 800; letter-spacing: .17em; margin-bottom: 9px; }
.head-copy h1 { font-size: clamp(28px, 4vw, 40px); margin-bottom: 9px; letter-spacing: -.04em; }
.head-copy > p:not(.eyebrow) { color: #b8d9d4; font-size: 12px; margin-bottom: 19px; }
.head-meta { display: flex; gap: 16px; flex-wrap: wrap; color: #8fc5bd; font-size: 10px; }
.score-ring { --score: 0deg; position: relative; z-index: 1; width: 148px; height: 148px; flex: 0 0 148px; border-radius: 50%; display: grid; place-content: center; background: conic-gradient(#f4c95d var(--score), rgba(255,255,255,.12) 0); }
.score-ring::before { content: ''; position: absolute; inset: 9px; border-radius: 50%; background: #113d39; }
.warning .score-ring::before { background: #5c4818; }
.severe .score-ring::before { background: #591f1d; }
.score-ring div { position: relative; z-index: 1; text-align: center; }
.score-ring strong { display: block; font-size: 40px; letter-spacing: -.07em; line-height: 1; }
.score-ring small { display: block; color: #a9cec8; font-size: 10px; margin-top: 8px; }
.level-badge { position: relative; z-index: 1; align-self: flex-start; border: 1px solid rgba(255,255,255,.2); border-radius: 10px; padding: 9px 11px; text-align: center; }
.level-badge span { display: block; font-size: 13px; font-weight: 800; color: #f4c95d; }
.level-badge small { display: block; color: #a9cec8; font-size: 9px; margin-top: 2px; }
.summary-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 13px; margin-bottom: 18px; }
.summary-card { padding: 17px 19px; }
.summary-card > span { color: #7d8996; font-size: 11px; }
.summary-card > b { display: block; font-size: 28px; letter-spacing: -.05em; margin: 6px 0 11px; }
.summary-card > b small { font-size: 11px; font-weight: 400; color: #8994a1; margin-left: 3px; }
.summary-card > b.text-value { font-size: 21px; margin-top: 13px; }
.summary-card > i { display: block; height: 4px; border-radius: 4px; background: #edf2f1; overflow: hidden; }
.summary-card > i em { display: block; height: 100%; background: #28a98b; border-radius: 4px; }
.summary-card > small.note { color: #9ba4ae; font-size: 9px; line-height: 1.5; }
.report-grid { align-items: start; }
.section-head { display: flex; justify-content: space-between; gap: 14px; align-items: start; margin-bottom: 16px; }
.section-head h2 { font-size: 18px; margin: 0 0 4px; }
.section-head p { margin: 0; color: #8a95a1; font-size: 11px; }
.segment-list { display: flex; flex-direction: column; gap: 12px; }
.segment { border: 1px solid #e6ecec; border-radius: 11px; padding: 13px 15px; background: #fcfdfd; }
.segment header { display: flex; justify-content: space-between; color: #667481; font-size: 10px; margin-bottom: 8px; }
.segment header small { margin-left: 7px; color: #9ba5ae; }
.segment header b { color: #b45309; }
.segment p { margin: 0; line-height: 1.9; font-size: 12px; color: #46535f; white-space: pre-wrap; }
mark { background: #fee7a6; color: inherit; padding: 1px 0; border-radius: 2px; }
.source-panel { padding-bottom: 12px; }
.source-list { display: flex; flex-direction: column; }
.source-item { border-top: 1px solid #edf1f2; padding: 13px 0; }
.source-item:first-child { border-top: 0; padding-top: 2px; }
.source-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 5px; }
.source-top span { font-size: 10px; color: #0f766e; background: #e7f6f2; padding: 3px 6px; border-radius: 4px; }
.source-top b { color: #a2570b; font-size: 12px; }
.source-item > strong, .source-item > small { display: block; }
.source-item > strong { font-size: 12px; margin-bottom: 4px; }
.source-item > small { color: #929ca6; font-size: 10px; }
.source-item p { margin: 7px 0 0; color: #737f8b; font-size: 10px; line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.disclaimer { color: #929da7; font-size: 10px; line-height: 1.7; text-align: center; padding: 18px 12px 0; }
.pending-panel { text-align: center; padding: 58px 24px; }
.pulse-ring { width: 112px; height: 112px; border-radius: 50%; margin: 0 auto 20px; display: grid; place-items: center; border: 7px solid #d8f2ec; border-top-color: #0f766e; animation: spin 2s linear infinite; }
.pulse-ring span { font-size: 18px; font-weight: 800; color: #0f766e; animation: reverse-spin 2s linear infinite; }
.pending-panel h1 { font-size: 28px; margin-bottom: 9px; letter-spacing: -.03em; }
.pending-panel > p:not(.eyebrow) { color: #81909b; font-size: 12px; max-width: 510px; margin: 0 auto 24px; line-height: 1.7; }
.pending-panel :deep(.el-progress) { max-width: 430px; margin: 0 auto; }
.pending-actions { margin-top: 22px; }
.empty-icon { font-size: 45px; color: #b4c4c0; margin-bottom: 10px; }
@keyframes spin { to { transform: rotate(360deg); } }
@keyframes reverse-spin { to { transform: rotate(-360deg); } }
@media (max-width: 1050px) { .summary-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 720px) { .report-head { flex-wrap: wrap; gap: 20px; padding: 25px; } .head-copy { flex-basis: 100%; } .score-ring { width: 112px; height: 112px; flex-basis: 112px; } .score-ring strong { font-size: 31px; } .summary-grid { grid-template-columns: 1fr 1fr; } }
</style>
