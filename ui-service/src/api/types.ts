/**
 * 后端接口数据模型定义。
 *
 * 说明（基于对真实接口的 curl 验证）：
 * 1. 后端 Jackson 配置为「不输出 null 字段」，因此大量字段在实际响应中会缺失，
 *    统一声明为可选（`?`）。前端展示时需做兜底（比如 `?? '-'`）。
 * 2. 后端雪花算法主键（申请 id / 订单 id / 审计 id）长度达到 19 位，超过 JS
 *    Number.MAX_SAFE_INTEGER，直接 JSON.parse 会丢精度（实测
 *    2110070374499643393 → 2110070374499643400）。因此这些 id 在 http.ts 的
 *    transformResponse 中被还原成字符串，类型上统一定义为 `LongId = string`。
 * 3. 业务小 id（区域 1001 / 公寓 2001 / 房间 3001 / 床位 4001 / 用户 9001）在
 *    安全整数范围内，保持 number。
 */

/** 雪花主键，前端以字符串承载避免精度丢失 */
export type LongId = string

/** 统一响应包装 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
  traceId?: string
  success: boolean
}

/** 分页结果 */
export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
  pages: number
}

/** 通用分页查询参数 */
export interface PageQuery {
  current?: number
  size?: number
}

/* ------------------------------------------------------------------ 认证 */

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginVO {
  token: string
  tokenType: string
  expiresInSeconds: number
  userId: number
  username: string
  realName: string
  role: UserRole
  roleLabel: string
  regionId?: number
  regionName?: string
  employeeNo?: string
  department?: string
  sessionId?: string
}

/** 用户信息；`/api/auth/me` 的 phoneMask 为脱敏值，`/api/auth/me/profile` 为明文 */
export interface UserVO {
  id: number
  username: string
  realName: string
  employeeNo?: string
  role: UserRole
  roleLabel: string
  regionId?: number
  regionName?: string
  department?: string
  /** 脱敏手机号（如 138****8888）；仅 /me/profile 返回明文 */
  phoneMask?: string
  email?: string
  status: number
  lastLoginTime?: string
  createTime?: string
}

export type UserRole = 'SUPER_ADMIN' | 'REGION_ADMIN' | 'EMPLOYEE'

export interface ProfileUpdateRequest {
  realName: string
  department?: string
  email?: string
  phone?: string
}

export interface PasswordUpdateRequest {
  oldPassword: string
  newPassword: string
}

/* ------------------------------------------------------------------ 区域 */

export interface RegionVO {
  id: number
  code: string
  name: string
  nameEn?: string
  country?: string
  countryCode?: string
  timezone?: string
  currency?: string
  managerId?: number
  managerName?: string
  /** 1 启用 0 停用 */
  status: number
  remark?: string
  roomCount?: number
  bedCount?: number
  idleBedCount?: number
}

export interface RegionOption {
  id: number
  code: string
  name: string
  country?: string
  currency?: string
}

export interface RegionSaveRequest {
  id?: number
  code: string
  name: string
  nameEn?: string
  country?: string
  countryCode?: string
  timezone?: string
  currency?: string
  managerId?: number
  status: number
  remark?: string
}

/* ------------------------------------------------------------------ 公寓 */

export interface BuildingVO {
  id: number
  regionId: number
  regionName?: string
  code: string
  name: string
  country?: string
  city?: string
  address?: string
  contactName?: string
  contactPhone?: string
  /** 1 启用 0 停用 */
  status: number
  remark?: string
  roomCount?: number
  bedCount?: number
  idleBedCount?: number
}

export interface BuildingOption {
  id: number
  regionId: number
  code: string
  name: string
  city?: string
  address?: string
}

export interface BuildingSaveRequest {
  id?: number
  regionId: number
  code: string
  name: string
  country?: string
  city?: string
  address?: string
  contactName?: string
  contactPhone?: string
  status: number
  remark?: string
}

