<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api/admin'
import { errorMessage } from '@/api/http'
import type { SystemConfigItem } from '@/types/api'

const loading = ref(true)
const saving = ref(false)
const configs = ref<SystemConfigItem[]>([])
const monitoring = ref<Record<string, unknown>>({})
const audits = ref<Array<Record<string, unknown>>>([])
const editing = ref<Record<string, string>>({})
const activeTab = ref('config')

const groups = computed(() => {
  const map = new Map<string, SystemConfigItem[]>()
  for (const item of configs.value) {
    const list = map.get(item.group) ?? []
    list.push(item)
    map.set(item.group, list)
  }
  return [...map.entries()]
})

const metrics = computed(() => {
  const value = monitoring.value
  return [
    { label: '服务状态', value: String(value.status ?? 'UP') },
    { label: '用户总数', value: String(value.userCount ?? '—') },
    { label: '课程总数', value: String(value.courseCount ?? '—') },
    { label: '查重队列', value: String(value.queuedCheckTasks ?? '—') },
    { label: '运行中任务', value: String(value.runningCheckTasks ?? '—') },
    { label: '今日报告', value: String(value.reportsToday ?? '—') },
  ]
})

function groupLabel(group: string): string {
  return ({ security: '安全策略', checking: '查重参数', submission: '提交规则', storage: '文件存储', course: '课程规则', system: '系统开关' } as Record<string, string>)[group] ?? group
}

function displayValue(item: SystemConfigItem): string {
  if (typeof item.value === 'string') return item.value
  return JSON.stringify(item.value)
}

