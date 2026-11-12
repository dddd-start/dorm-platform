<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { auditApi } from '@/api/audit'
import { useMetaStore } from '@/stores/meta'
import { toLoadError } from '@/api/http'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AsyncState from '@/components/AsyncState.vue'
import type { AuditLog } from '@/api/types'

/** 审计日志：条件筛选 + 按 traceId 查看整条链路 + before/after JSON 对比 */

const meta = useMetaStore()
const { isMobile } = useBreakpoint()

const loading = ref(false)
const list = ref<AuditLog[]>([])
const total = ref(0)
/** 错误态（加载失败，带重试 + traceId）与空态（确实没有记录）必须分开 */
const loadError = ref('')
const loadTraceId = ref('')

const query = reactive({
  current: 1,
  size: 10,
  module: '',
  action: '',
  userId: undefined as number | undefined,
  targetId: '',
  traceId: '',
  dateRange: [] as string[],
})

/** 是否处于筛选状态 —— 决定空态文案 */
const filtered = computed(() =>
  Boolean(
    query.module ||
      query.action ||
      query.userId !== undefined ||
      query.targetId ||
      query.traceId ||
      query.dateRange?.length,
  ),
)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  loadTraceId.value = ''
  try {
    const page = await auditApi.page({
      current: query.current,
      size: query.size,
      module: query.module || undefined,
      action: query.action || undefined,
      userId: query.userId,
      targetId: query.targetId || undefined,
      traceId: query.traceId || undefined,
      from: query.dateRange?.[0] || undefined,
      to: query.dateRange?.[1] || undefined,
    })
    list.value = page.records ?? []
    total.value = page.total ?? 0
  } catch (err) {
    // 关键词：审计日志页面里"接口失败"绝不能被画成"没有日志" ——
    // 那会让人误以为"这段时间没有操作记录"，是审计场景下最危险的一种误导。
    const { message, traceId } = toLoadError(err)
    loadError.value = message
    loadTraceId.value = traceId
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  query.current = 1
  void load()
}

function handleReset(): void {
  query.current = 1
  query.module = ''
  query.action = ''
  query.userId = undefined
  query.targetId = ''
  query.traceId = ''
  query.dateRange = []
  void load()
}

/* ------------------------------ 详情 / 链路 ------------------------------ */
const detailVisible = ref(false)
const traceVisible = ref(false)
const current = ref<AuditLog | null>(null)
const traceLoading = ref(false)
const traceLogs = ref<AuditLog[]>([])
/** 链路请求失败也要能区分出来（否则会被当成"没有链路记录"） */
const traceError = ref('')
const traceErrorId = ref('')
/** 当前正在查看的 traceId —— 失败后重试要能回到同一条链路 */
const currentTraceId = ref('')

function openDetail(row: AuditLog): void {
  current.value = row
  detailVisible.value = true
}

async function openTrace(row: AuditLog): Promise<void> {
  if (!row.traceId) return
  traceVisible.value = true
  await loadTrace(row.traceId)
}

/** 按 traceId 拉链路；失败时进入错误态而不是空态 */
async function loadTrace(traceId: string): Promise<void> {
  currentTraceId.value = traceId
  traceLoading.value = true
  traceError.value = ''
  traceErrorId.value = ''
  try {
    traceLogs.value = await auditApi.trace(traceId)
  } catch (err) {
    const { message, traceId: tid } = toLoadError(err)
    traceError.value = message
    traceErrorId.value = tid
    traceLogs.value = []
  } finally {
    traceLoading.value = false
  }
}

/** 重试当前 traceId 的链路加载（由状态组件的重试按钮触发） */
async function retryTrace(): Promise<void> {
  if (currentTraceId.value) await loadTrace(currentTraceId.value)
}