export interface BuildingQuery extends PageQuery {
  keyword?: string
  regionId?: number
  status?: number
}

/* ------------------------------------------------------------ 房间与床位 */

export type RoomStatus = 'OPEN' | 'CLOSED' | 'MAINTENANCE'
export type RoomType = 'SINGLE' | 'DOUBLE' | 'TRIPLE' | 'QUAD'
export type GenderLimit = 'ANY' | 'MALE' | 'FEMALE'
export type BedStatus = 'IDLE' | 'OCCUPIED' | 'DISABLED'

export interface BedVO {
  id: number
  bedNo: string
  status: BedStatus
  statusLabel?: string
  currentApplyId?: LongId
}

export interface RoomVO {
  id: number
  buildingId: number
  buildingName?: string
  regionId: number
  regionName?: string
  roomNo: string
  floorNo?: number
  roomType: RoomType
  roomTypeLabel?: string
  capacity: number
  genderLimit: GenderLimit
  genderLimitLabel?: string
  status: RoomStatus
  statusLabel?: string
  areaSqm?: number
  pricePerNight?: number
  currency?: string
  /** 列表接口返回字符串数组 */
  facilities?: string[]
  remark?: string
  idleBeds?: number
  occupiedBeds?: number
  disabledBeds?: number
  /** 仅详情接口 /api/rooms/{id} 返回 */
  beds?: BedVO[]
}

export interface RoomQuery extends PageQuery {
  keyword?: string
  buildingId?: number
  regionId?: number
  roomType?: RoomType
  status?: RoomStatus
}

/** 房间可订查询结果行；注意 facilities 是「逗号分隔字符串」，与 RoomVO 的数组不同 */
export interface RoomAvailabilityRow {
  roomId: number
  buildingId: number
  buildingName?: string
  buildingCode?: string
  regionId: number
  regionName?: string
  city?: string
  address?: string
  roomNo: string
  floorNo?: number
  roomType: RoomType
  roomTypeLabel?: string
  capacity: number
  genderLimit: GenderLimit
  status: RoomStatus
  pricePerNight?: number
  currency?: string
  facilities?: string
  remark?: string
  idleBeds?: number
  availableBeds?: number
  totalBeds?: number
  nights?: number
  estimatedAmount?: number
}

export interface RoomAvailabilityQuery {
  regionId?: number
  buildingId?: number
  city?: string
  roomType?: RoomType
  gender?: GenderLimit
  checkInDate?: string
  checkOutDate?: string
  minBeds?: number
}

/** 床位占用明细（含占用区间），用于房源查询详情 */
export interface RoomBedDetail {
  bedId: number
  bedNo: string
  status: BedStatus
  availableForRange?: boolean
  applyId?: LongId
  applyNo?: string
  employeeName?: string
  occupiedCheckIn?: string
  occupiedCheckOut?: string
}

export interface RoomSaveRequest {
  id?: number
  buildingId: number
  roomNo: string
  floorNo?: number
  roomType: RoomType
  genderLimit: GenderLimit
  areaSqm?: number
  pricePerNight?: number
  currency?: string
  /** 逗号分隔字符串 */
  facilities?: string
  remark?: string
}

export interface RoomBatchStatusRequest {
  roomIds: number[]
  status: RoomStatus
  remark?: string
}

export interface BatchFailure {
  roomId: number
  roomNo?: string
  reason?: string
}

export interface BatchResult {
  successCount: number
  failCount: number
  failures?: BatchFailure[]
}

export interface BedBatchStatusRequest {
  bedIds: number[]
  status: Extract<BedStatus, 'IDLE' | 'DISABLED'>
}

/* ------------------------------------------------------------ 入住申请 */

export type ApplyStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'CHECKED_IN'
  | 'CHECKED_OUT'
  | 'CANCELLED'
  | 'EXPIRED'

export interface TimelineItem {
  node: string
  label: string
  color?: string
  time?: string
  operator?: string
  remark?: string
}

