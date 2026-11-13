export type Role = 'STUDENT' | 'TEACHER' | 'ASSISTANT' | 'ADMIN'

export interface Pagination {
  page: number
  size: number
  total: number
  totalPages: number
  hasNext: boolean
}

export interface PageResult<T> {
  items: T[]
  pagination: Pagination
}

export interface SessionUser {
  id: string
  userNo: string
  displayName: string
  roles: Role[]
  permissions: string[]
  mustChangePassword: boolean
}

export interface LoginResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
  user: SessionUser
}

export interface Me extends SessionUser {
  loginName: string
  email: string | null
  phone: string | null
  status: string
}

export interface Course {
  id: string
  courseCode: string
  name: string
  semester: string
  ownerId: string
  description: string | null
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
  inviteCode: string | null
  readOnly: boolean
  createdAt: string
  updatedAt: string
  rowVersion: number
}

export interface CourseMember {
  id: string
  userId: string
  userNo: string
  displayName: string
  memberRole: 'STUDENT' | 'ASSISTANT' | 'INSTRUCTOR'
  status: string
  joinedAt: string
}

export interface RubricDimension {
  key: string
  name: string
  description?: string
  maxScore: number
  levels?: Array<{ label: string; score: number }>
}

export interface Assignment {
  id: string
  courseId: string
  title: string
  description: string | null
  status: 'DRAFT' | 'PUBLISHED' | 'CLOSED' | 'ARCHIVED'
  publishAt: string | null
  openAt: string | null
  deadlineAt: string
  lateDeadlineAt: string | null
  allowLate: boolean
  allowMultiVersion: boolean
  effectiveRule: 'LAST_SUBMISSION' | 'MANUAL'
  checkEnabled: boolean
  checkConfig: Record<string, unknown> | null
  rubricDefinition: { dimensions?: RubricDimension[]; totalScore?: number }
  allowedFileTypes?: string[]
  scheduledPublishAt?: string | null
  resultsVisible: boolean
  weight: number
  createdAt: string
  updatedAt: string
  rowVersion: number
}

export interface Submission {
  id: string
  assignmentId: string
  submitterId: string
  submitterName: string
  versionNo: number
  status: string
  effective: boolean
  late: boolean
  submittedAt: string
  effectiveDeadlineAt: string
  onlineText: string | null
  integrityConsent: boolean
  checkTaskId: string | null
  checkTaskStatus: string | null
  files: SubmissionFile[]
  createdAt: string
  rowVersion: number
}

export interface SubmissionFile {
  id: string
  originalName: string
  mimeType: string
  sizeBytes: number
  sha256: string
  scanStatus: string
  createdAt: string
}

export interface CheckTask {
  id: string
  subjectType: 'SUBMISSION' | 'PRECHECK'
  taskType: 'FORMAL' | 'PRECHECK' | 'RECHECK'
  assignmentId: string
  submissionId: string | null
  status: string
  stage: string | null
  progress: number
  reportId: string | null
  reportVersion: number | null
  attempt: number
  maxAttempts: number
  retryAfterSeconds: number
  error: { code?: string; detail?: string; category?: string } | null
  queuedAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface CheckReport {
  id: string
  checkTaskId: string
  submissionId: string | null
  assignmentId: string
  reportVersion: number
  isCurrent: boolean
  overallScore: number
  textScore: number | null
  structureScore: number | null
  level: 'PASS' | 'WARNING' | 'SEVERE' | 'UNKNOWN'
  algorithmVersion: string
  params: Record<string, unknown>
  sourceCount: number
  topSourceType: string | null
  topSourceSimilarity: number | null
  reportDigest: string
  generatedAt: string
  links: Record<string, string>
}

export interface CheckSegment {
  segmentId: string
  pageNo?: number
  section?: string
  originalText: string
  similarity: number
  sourceId?: string
  matchType: string
  highlightRanges: Array<{ start: number; end: number }>
}

export interface CheckSource {
  sourceId: string
  sourceType: string
  similarity: number
  title: string
  submissionId?: string
  studentVisible: boolean
  locator?: string
  matchedChars: number
  excerpt?: string
}

export interface Review {
  id: string | null
  submissionId: string
  reviewerId: string | null
  status: 'DRAFT' | 'SUBMITTED' | 'PUBLISHED' | 'NOT_STARTED'
  rawScore: number | null
  lateDeduction: number
  finalScore: number | null
  dimensionScores: Array<{ criterionId: string; score: number }>
  annotations: Array<{ id: string; text: string; quote?: string }>
  checkVerdict: string | null
  comment: string | null
  submittedAt: string | null
  updatedAt: string | null
  rowVersion: number
}

export interface Grade {
  id: string
  submissionId: string
  submitterName: string
  versionNo: number
  status: string
  rawScore: number | null
  lateDeduction: number
  finalScore: number
  checkVerdict: string | null
  comment: string | null
  published: boolean
  publishedAt: string | null
}

export interface ReviewQueueItem {
  submissionId: string
  submitterId: string
  submitterName: string
  versionNo: number
  submittedAt: string
  late: boolean
  checkScore: number | null
  checkLevel: string | null
  reviewStatus: string
  gradeStatus: string | null
}

export interface NotificationItem {
  id: string
  notificationType: string
  title: string
  content: string
  targetType: string | null
  targetId: string | null
  submissionId?: string | null
  read: boolean
  readAt: string | null
  createdAt: string
}

export interface AdminUser {
  id: string
  userNo: string
  loginName: string
  displayName: string
  email: string | null
  phone: string | null
  roles: Role[]
  status: string
  lastLoginAt: string | null
  createdAt: string
  rowVersion: number
}

export interface SystemConfigItem {
  key: string
  value: unknown
  valueType: string
  group: string
  description: string | null
  version: number
  updatedAt: string
}

export interface SubmissionProgress {
  assignmentId: string
  assignmentTitle: string
  memberCount: number
  submittedCount: number
  lateCount: number
  checkingCount: number
  completedCount: number
  unsubmittedCount: number
  submitRate: number
}

export interface CheckDistribution {
  assignmentId: string
  passCount: number
  warningCount: number
  severeCount: number
  unknownCount: number
  highRisk: Array<{
    submissionId: string
    submitterName: string
    versionNo: number
    overallScore: number
    level: string
  }>
}
