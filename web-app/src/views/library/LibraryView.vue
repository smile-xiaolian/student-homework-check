<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { libraryApi } from '@/api/libraries'
import { courseApi } from '@/api/courses'
import { errorMessage } from '@/api/http'
import type { Course, Library, LibraryEntry } from '@/types/api'

const route = useRoute()
const router = useRouter()
const courseId = String(route.params.id)

const loading = ref(true)
const course = ref<Course | null>(null)
const libraries = ref<Library[]>([])
const expandedIds = ref<string[]>([])
const entriesMap = reactive<Record<string, LibraryEntry[]>>({})
const entriesLoading = reactive<Record<string, boolean>>({})
const createVisible = ref(false)
const createName = ref('')
const creating = ref(false)
const uploadingId = ref('')
const uploadTargetId = ref('')

const libraryAccept = '.pdf,.docx,.doc,.txt,.md,.java,.py,.c,.cpp,.h,.js,.ts,.go,.zip'

function format(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [courseData, libraryData] = await Promise.all([
      courseApi.get(courseId),
      libraryApi.list(courseId),
    ])
    course.value = courseData
    libraries.value = libraryData
  } catch (error) {
    ElMessage.error(errorMessage(error))
    router.replace(`/courses/${courseId}`)
  } finally {
    loading.value = false
  }
}

async function loadEntries(libraryId: string): Promise<void> {
  entriesLoading[libraryId] = true
  try {
    entriesMap[libraryId] = await libraryApi.entries(libraryId)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    entriesLoading[libraryId] = false
  }
}

async function onExpandChange(ids: string[]): Promise<void> {
  for (const id of ids) {
    if (!entriesMap[id]) await loadEntries(id)
  }
}

async function createLibrary(): Promise<void> {
  if (!createName.value.trim()) {
    ElMessage.warning('请输入比对库名称')
    return
  }
  creating.value = true
  try {
    const created = await libraryApi.create(courseId, createName.value.trim())
    ElMessage.success('比对库已创建')
    libraries.value = await libraryApi.list(courseId)
    createVisible.value = false
    createName.value = ''
    expandedIds.value = [...expandedIds.value, created.id]
    await loadEntries(created.id)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    creating.value = false
  }
}