export interface ApplyVO {
  /** 雪花 id，字符串承载 */
  id: LongId
  applyNo: string
  employeeId?: number
  employeeName?: string
  employeeNo?: string
  regionId?: number
  regionName?: string
  destCountry?: string
  destCity?: string
  buildingId?: number
  buildingName?: string
  preferredRoomType?: RoomType
  checkInDate?: string
  checkOutDate?: string
  nights?: number
  tripNo?: string
  reason?: string
  status: ApplyStatus
  statusLabel?: string
  statusColor?: string
  roomId?: number
  roomNo?: string
  bedId?: number
  bedNo?: string
  assignTime?: string
  approverId?: number
  approverName?: string
  approveTime?: string
  approveRemark?: string
  rejectReason?: string
  checkInTime?: string
  checkOutTime?: string
  expireTime?: string
  applySource?: 'WEB' | 'AGENT'
  applySourceLabel?: string
  currency?: string
  pricePerNight?: number
  createTime?: string
  /** 后端下发的可执行动作，按钮直接由此驱动 */
  allowedActions?: string[]
  timeline?: TimelineItem[]
  orderNo?: string
}

export interface ApplyCreateRequest {
  regionId: number
  destCountry?: string
  destCity?: string
  buildingId?: number
  preferredRoomType?: RoomType
  checkInDate: string
  checkOutDate: string
  tripNo?: string
  reason?: string
  /** 幂等键，前端生成，防重复提交 */
  idempotencyKey: string
}

export interface ApplyQuery extends PageQuery {
  status?: ApplyStatus
  regionId?: number
  employeeId?: number
  keyword?: string
  from?: string
  to?: string
}

export interface ApplyApproveRequest {
  applyId: LongId
  roomId: number
  bedId: number
  remark?: string
}

export interface ApplyRejectRequest {
  applyId: LongId
  reason: string
}

export interface ApplyCancelRequest {
  applyId: LongId
  reason?: string
}

export interface ApplyCheckInRequest {
  applyId: LongId
  remark?: string
}

export interface ApplyCheckOutRequest {
  applyId?: LongId
  orderId?: LongId
  earlyCheckOutReason?: string
  remark?: string
}

export interface ApplyReassignRequest {
  applyId: LongId
  bedId: number
  reason?: string
}

/** 申请统计：键为状态枚举，额外含 TOTAL */
export type ApplyStatistics = Record<string, number>

/* ------------------------------------------------------------ 订单 */

export type OrderStatus = 'RESERVED' | 'CHECKED_IN' | 'CHECKED_OUT' | 'CANCELLED'

export interface OrderVO {
  /** 雪花 id，字符串承载 */
  id: LongId
  orderNo: string
  applyId?: LongId
  applyNo?: string
  employeeId?: number
  employeeName?: string
  regionId?: number
  regionName?: string
  buildingId?: number
  buildingName?: string
  roomId?: number
  roomNo?: string
  bedId?: number
  bedNo?: string
  checkInDate?: string
  checkOutDate?: string
  nights?: number
  realCheckInTime?: string
  realCheckOutTime?: string
  status: OrderStatus
  statusLabel?: string
  statusColor?: string
  amount?: number
  currency?: string
  source?: string
  earlyCheckOutReason?: string
  remark?: string
  createTime?: string
}

export interface OrderQuery extends PageQuery {
  status?: OrderStatus
  regionId?: number
  employeeId?: number
  keyword?: string
  from?: string
  to?: string
}

/* ------------------------------------------------------------ 统计 */

export interface ApplyTrendPoint {
  day: string
  cnt: number
}

export interface RegionDistribution {
  regionId: number
  regionName: string
  roomCount: number
  bedCount: number
  idleBedCount: number
  occupiedBedCount: number
  openRoomCount?: number
  closedRoomCount?: number
  maintenanceRoomCount?: number
  disabledBedCount?: number
}