function parseValue(item: SystemConfigItem): unknown {
  const raw = editing.value[item.key]
  if (item.valueType === 'BOOLEAN') return raw === 'true'
  if (item.valueType === 'INTEGER') return Number.parseInt(raw, 10)
  if (item.valueType === 'DECIMAL') return Number(raw)
  if (item.valueType === 'OBJECT' || item.valueType === 'ARRAY') {
    try { return JSON.parse(raw) } catch { throw new Error(`${item.key} 不是合法 JSON`) }
  }
  return raw
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [configResult, monitoringResult, auditResult] = await Promise.all([
      adminApi.config(), adminApi.monitoring(), adminApi.audits({ page: 1, size: 20 }),
    ])
    configs.value = configResult.items
    monitoring.value = monitoringResult
    audits.value = auditResult.items
    for (const item of configs.value) editing.value[item.key] = displayValue(item)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function saveConfig(): Promise<void> {
  saving.value = true
  try {
    const items = configs.value.map((item) => ({ key: item.key, value: parseValue(item), version: item.version }))
    configs.value = (await adminApi.updateConfig(items)).items
    for (const item of configs.value) editing.value[item.key] = displayValue(item)
    ElMessage.success('系统参数已保存')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head"><div><p class="eyebrow">SYSTEM OPERATIONS</p><h1>系统管理</h1><p>全局参数、运行状态与审计留痕。</p></div><el-button @click="load">刷新</el-button></div>
    <el-skeleton :loading="loading" animated :rows="10">
      <template #default>
        <div class="metric-grid">
          <div v-for="metric in metrics" :key="metric.label" class="surface metric"><span>{{ metric.label }}</span><strong>{{ metric.value }}</strong></div>
        </div>
        <section class="surface admin-panel">
          <el-tabs v-model="activeTab">
            <el-tab-pane label="全局参数" name="config">
              <div class="panel-head"><div><h2>系统参数</h2><p>参数保存后立即生效；部分参数由环境变量在启动时覆盖。</p></div><el-button type="primary" :loading="saving" @click="saveConfig">保存修改</el-button></div>
              <div v-if="groups.length" class="config-groups">
                <section v-for="[group, items] in groups" :key="group" class="config-group">
                  <h3>{{ groupLabel(group) }}</h3>
                  <div v-for="item in items" :key="item.key" class="config-row">
                    <div><code>{{ item.key }}</code><small>{{ item.description || '暂无说明' }}</small></div>
                    <el-switch v-if="item.valueType === 'BOOLEAN'" :model-value="editing[item.key] === 'true'" @change="editing[item.key] = String($event)" />
                    <el-input v-else v-model="editing[item.key]" class="config-input" />
                    <span class="version">v{{ item.version }}</span>
                  </div>
                </section>
              </div>
              <div v-else class="empty-line">暂无系统参数</div>
            </el-tab-pane>
            <el-tab-pane label="审计日志" name="audit">
              <div class="panel-head"><div><h2>审计日志</h2><p>关键管理操作与权限变更均会留下不可修改的记录。</p></div></div>
              <el-table :data="audits" style="width: 100%">
                <el-table-column prop="action" label="操作" min-width="160" />
                <el-table-column prop="targetType" label="对象类型" width="130" />
                <el-table-column prop="result" label="结果" width="100" />
                <el-table-column prop="traceId" label="追踪号" min-width="190" />
                <el-table-column prop="createdAt" label="时间" width="175" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="运行说明" name="ops">
              <div class="ops-grid">
                <article><span>01</span><h3>健康检查</h3><p>服务就绪探针：<code>/actuator/health/readiness</code>，存活探针：<code>/actuator/health/liveness</code>。</p></article>
                <article><span>02</span><h3>指标采集</h3><p>Prometheus 抓取地址：<code>/actuator/prometheus</code>，包含 HTTP、JVM 与查重队列指标。</p></article>
                <article><span>03</span><h3>接口契约</h3><p>OpenAPI 文档：<code>/swagger-ui.html</code>，JSON 契约：<code>/v3/api-docs</code>。</p></article>
                <article><span>04</span><h3>对象存储</h3><p>MinIO 控制台默认位于 <code>http://localhost:9001</code>，业务文件桶为 <code>edu-submission</code>。</p></article>
              </div>
            </el-tab-pane>
          </el-tabs>
        </section>
      </template>
    </el-skeleton>
  </div>
</template>

<style scoped>
.eyebrow { color: #0f766e; font-size: 11px; font-weight: 800; letter-spacing: .14em; margin-bottom: 9px; }
.metric-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 11px; margin-bottom: 17px; }
.metric { padding: 14px 16px; }
.metric span, .metric strong { display: block; }
.metric span { color: #87929f; font-size: 10px; margin-bottom: 8px; }
.metric strong { font-size: 19px; letter-spacing: -.03em; }
.admin-panel { padding: 12px 22px 24px; }
.panel-head { display: flex; justify-content: space-between; align-items: start; gap: 16px; padding: 8px 0 17px; }
.panel-head h2 { font-size: 18px; margin-bottom: 4px; }
.panel-head p { color: #8a95a1; font-size: 11px; margin: 0; }
.config-group { border-top: 1px solid #edf1f2; padding: 17px 0 4px; }
.config-group h3 { color: #43525e; font-size: 12px; margin-bottom: 7px; }
.config-row { display: grid; grid-template-columns: minmax(250px, 1fr) minmax(200px, .65fr) 40px; gap: 16px; align-items: center; padding: 9px 0; }
.config-row code { display: block; font-size: 11px; color: #17665e; margin-bottom: 3px; }
.config-row small { display: block; color: #9aa4ae; font-size: 9px; line-height: 1.4; }
.config-input { max-width: 480px; }
.version { color: #a0a9b2; font-family: Consolas, monospace; font-size: 9px; text-align: right; }
.ops-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; padding-top: 5px; }
.ops-grid article { border: 1px solid #e7ecec; background: #fafcfc; border-radius: 12px; padding: 17px; }
.ops-grid span { color: #0f766e; font-size: 10px; font-weight: 800; }
.ops-grid h3 { font-size: 14px; margin: 7px 0; }
.ops-grid p { color: #7b8793; font-size: 10px; line-height: 1.7; margin: 0; }
.ops-grid code { background: #edf4f3; padding: 1px 4px; border-radius: 3px; color: #35645f; }
@media (max-width: 1100px) { .metric-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 700px) { .metric-grid { grid-template-columns: repeat(2, 1fr); } .config-row { grid-template-columns: 1fr; gap: 7px; } .config-input { max-width: none; } .version { text-align: left; } .ops-grid { grid-template-columns: 1fr; } }
</style>