async function removeLibrary(lib: Library): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除比对库「${lib.name}」吗？其中的条目将一并移除。`, '删除比对库', { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' })
    await libraryApi.remove(lib.id)
    ElMessage.success('比对库已删除')
    libraries.value = await libraryApi.list(courseId)
    delete entriesMap[lib.id]
    expandedIds.value = expandedIds.value.filter((id) => id !== lib.id)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

async function uploadEntry(lib: Library, file: File): Promise<void> {
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.error(`${file.name} 超过 20MB 限制`)
    return
  }
  uploadingId.value = lib.id
  try {
    await libraryApi.uploadEntry(lib.id, file)
    ElMessage.success('文件已上传')
    libraries.value = await libraryApi.list(courseId)
    await loadEntries(lib.id)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    uploadingId.value = ''
  }
}

function onBeforeUpload(file: File): boolean {
  const lib = libraries.value.find((item) => item.id === uploadTargetId.value)
  if (lib) uploadEntry(lib, file)
  return false
}

async function removeEntry(lib: Library, entry: LibraryEntry): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定从比对库中删除「${entry.originalName}」吗？`, '删除条目', { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' })
    await libraryApi.removeEntry(lib.id, entry.id)
    ElMessage.success('条目已删除')
    await loadEntries(lib.id)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="breadcrumb">
      <button @click="router.push(`/courses/${courseId}`)">返回课程</button><span>/</span>比对库管理
    </div>

    <el-skeleton :loading="loading" animated :rows="8">
      <template #default>
        <div class="page-head">
          <div>
            <p class="eyebrow">WHITELIST LIBRARIES</p>
            <h1>比对库管理</h1>
            <p>{{ course?.name }} 的白名单比对库，仅用于本次作业相似度比对的辅助来源。</p>
          </div>
          <el-button type="primary" @click="createVisible = true">＋ 新建比对库</el-button>
        </div>

        <section class="surface library-panel">
          <el-collapse v-model="expandedIds" @change="onExpandChange">
            <el-collapse-item v-for="lib in libraries" :key="lib.id" :name="lib.id">
              <template #title>
                <div class="lib-title">
                  <strong>{{ lib.name }}</strong>
                  <el-tag size="small" type="success" effect="plain">白名单库</el-tag>
                  <span class="lib-meta">{{ lib.entryCount }} 个条目 · {{ format(lib.createdAt) }} 创建</span>
                </div>
              </template>
              <div class="lib-body">
                <div class="lib-toolbar">
                  <el-upload :show-file-list="false" :accept="libraryAccept" :before-upload="onBeforeUpload">
                    <el-button size="small" type="primary" plain :loading="uploadingId === lib.id" @click="uploadTargetId = lib.id">上传文件</el-button>
                  </el-upload>
                  <span class="upload-hint">支持 PDF、Word、文本与常见源码文件，单个不超过 20MB。</span>
                  <el-button size="small" type="danger" plain @click="removeLibrary(lib)">删除比对库</el-button>
                </div>
                <el-table v-loading="entriesLoading[lib.id]" :data="entriesMap[lib.id] ?? []" size="small" style="width: 100%">
                  <el-table-column prop="originalName" label="文件名" min-width="230" />
                  <el-table-column label="大小" width="100"><template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template></el-table-column>
                  <el-table-column prop="sequenceNo" label="序号" width="70" />
                  <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" effect="plain" :type="row.status === 'READY' ? 'success' : 'warning'">{{ row.status === 'READY' ? '就绪' : row.status }}</el-tag></template></el-table-column>
                  <el-table-column label="上传时间" width="160"><template #default="{ row }">{{ format(row.createdAt) }}</template></el-table-column>
                  <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="danger" @click="removeEntry(lib, row)">删除</el-button></template></el-table-column>
                </el-table>
                <div v-if="!(entriesMap[lib.id] ?? []).length && !entriesLoading[lib.id]" class="empty-line">暂无条目，上传文件建立本班比对源。</div>
              </div>
            </el-collapse-item>
          </el-collapse>
          <div v-if="!libraries.length && !loading" class="empty-line">该课程还没有比对库，点击右上角创建。</div>
        </section>
      </template>
    </el-skeleton>

    <el-dialog v-model="createVisible" title="新建比对库" width="min(460px, 92vw)">
      <el-form label-position="top">
        <el-form-item label="库名称"><el-input v-model="createName" placeholder="如：本班历届优秀作业" /></el-form-item>
        <el-form-item label="库类型"><el-input :model-value="'WHITELIST'" disabled /></el-form-item>
        <p class="hint">本班库默认启用且不可关闭；库内条目仅作为相似度比对的辅助来源。</p>
      </el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" :loading="creating" @click="createLibrary">创建</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.breadcrumb { display: flex; gap: 9px; align-items: center; color: #93a0ac; font-size: 12px; margin-bottom: 16px; }
.breadcrumb button { border: 0; background: transparent; color: #0f766e; cursor: pointer; padding: 0; font-size: 12px; }
.eyebrow { color: #0f766e; font-size: 11px; font-weight: 800; letter-spacing: .14em; margin-bottom: 9px; }
.library-panel { padding: 8px 22px 22px; }
.lib-title { display: flex; align-items: center; gap: 10px; }
.lib-title strong { font-size: 14px; }
.lib-meta { color: #8b96a2; font-size: 11px; margin-left: auto; }
.lib-body { padding: 6px 6px 4px; }
.lib-toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.upload-hint { color: #8b96a2; font-size: 11px; margin-right: auto; }
.hint { color: #8b96a2; font-size: 11px; line-height: 1.7; margin: 0; }
@media (max-width: 700px) { .lib-meta { display: none; } .lib-toolbar { align-items: flex-start; flex-direction: column; } }
</style>