export interface StatsOverview {
  roomCount: number
  openRoomCount: number
  closedRoomCount: number
  maintenanceRoomCount: number
  bedCount: number
  idleBedCount: number
  occupiedBedCount: number
  disabledBedCount: number
  occupancyRate: number
  applyStatusCount: Record<string, number>
  orderStatusCount: Record<string, number>
  applyTrend: ApplyTrendPoint[]
  regionDistribution: RegionDistribution[]
}

export interface StatsOccupancy {
  occupiedBeds: number
  usableBeds: number
  idleBeds: number
  occupancyRate: number
  scope?: string
}

/* ------------------------------------------------------------ 用户与审计 */

export interface UserCreateRequest {
  id?: number
  username: string
  password?: string
  realName: string
  employeeNo?: string
  role: UserRole
  regionId?: number
  department?: string
  email?: string
  phone?: string
  status?: number
}

export interface UserQuery extends PageQuery {
  keyword?: string
  role?: UserRole
  regionId?: number
  status?: number
}

export interface AuditLog {
  /** 雪花 id，字符串承载 */
  id: LongId
  traceId?: string
  userId?: number
  userName?: string
  role?: string
  module?: string
  action?: string
  targetType?: string
  targetId?: string
  beforeJson?: string
  afterJson?: string
  result?: 'SUCCESS' | 'FAIL'
  errorMsg?: string
  ip?: string
  userAgent?: string
  costMs?: number
  createTime?: string
}

export interface AuditQuery extends PageQuery {
  module?: string
  action?: string
  userId?: number
  targetId?: string
  traceId?: string
  from?: string
  to?: string
}

/* ------------------------------------------------------------ 枚举字典 */

export interface EnumItem {
  value: string
  label: string
  color?: string
  /** roomType 上附带容量 */
  capacity?: number
}

export interface EnumDictionary {
  userRole: EnumItem[]
  applyStatus: EnumItem[]
  orderStatus: EnumItem[]
  roomStatus: EnumItem[]
  roomType: EnumItem[]
  genderLimit: EnumItem[]
  bedStatus: EnumItem[]
  applySource: EnumItem[]
  docCategory: EnumItem[]
  auditResult: EnumItem[]
  agentType: EnumItem[]
  agentMessageStatus: EnumItem[]
  agentTaskStatus: EnumItem[]
  agentTaskType: EnumItem[]
  approvalStatus: EnumItem[]
  auditModule: EnumItem[]
  streamEvent: EnumItem[]
}

export type EnumKey = keyof EnumDictionary

/* ------------------------------------------------------------ AI 助手 */

export interface AgentInfo {
  type: string
  label: string
  description?: string
  sample?: string
}

export interface AgentLimits {
  turnsPerMinute: number
  maxStreamsPerUser: number
  maxMessageLength: number
  dailyTokenBudget: number
}

export interface AgentMeta {
  enabled: boolean
  primaryModel?: string
  agents: AgentInfo[]
  adminAgents?: AgentInfo[]
  limits: AgentLimits
  ragEnabled: boolean
}

export interface ConversationVO {
  id: LongId
  conversationNo?: string
  title?: string
  agentType?: string
  agentTypeLabel?: string
  messageCount?: number
  lastMessageTime?: string
  hasSummary?: boolean
}

/**
 * 消息角色。
 * 注意：后端实际返回的是**大写**枚举（curl 验证：USER / ASSISTANT / SYSTEM），
 * 这里同时兼容大小写，读取时统一用 normalizeRole 归一化。
 */
export type ChatRole = 'USER' | 'ASSISTANT' | 'SYSTEM' | 'user' | 'assistant' | 'system'

/** 把后端返回的角色归一化为小写，便于前端比较 */
export function normalizeRole(role?: string | null): 'user' | 'assistant' | 'system' {
  const lower = (role ?? '').toLowerCase()
  if (lower === 'user') return 'user'
  if (lower === 'assistant') return 'assistant'
  return 'system'
}