/** JSON 美化；解析失败时原样返回 */
function pretty(raw?: string): string {
  if (!raw) return '（无）'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/**
 * 逐行对比 before / after，标记新增（+）、删除（-）与修改（~）。
 * 只做顶层键比较，够用且直观。
 */
interface DiffRow {
  key: string
  before: string
  after: string
  type: 'added' | 'removed' | 'changed' | 'same'
}

const diffRows = computed<DiffRow[]>(() => {
  const row = current.value
  if (!row) return []
  let before: Record<string, unknown> = {}
  let after: Record<string, unknown> = {}
  try {
    before = row.beforeJson ? (JSON.parse(row.beforeJson) as Record<string, unknown>) : {}
  } catch {
    before = {}
  }
  try {
    after = row.afterJson ? (JSON.parse(row.afterJson) as Record<string, unknown>) : {}
  } catch {
    after = {}
  }
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort()
  return keys.map((key) => {
    const b = before[key]
    const a = after[key]
    const bs = b === undefined ? '（无）' : JSON.stringify(b)
    const as = a === undefined ? '（无）' : JSON.stringify(a)
    let type: DiffRow['type'] = 'same'
    if (b === undefined && a !== undefined) type = 'added'
    else if (b !== undefined && a === undefined) type = 'removed'
    else if (bs !== as) type = 'changed'
    return { key, before: bs, after: as, type }
  })
})

const diffSummary = computed(() => {
  const rows = diffRows.value
  return {
    added: rows.filter((r) => r.type === 'added').length,
    removed: rows.filter((r) => r.type === 'removed').length,
    changed: rows.filter((r) => r.type === 'changed').length,
  }
})

function rowClass(row: DiffRow): string {
  if (row.type === 'added') return 'diff-added'
  if (row.type === 'removed') return 'diff-removed'
  if (row.type === 'changed') return 'diff-changed'
  return ''
}

/** el-table 的行样式回调（模板里不能写带类型标注的箭头函数） */
function rowClassName({ row }: { row: DiffRow }): string {
  return rowClass(row)
}

function resultTagType(result?: string): 'success' | 'danger' | 'info' {
  if (result === 'SUCCESS') return 'success'
  if (result === 'FAIL') return 'danger'
  return 'info'
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page-container">
    <div class="page-card">
      <h3 class="page-title">审计日志</h3>

      <div class="filter-bar">
        <el-select v-model="query.module" placeholder="模块" clearable style="width: 150px">
          <el-option
            v-for="item in meta.options('auditModule')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-input v-model="query.action" placeholder="动作（如 LOGIN）" clearable style="width: 160px" />
        <el-input-number
          v-model="query.userId"
          :min="1"
          :controls="false"
          placeholder="用户ID"
          style="width: 120px"
        />
        <el-input v-model="query.targetId" placeholder="目标ID" clearable style="width: 140px" />
        <el-input v-model="query.traceId" placeholder="traceId" clearable style="width: 190px" />
        <el-date-picker
          v-model="query.dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          style="width: 250px"
        />
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <!-- 审计日志刻意**保留表格**（不转卡片）：它的价值在"跨行比较时间/操作人/结果"，
           转卡片会破坏这种可比性。窄屏改用横向滚动 + 提示条，并取消 fixed 列
           （实测 390px 下 fixed 的 90px 操作列会占掉可视宽度的近三成）。 -->
      <div class="scroll-hint">
        <el-icon><DCaret /></el-icon><span>左右滑动查看更多列</span>
      </div>

      <AsyncState
        :loading="loading"
        :error="loadError"
        :trace-id="loadTraceId"
        :empty="list.length === 0"
        skeleton="list"
        :skeleton-rows="6"
        :empty-title="filtered ? '没有符合条件的审计日志' : '暂无审计日志'"
        :empty-description="
          filtered
            ? '试试放宽模块 / 动作 / 时间范围条件，或重置后重新查询。'
            : '系统会自动记录管理端的增删改操作，发生操作后这里就会出现记录。'
        "
        @retry="load"
      >
        <template #empty-action>
          <el-button v-if="filtered" size="small" @click="handleReset">重置筛选条件</el-button>
        </template>

        <el-table :data="list" border stripe>
          <el-table-column prop="createTime" label="时间" width="165">
            <template #default="{ row }">
              <span class="mono">{{ row.createTime ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="traceId" width="160">
            <template #default="{ row }">
              <el-button link type="primary" class="mono" @click="openTrace(row)">
                {{ row.traceId ?? '-' }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column prop="userName" label="操作人" width="100">
            <template #default="{ row }">{{ row.userName ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="角色" width="110">
            <template #default="{ row }">{{ meta.label('userRole', row.role) }}</template>
          </el-table-column>
          <el-table-column label="模块" width="110">
            <template #default="{ row }">{{ meta.label('auditModule', row.module) }}</template>
          </el-table-column>
          <el-table-column prop="action" label="动作" min-width="150" show-overflow-tooltip />
          <el-table-column prop="targetType" label="目标" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.targetType ?? '-' }}
              <span v-if="row.targetId" class="muted">#{{ row.targetId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="resultTagType(row.result)" size="small" effect="plain">
                {{ meta.label('auditResult', row.result) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="90" align="right">
            <template #default="{ row }">
              <span v-if="row.costMs != null" class="mono">{{ row.costMs }} ms</span>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="ip" label="IP" width="130">
            <template #default="{ row }">
              <span class="mono">{{ row.ip ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" :fixed="isMobile ? undefined : 'right'">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="list.length > 0" class="pager">
          <el-pagination
            v-model:current-page="query.current"
            v-model:page-size="query.size"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            @current-change="load"
            @size-change="handleSearch"
          />
        </div>
      </AsyncState>
    </div>

    <!-- ------------------------------ 日志详情 + JSON 对比 ------------------------------ -->
    <el-drawer v-model="detailVisible" title="审计日志详情" size="820px">
      <template v-if="current">
        <!-- 窄屏改单列：2 列描述在 390px 下每个值只剩 ~130px，traceId 会被截断 -->
        <el-descriptions :column="isMobile ? 1 : 2" border size="small">
          <el-descriptions-item label="时间">{{ current.createTime ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="traceId">
            <span class="mono">{{ current.traceId ?? '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="操作人">
            {{ current.userName ?? '-' }}（{{ current.userId ?? '-' }}）
          </el-descriptions-item>
          <el-descriptions-item label="角色">{{ meta.label('userRole', current.role) }}</el-descriptions-item>
          <el-descriptions-item label="模块">{{ meta.label('auditModule', current.module) }}</el-descriptions-item>
          <el-descriptions-item label="动作">{{ current.action ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="目标类型">{{ current.targetType ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="目标 ID">{{ current.targetId ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="结果">
            <el-tag :type="resultTagType(current.result)" size="small" effect="plain">
              {{ meta.label('auditResult', current.result) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="耗时">
            {{ current.costMs != null ? `${current.costMs} ms` : '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="IP">
            <span class="mono">{{ current.ip ?? '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="User-Agent">
            <span class="mono">{{ current.userAgent ?? '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="current.errorMsg" label="错误信息" :span="isMobile ? 1 : 2">
            <span class="error-text">{{ current.errorMsg }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">
          before / after 变更对比
          <el-tag size="small" type="success" effect="plain" style="margin-left: 8px">
            新增 {{ diffSummary.added }}
          </el-tag>
          <el-tag size="small" type="danger" effect="plain" style="margin-left: 4px">
            删除 {{ diffSummary.removed }}
          </el-tag>
          <el-tag size="small" type="warning" effect="plain" style="margin-left: 4px">
            修改 {{ diffSummary.changed }}
          </el-tag>
        </el-divider>

        <!-- 这里"该记录没有结构化变更内容"是**真的空**（记录本身加载成功了，
             只是没有 before/after），因此用空态而不是错误态。 -->
        <AsyncState :empty="diffRows.length === 0" compact empty-title="该记录没有结构化变更内容" empty-description="这条审计只记录了动作本身，没有字段级变更。">
          <div class="scroll-hint">
            <el-icon><DCaret /></el-icon><span>左右滑动查看完整对比</span>
          </div>
          <el-table
            :data="diffRows"
            border
            size="small"
            :row-class-name="rowClassName"
            max-height="300"
          >
            <el-table-column prop="key" label="字段" width="170" />
            <el-table-column label="变更前" min-width="200">
              <template #default="{ row }">
                <span class="mono">{{ row.before }}</span>
              </template>
            </el-table-column>
            <el-table-column label="变更后" min-width="200">
              <template #default="{ row }">
                <span class="mono">{{ row.after }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="80" align="center">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="row.type === 'added' ? 'success' : row.type === 'removed' ? 'danger' : row.type === 'changed' ? 'warning' : 'info'"
                >
                  {{ row.type === 'added' ? '新增' : row.type === 'removed' ? '删除' : row.type === 'changed' ? '修改' : '未变' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </AsyncState>

        <el-divider content-position="left">原始 JSON</el-divider>
        <!-- 窄屏单列堆叠：并排两列在 390px 下每列仅 ~150px，JSON 完全不可读 -->
        <el-row :gutter="12">
          <el-col :xs="24" :md="12">
            <div class="muted" style="margin-bottom: 4px">beforeJson</div>
            <pre class="json-block">{{ pretty(current.beforeJson) }}</pre>
          </el-col>
          <el-col :xs="24" :md="12">
            <div class="muted" style="margin-bottom: 4px">afterJson</div>
            <pre class="json-block">{{ pretty(current.afterJson) }}</pre>
          </el-col>
        </el-row>
      </template>
    </el-drawer>

    <!-- ------------------------------ traceId 链路 ------------------------------ -->
    <el-drawer v-model="traceVisible" title="调用链路（按 traceId）" size="720px">
      <!-- 三态：加载骨架 / 请求失败（可重试 + traceId）/ 确实没有链路记录。
           原来的 v-loading 会把"接口失败"画成"未找到链路记录"，
           在排查问题时这是最误导人的一种表现。 -->
      <AsyncState
        :loading="traceLoading"
        :error="traceError"
        :trace-id="traceErrorId"
        :empty="traceLogs.length === 0"
        skeleton="list"
        :skeleton-rows="4"
        compact
        empty-title="未找到该 traceId 的链路记录"
        empty-description="该 traceId 可能已被归档，或链路记录尚未落库。"
        @retry="retryTrace"
      >
        <el-alert
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          :title="`共 ${traceLogs.length} 条链路记录`"
        />
        <el-timeline>
          <el-timeline-item
            v-for="log in traceLogs"
            :key="log.id"
            :timestamp="log.createTime ?? ''"
            :type="log.result === 'FAIL' ? 'danger' : 'success'"
            placement="top"
          >
            <div style="font-weight: 600">
              [{{ meta.label('auditModule', log.module) }}] {{ log.action ?? '-' }}
              <el-tag :type="resultTagType(log.result)" size="small" effect="plain" style="margin-left: 6px">
                {{ meta.label('auditResult', log.result) }}
              </el-tag>
            </div>
            <div class="muted">
              操作人：{{ log.userName ?? '-' }} · IP：{{ log.ip ?? '-' }}
              <template v-if="log.costMs != null"> · 耗时 {{ log.costMs }} ms</template>
            </div>
            <div v-if="log.targetType" class="muted">
              目标：{{ log.targetType }}<span v-if="log.targetId"> #{{ log.targetId }}</span>
            </div>
            <div v-if="log.errorMsg" class="error-text error-text--sm">{{ log.errorMsg }}</div>
            <div v-if="log.beforeJson || log.afterJson" style="margin-top: 6px">
              <el-button link type="primary" size="small" @click="openDetail(log)">
                查看 before / after 对比
              </el-button>
            </div>
          </el-timeline-item>
        </el-timeline>
      </AsyncState>
    </el-drawer>
  </div>
</template>

<style scoped>
:deep(.diff-added) {
  background: var(--d-success-subtle);
}

:deep(.diff-removed) {
  background: var(--d-danger-subtle);
}

:deep(.diff-changed) {
  background: var(--d-warning-subtle);
}

/* 错误文字：原来写死 #f56c6c，深色主题下落在深底上偏暗；改用令牌 */
.error-text {
  color: var(--d-danger-text);
}

.error-text--sm {
  font-size: var(--d-font-size-xs);
}
</style>
