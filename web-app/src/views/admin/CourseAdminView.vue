<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { courseApi } from '@/api/courses'
import { errorMessage } from '@/api/http'
import type { Assignment, Course } from '@/types/api'

const router = useRouter()
const loading = ref(false)
const allCourses = ref<Course[]>([])
const query = reactive({ keyword: '', semester: '', status: '' })
const pageSize = 20
const page = ref(1)

const statusLabel: Record<string, string> = { ACTIVE: '进行中', ARCHIVED: '已归档', DRAFT: '草稿' }
const assignmentStatusLabel: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', CLOSED: '已关闭', ARCHIVED: '已归档' }

const filtered = computed(() => {
  const kw = query.keyword.trim().toLowerCase()
  const semester = query.semester.trim().toLowerCase()
  return allCourses.value.filter((course) => {
    if (kw && !course.name.toLowerCase().includes(kw) && !course.courseCode.toLowerCase().includes(kw)) return false
    if (semester && !course.semester.toLowerCase().includes(semester)) return false
    if (query.status && course.status !== query.status) return false
    return true
  })
})

const total = computed(() => filtered.value.length)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const pageCourses = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))

watch(total, () => {
  if (page.value > totalPages.value) page.value = totalPages.value
})

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    const collected: Course[] = []
    let currentPage = 1
    for (;;) {
      const result = await courseApi.list(currentPage, 100)
      collected.push(...result.items)
      if (!result.pagination.hasNext) break
      currentPage++
    }
    allCourses.value = collected
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function resetPage(): void {
  page.value = 1
}

function onPageChange(next: number): void {
  page.value = next
}

/** 查看某课程的作业列表 */
const assignmentVisible = ref(false)
const assignmentLoading = ref(false)
const assignmentCourse = ref<Course | null>(null)
const assignmentItems = ref<Assignment[]>([])

async function openAssignments(course: Course): Promise<void> {
  assignmentCourse.value = course
  assignmentItems.value = []
  assignmentVisible.value = true
  assignmentLoading.value = true
  try {
    assignmentItems.value = await courseApi.assignments(course.id)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    assignmentLoading.value = false
  }
}

function assignmentStatusType(status: string): 'success' | 'warning' | 'info' | 'primary' {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'CLOSED') return 'warning'
  return 'info'
}

async function archiveCourse(course: Course): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定强制归档课程「${course.name}」吗？归档后课程及其作业、提交、成绩将变为只读。`,
      '强制归档', { type: 'warning', confirmButtonText: '归档', confirmButtonClass: 'el-button--danger' },
    )
    await courseApi.archive(course.id)
    ElMessage.success('课程已归档')
    await loadAll()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}

function format(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value))
}

function formatDateTime(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

onMounted(loadAll)
</script>

<template>
  <div class="page">
    <div class="page-head"><div><p class="eyebrow">ADMINISTRATION</p><h1>课程与作业管理</h1><p>查看全部课程，维护课程状态并查看课程作业。</p></div>
      <div class="head-actions">
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </div>
    <section class="surface">
      <div class="filter-bar">
        <el-input v-model="query.keyword" placeholder="课程名称 / 课程代码" clearable style="max-width: 250px" @keyup.enter="resetPage" />
        <el-input v-model="query.semester" placeholder="学期，如 2026-FALL" clearable style="max-width: 170px" @keyup.enter="resetPage" />
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 125px" @change="resetPage">
          <el-option v-for="(label, status) in statusLabel" :key="status" :label="label" :value="status" />
        </el-select>
        <el-button @click="resetPage">查询</el-button><span class="total">共 {{ total }} 门课程</span>
      </div>
      <el-table v-loading="loading" :data="pageCourses" style="width: 100%">
        <el-table-column prop="courseCode" label="课程代码" width="140" />
        <el-table-column prop="name" label="课程名称" min-width="180" />
        <el-table-column prop="semester" label="学期" width="130" />
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag size="small" effect="plain" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ statusLabel[row.status] ?? row.status }}</el-tag></template></el-table-column>
        <el-table-column label="创建时间" width="130"><template #default="{ row }">{{ format(row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAssignments(row)">查看作业</el-button>
            <el-button v-if="row.status === 'ACTIVE'" link type="warning" @click="archiveCourse(row)">强制归档</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="totalPages > 1" v-model:current-page="page" :page-size="pageSize" :total="total"
                     layout="prev, pager, next" class="pager" @current-change="onPageChange" />
    </section>

    <el-dialog v-model="assignmentVisible" :title="`课程作业 · ${assignmentCourse?.name ?? ''}`" width="min(720px, 94vw)" destroy-on-close>
      <div v-loading="assignmentLoading" class="assignment-body">
        <div v-if="assignmentItems.length" class="assignment-list">
          <div v-for="item in assignmentItems" :key="item.id" class="assignment-row" @click="router.push(`/assignments/${item.id}`)">
            <div class="assignment-main">
              <strong>{{ item.title }}</strong>
              <small>截止 {{ formatDateTime(item.deadlineAt) }} · 权重 {{ item.weight }}%{{ item.checkEnabled ? ' · 查重' : '' }}</small>
            </div>
            <el-tag size="small" effect="plain" :type="assignmentStatusType(item.status)">{{ assignmentStatusLabel[item.status] ?? item.status }}</el-tag>
            <el-button link type="primary" @click.stop="router.push(`/assignments/${item.id}`)">查看</el-button>
          </div>
        </div>
        <el-empty v-else description="该课程暂无作业" />
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.eyebrow { color: #0f766e; font-size: 11px; font-weight: 800; letter-spacing: .14em; margin-bottom: 9px; }
.head-actions { display: flex; gap: 9px; align-items: center; flex-wrap: wrap; }
.surface { overflow: hidden; }
.filter-bar { display: flex; gap: 9px; align-items: center; padding: 17px 19px; flex-wrap: wrap; border-bottom: 1px solid #edf1f2; }
.total { margin-left: auto; color: #909ba5; font-size: 10px; }
.pager { justify-content: center; padding: 16px; }
.assignment-body { min-height: 80px; }
.assignment-list { display: grid; gap: 8px; }
.assignment-row { display: flex; align-items: center; gap: 12px; border: 1px solid #e7eded; border-radius: 9px; padding: 11px 13px; background: #fbfcfc; cursor: pointer; }
.assignment-row:hover { border-color: #c5e3dc; background: #f4faf8; }
.assignment-main { flex: 1; min-width: 0; }
.assignment-main strong, .assignment-main small { display: block; }
.assignment-main strong { font-size: 13px; color: #354652; }
.assignment-main small { font-size: 11px; color: #97a1ac; margin-top: 3px; }
</style>