export interface ChatMessageVO {
  id: LongId
  seqNo?: number
  role: ChatRole
  content?: string
  thinking?: string
  /** 历史工具调用（JSON 字符串或结构化数组，视后端存储而定） */
  toolCalls?: string | ToolCallRecord[]
  /** 历史 RAG 引用（刷新后仍能展示来源） */
  references?: SseReference[]
  status?: string
  model?: string
  tokensIn?: number
  tokensOut?: number
  latencyMs?: number
  firstTokenMs?: number
  blockReason?: string
  time?: string
  turnId?: string
}

/** 工具入参：后端以对象下发（SSE 的 TOOL 帧与 replay 均是），历史记录里也可能是字符串 */
export type ToolArguments = string | Record<string, unknown> | null

/** 把工具入参转成可读字符串，供 UI 展示 */
export function formatArguments(args: ToolArguments | undefined): string | undefined {
  if (args === undefined || args === null) return undefined
  if (typeof args === 'string') return args === '{}' ? undefined : args
  const keys = Object.keys(args)
  if (keys.length === 0) return undefined
  try {
    return JSON.stringify(args)
  } catch {
    return undefined
  }
}

export interface ToolCallRecord {
  seqInTurn?: number
  tool: string
  arguments?: ToolArguments
  success?: boolean
  deduplicated?: boolean
  permissionChecked?: boolean
  error?: string
  costMs?: number
}

export interface ReplayNode {
  seq: number
  role: string
  content?: string
  thinking?: string
  status?: string
  model?: string
  tokensIn?: number
  tokensOut?: number
  latencyMs?: number
  firstTokenMs?: number
  time?: string
  toolCalls?: ToolCallRecord[]
}

export interface AgentReplay {
  conversationId: LongId
  messageId?: LongId
  nodeCount: number
  nodes: ReplayNode[]
}

export interface AgentMemory {
  id?: LongId
  content?: string
  memoryType?: string
  createTime?: string
  [key: string]: unknown
}

/* ---------------------- Agent 运营看板（字段名以真实接口为准） ---------------------- */

/** GET /api/agent/admin/models —— 模型健康状态 */
export interface AgentModelHealth {
  role: string
  model: string
  available: boolean
  consecutiveFailures: number
  successCount: number
  failureCount: number
  breakerOpenSeconds: number
}

/** GET /api/agent/admin/usage —— 用量趋势点（后端 VO 为驼峰命名，与其它接口保持一致） */
export interface AgentUsageTrendPoint {
  statDate: string
  tokens: number
  toolCalls: number
  turns: number
  cost: number
}

export interface AgentToolStatistic {
  toolName: string
  total: number
  failed: number
  avgCostMs: number
}

export interface AgentUsageResult {
  trend: AgentUsageTrendPoint[]
  toolStatistics: AgentToolStatistic[]
  periodDays: number
}

/** GET /api/agent/admin/knowledge —— 知识库索引状态 */
export interface AgentKnowledgeLoadReport {
  created: number
  updated: number
  unchanged: number
  chunkCount: number
  documentCount: number
  indexSize: number
  details?: string[]
}

export interface AgentKnowledgeStatus {
  indexSize: number
  indexVersion: number
  topK: number
  minScore: number
  mmrEnabled: boolean
  lastLoadReport?: AgentKnowledgeLoadReport
}

/**
 * GET /api/agent/admin/rag-quality —— RAG 质量指标
 *
 * 后端已把字段统一为驼峰命名，并且**比率一律是 0–1 的小数**（不再是 "12.5%" 字符串）。
 * 这样做的好处是前端可以比较、排序、按阈值变色，百分比符号由展示层决定。
 */
export interface RagQuality {
  periodDays: number
  totalRetrievals: number
  noHitCount: number
  cacheHitCount: number
  /** 0–1 的小数，展示时 ×100 并加 % */
  noHitRate: number
  cacheHitRate: number
  avgTopScore: number
  avgLatencyMs?: number
  likeCount: number
  dislikeCount: number
  topMissedQueries: { query: string; count: number }[]
}

/** GET /api/agent/admin/models —— 模型健康与熔断 */
export interface ModelHealth {
  role: string
  model: string
  available: boolean
  breakerOpen: boolean
  breakerRemainingSeconds: number
  successCount: number
  failureCount: number
  consecutiveFailures: number
}

/** GET /api/agent/admin/tools —— 工具清单与读写属性 */
export interface AgentToolsResult {
  assembledAgents: string[]
  /** 单一来源的工具目录：名称、中文标签、是否只读 */
  tools: { name: string; label: string; readOnly: boolean }[]
  keywordRouting?: Record<string, string[]>
  maxToolCallInvocations?: number
  maxToolCallingRoundTrips?: number
  turnTimeoutSeconds?: number
}

/** 用量统计 */
export interface UsageStats {
  trend: {
    statDate: string
    turns?: number
    tokens?: number
    toolCalls?: number
    cost?: number
  }[]
  toolStatistics: { toolName: string; total?: number; failed?: number; avgCostMs?: number }[]
  periodDays: number
}

/** RAG 评测指标（比率均为 0–1 小数） */
export interface RagEvalMetrics {
  totalQuestions: number
  hitAt1: number
  hitAt3: number
  hitAt5: number
  recallAt1: number
  recallAt3: number
  recallAt5: number
  mrr: number
  keywordHitCount: number
  keywordHitRate: number
  noHitCount: number
  noHitRate: number
  avgTopScore: number
  avgLatencyMs: number
}

export interface RagEvalResult {
  indexSize: number
  metrics: RagEvalMetrics
  missed: RagEvalItem[]
  details: RagEvalItem[]
  markdown: string
}

export interface RagEvalItem {
  id: string
  question: string
  expectedDocCode: string
  topDocCode?: string
  rank?: number
  topScore?: number
  latencyMs?: number
  keywordHit?: boolean
  category?: string
}

/** 助记信息（长期记忆） */
export interface AgentMemoryVO {
  id: LongId
  type: string
  typeLabel?: string
  memoryKey?: string
  content?: string
  importance?: number
  updateTime?: string
}

/* ------------------------------------------------------------ SSE 事件 */

export interface SseStartData {
  messageId: LongId
  conversationId: LongId
  conversationNo?: string
  turnId?: string
  traceId?: string
  startSeq?: number
  heartbeatSeconds?: number
  notice?: string
}

export interface SseReasoningData {
  text: string
}

export interface SseDeltaData {
  text: string
}

export interface SseToolData {
  phase: 'start' | 'end'
  tool: string
  label: string
  /** 实测为对象，例如 {"regionCode":"SEA","city":"新加坡"} */
  arguments?: ToolArguments
  failed?: boolean
}

export interface SseReference {
  snippet?: string
  title?: string
}

export interface SseReferenceData {
  count?: number
  references: SseReference[]
}

export interface SseDoneData {
  messageId: LongId
  model?: string
  agent?: string
  tokensIn?: number
  tokensOut?: number
  latencyMs?: number
  firstTokenMs?: number
  toolCalls?: number
  ragHits?: number
  status?: string
  graceful?: boolean
  message?: string
}

export interface SseBlockedData {
  messageId: LongId
  reason?: string
}

export interface SseInterruptedData {
  messageId: LongId
  message?: string
  partialLength?: number
}

export interface SseErrorData {
  messageId?: LongId
  message?: string
  partialLength?: number
  traceId?: string
  /** 业务码；实测护栏拦截 prompt injection 时下发 6009，且不发送 START 帧 */
  code?: number
}

/** 前端使用的一条工具调用过程 UI 状态 */
export interface ToolCallView {
  key: string
  tool: string
  label: string
  /** 已格式化为字符串的入参摘要 */
  arguments?: string
  running: boolean
  failed: boolean
}
