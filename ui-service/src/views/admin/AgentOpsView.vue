<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { agentApi } from '@/api/agent'
import { toLoadError } from '@/api/http'
import StateEmpty from '@/components/StateEmpty.vue'
import AsyncState from '@/components/AsyncState.vue'
import { useBreakpoint } from '@/composables/useBreakpoint'
import { ElMessage } from 'element-plus'
import { normalizeRole, formatArguments } from '@/api/types'
import type {
  AgentKnowledgeStatus,
  AgentMeta,
  AgentModelHealth,
  AgentReplay,
  AgentToolsResult,
  AgentUsageResult,
  ConversationVO,
  RagQuality,
} from '@/api/types'

/**
 * Agent 运营看板（管理员）。
 * 六个区块：模型健康状态 / 用量趋势 / 知识库索引 / RAG 质量 / 工具清单 / 会话回放。
 * 数据分别来自 /api/agent/admin/{models,usage,knowledge,rag-quality,tools} 与 /api/agent/replay/{id}。
 */

const activeTab = ref('models')
const { isMobile } = useBreakpoint()

/**
 * 各区块的错误簿记。
 *
 * 为什么用一张表而不是 6 组 ref：这个页面有 6 个独立数据源，
 * 逐个写 loadError/loadTraceId 会有 12 个 ref 且极易漏掉某一个 ——
 * 漏掉的那个区块就会把"接口失败"画成"0 / 暂无数据"，
 * 运营看到的是"模型都正常、用量为 0"，属于最危险的误导。
 * 用 key 统一管理后，每个区块只多一行 `setErr(key, err)`。
 */
const errors = reactive<Record<string, { message: string; traceId: string }>>({})
function setErr(key: string, err: unknown): void {
  const { message, traceId } = toLoadError(err)
  errors[key] = { message, traceId }
}
function clearErr(key: string): void {
  delete errors[key]
}
/** 模板里读错误信息 / traceId 的小工具 */
const errOf = (key: string): string => errors[key]?.message ?? ''
const traceOf = (key: string): string => errors[key]?.traceId ?? ''

/* ------------------------------ 元信息 ------------------------------ */
const meta = ref<AgentMeta | null>(null)

async function loadMeta(): Promise<void> {
  try {
    meta.value = await agentApi.meta()
  } catch {
    meta.value = null
  }
}

/* ------------------------------ 模型健康 ------------------------------ */
const modelsLoading = ref(false)
const models = ref<AgentModelHealth[]>([])

async function loadModels(): Promise<void> {
  modelsLoading.value = true
  clearErr('models')
  try {
    models.value = await agentApi.adminModels()
  } catch (err) {
    models.value = []
    setErr('models', err)
  } finally {
    modelsLoading.value = false
  }
}

/** 成功率 = successCount / (successCount + failureCount) */
function successRate(row: AgentModelHealth): number {
  const total = (row.successCount ?? 0) + (row.failureCount ?? 0)
  if (total === 0) return 0
  return Number((((row.successCount ?? 0) / total) * 100).toFixed(1))
}

/* ------------------------------ 用量趋势 ------------------------------ */
const usageLoading = ref(false)
const usage = ref<AgentUsageResult | null>(null)
const usageDays = ref(14)
const usageChartRef = ref<HTMLElement | null>(null)
let usageChart: echarts.ECharts | null = null

async function loadUsage(): Promise<void> {
  usageLoading.value = true
  clearErr('usage')
  try {
    usage.value = await agentApi.adminUsage(usageDays.value)
  } catch (err) {
    usage.value = null
    setErr('usage', err)
  } finally {
    usageLoading.value = false
  }
  // 图表容器由 AsyncState 控制显隐：必须等骨架卸载、容器挂载后再 init，
  // 否则 echarts.init(null) 会静默失败（"改了加载态导致图表消失"的典型坑）。
  await nextTick()
  if (usage.value) renderUsageChart()
}

// ECharts 在 display:none 的容器里初始化时拿不到宽度，会退化成 100px 并让坐标轴乱掉。
// 因此「切到该 tab 时」再 resize 一次；没初始化过就补一次渲染。
watch(activeTab, async (tab) => {
  if (tab !== 'usage') return
  await nextTick()
  if (usageChart) {
    usageChart.resize()
  } else if (usage.value) {
    renderUsageChart()
  }
})

function renderUsageChart(): void {
  const trend = usage.value?.trend ?? []
  if (!usageChartRef.value) return
  if (!usageChart) usageChart = echarts.init(usageChartRef.value)
  // tab 容器已可见时按真实宽度重排（首次渲染可能发生在隐藏 tab 中）
  usageChart.resize()
  usageChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { top: 0, data: ['对话轮次', '工具调用', 'Token 数', '成本(USD)'] },
    grid: { left: 55, right: 55, top: 40, bottom: 40 },
    xAxis: {
      type: 'category',
      data: trend.map((p) => p.statDate),
      axisLabel: { rotate: 30, fontSize: 11 },
    },
    yAxis: [
      { type: 'value', name: '次数', minInterval: 1 },
      { type: 'value', name: 'Token / 成本', minInterval: 1 },
    ],
    series: [
      {
        name: '对话轮次',
        type: 'bar',
        itemStyle: { color: '#409eff' },
        data: trend.map((p) => p.turns),
      },
      {
        name: '工具调用',
        type: 'bar',
        itemStyle: { color: '#67c23a' },
        data: trend.map((p) => p.toolCalls),
      },
      {
        name: 'Token 数',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        itemStyle: { color: '#e6a23c' },
        data: trend.map((p) => p.tokens),
      },
      {
        name: '成本(USD)',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        itemStyle: { color: '#f56c6c' },
        data: trend.map((p) => p.cost),
      },
    ],
  })
}

/* ------------------------------ 知识库 ------------------------------ */
const knowledgeLoading = ref(false)
const knowledge = ref<AgentKnowledgeStatus | null>(null)

async function loadKnowledge(): Promise<void> {
  knowledgeLoading.value = true
  clearErr('knowledge')
  try {
    knowledge.value = await agentApi.adminKnowledge()
  } catch (err) {
    knowledge.value = null
    setErr('knowledge', err)
  } finally {
    knowledgeLoading.value = false
  }
}

/* ------------------------------ RAG 质量 ------------------------------ */
const ragLoading = ref(false)
const rag = ref<RagQuality | null>(null)

async function loadRag(): Promise<void> {
  ragLoading.value = true
  clearErr('rag')
  try {
    rag.value = await agentApi.adminRagQuality(30)
  } catch (err) {
    rag.value = null
    setErr('rag', err)
  } finally {
    ragLoading.value = false
  }
}

/** 点踩率：dislikes / (likes + dislikes) */
const dislikeRate = computed(() => {
  const r = rag.value
  if (!r) return '0%'
  const total = (r.likeCount ?? 0) + (r.dislikeCount ?? 0)
  if (total === 0) return '0%'
  return `${(((r.dislikeCount ?? 0) / total) * 100).toFixed(1)}%`
})

/* ------------------------------ 工具清单 ------------------------------ */
const toolsLoading = ref(false)
const tools = ref<AgentToolsResult | null>(null)

async function loadTools(): Promise<void> {
  toolsLoading.value = true
  clearErr('tools')
  try {
    tools.value = await agentApi.adminTools()
  } catch (err) {
    tools.value = null
    setErr('tools', err)
  } finally {
    toolsLoading.value = false
  }
}

/** 合并成一张表：工具名 + 读写属性 + 是否挂载到某 Agent */
interface ToolRow {
  name: string
  kind: '只读' | '写入'
}

/**
 * 把后端下发的比率（0–1 小数）格式化成百分比文本。
 *
 * 后端刻意不下发 "12.5%" 这样的字符串 —— 数值只在展示层变成文本，
 * 这样前端才能做阈值判断、排序与配色，也避免"同一个字段有时是数字有时是字符串"。
 */
function percent(value: number | undefined | null, digits = 1): string {
  if (value === undefined || value === null || Number.isNaN(value)) return '0%'
  return `${(value * 100).toFixed(digits)}%`
}

const toolRows = computed<ToolRow[]>(() => {
  const t = tools.value
  if (!t) return []
  // 后端下发的是一张单一来源的工具目录（名称 + 中文标签 + 读写属性），
  // 前端只负责按读写分组渲染，不再各自维护两份清单
  const catalog = t.tools ?? []
  return [
    ...catalog.filter((x) => x.readOnly).map((x) => ({ name: x.label || x.name, kind: '只读' as const })),
    ...catalog.filter((x) => !x.readOnly).map((x) => ({ name: x.label || x.name, kind: '写入' as const })),
  ]
})

/* ------------------------------ 会话回放 ------------------------------ */
const replayLoading = ref(false)
const conversations = ref<ConversationVO[]>([])
const replayConversationId = ref<string>('')
const replay = ref<AgentReplay | null>(null)

async function loadConversations(): Promise<void> {
  try {
    const page = await agentApi.conversations({ current: 1, size: 50 })
    conversations.value = page.records ?? []
  } catch {
    conversations.value = []
  }
}

async function loadReplay(): Promise<void> {
  if (!replayConversationId.value) {
    ElMessage.warning('请先选择要回放的会话')
    return
  }
  replayLoading.value = true
  clearErr('replay')
  try {
    replay.value = await agentApi.replay(replayConversationId.value)
    if (!replay.value) ElMessage.warning('该会话暂无回放数据')
  } catch (err) {
    replay.value = null
    setErr('replay', err)
  } finally {
    replayLoading.value = false
  }
}

/** 工具调用是否成功 */
function toolOk(success?: boolean): boolean {
  return success !== false
}

/** 回放节点角色归一化（后端返回大写 USER/ASSISTANT） */
function isUserNode(role?: string): boolean {
  return normalizeRole(role) === 'user'
}

/** 回放节点的工具入参格式化 */
function argText(args?: import('@/api/types').ToolArguments): string {
  return formatArguments(args) ?? ''
}

/* ------------------------------ 生命周期 ------------------------------ */
function handleResize(): void {
  usageChart?.resize()
}

watch(usageDays, () => {
  void loadUsage()
})

onMounted(async () => {
  await Promise.all([
    loadMeta(),
    loadModels(),
    loadUsage(),
    loadKnowledge(),
    loadRag(),
    loadTools(),
    loadConversations(),
  ])
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  usageChart?.dispose()
  usageChart = null
})
</script>

<template>
  <div class="page-container">
    <div class="page-card">
      <h3 class="page-title">
        <span>Agent 运营看板</span>
        <span class="muted">
          主模型：<b>{{ meta?.primaryModel ?? '-' }}</b>
          · RAG：{{ meta?.ragEnabled ? '已启用' : '未启用' }}
          · 限流 {{ meta?.limits?.turnsPerMinute ?? '-' }} 轮/分
        </span>
      </h3>

      <el-tabs v-model="activeTab">
        <!-- ============================== 模型健康 ============================== -->
        <el-tab-pane label="模型健康状态" name="models">
          <el-button size="small" style="margin-bottom: 10px" @click="loadModels">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
          <div class="scroll-hint">
            <el-icon><DCaret /></el-icon><span>左右滑动查看更多列</span>
          </div>
          <AsyncState
            :loading="modelsLoading"
            :error="errOf('models')"
            :trace-id="traceOf('models')"
            :empty="models.length === 0"
            skeleton="list"
            :skeleton-rows="4"
            empty-title="暂无模型数据"
            empty-description="Agent 未注册任何模型角色，或注册信息尚未加载完成。"
            @retry="loadModels"
          >
          <el-table :data="models" border stripe>
            <el-table-column prop="role" label="角色" width="130">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.role }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="model" label="模型" min-width="260">
              <template #default="{ row }">
                <span class="mono">{{ row.model }}</span>
              </template>
            </el-table-column>
            <el-table-column label="可用性" width="110">
              <template #default="{ row }">
                <el-tag :type="row.available ? 'success' : 'danger'" size="small">
                  {{ row.available ? '可用' : '不可用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="成功率" width="200">
              <template #default="{ row }">
                <el-progress
                  :percentage="successRate(row)"
                  :stroke-width="14"
                  :status="successRate(row) >= 80 ? 'success' : successRate(row) >= 50 ? 'warning' : 'exception'"
                  text-inside
                />
              </template>
            </el-table-column>
            <el-table-column prop="successCount" label="成功次数" width="100" align="center" />
            <el-table-column prop="failureCount" label="失败次数" width="100" align="center" />
            <el-table-column prop="consecutiveFailures" label="连续失败" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.consecutiveFailures > 0 ? 'danger' : 'info'" size="small">
                  {{ row.consecutiveFailures }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="熔断剩余" width="110" align="center">
              <template #default="{ row }">
                <span v-if="row.breakerOpenSeconds > 0" class="breaker-open">
                  {{ row.breakerOpenSeconds }}s
                </span>
                <span v-else class="muted">未熔断</span>
              </template>
            </el-table-column>
          </el-table>
          </AsyncState>

          <el-divider content-position="left">助手与限流</el-divider>
          <el-descriptions :column="isMobile ? 1 : 3" border size="small">
            <el-descriptions-item label="每分钟轮次上限">
              {{ meta?.limits?.turnsPerMinute ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="单用户并发流">
              {{ meta?.limits?.maxStreamsPerUser ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="单条消息长度上限">
              {{ meta?.limits?.maxMessageLength ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="每日 Token 预算">
              {{ meta?.limits?.dailyTokenBudget ?? 0 }}
            </el-descriptions-item>
            <el-descriptions-item label="可用助手" :span="2">
              <el-tag
                v-for="a in [...(meta?.agents ?? []), ...(meta?.adminAgents ?? [])]"
                :key="a.type"
                size="small"
                effect="plain"
                style="margin-right: 4px"
              >
                {{ a.label }}（{{ a.type }}）
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>

        <!-- ============================== 用量趋势 ============================== -->
        <el-tab-pane label="用量趋势" name="usage">
          <div class="filter-bar">
            <el-select v-model="usageDays" style="width: 140px">
              <el-option label="近 7 天" :value="7" />
              <el-option label="近 14 天" :value="14" />
              <el-option label="近 30 天" :value="30" />
            </el-select>
            <el-button type="primary" :loading="usageLoading" @click="loadUsage">刷新</el-button>
          </div>

          <AsyncState
            :loading="usageLoading"
            :error="errOf('usage')"
            :trace-id="traceOf('usage')"
            skeleton="card"
            :skeleton-rows="2"
            @retry="loadUsage"
          >
            <div ref="usageChartRef" style="height: 320px; width: 100%"></div>

            <el-divider content-position="left">工具调用统计</el-divider>
            <AsyncState
              :empty="(usage?.toolStatistics ?? []).length === 0"
              skeleton="list"
              :skeleton-rows="3"
              compact
              empty-title="暂无工具统计"
              empty-description="统计周期内 Agent 还没有调用过工具。"
            >
            <el-table :data="usage?.toolStatistics ?? []" border stripe size="small">
              <el-table-column prop="toolName" label="工具" min-width="220">
                <template #default="{ row }">
                  <span class="mono">{{ row.toolName }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="total" label="调用次数" width="110" align="center" />
              <el-table-column label="失败次数" width="110" align="center">
                <template #default="{ row }">
                  <el-tag :type="row.failed > 0 ? 'danger' : 'info'" size="small">{{ row.failed }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="平均耗时" width="130" align="center">
                <template #default="{ row }">
                  <span class="mono">{{ row.avgCostMs }} ms</span>
                </template>
              </el-table-column>
            </el-table>
            </AsyncState>
          </AsyncState>
        </el-tab-pane>

        <!-- ============================== 知识库 ============================== -->
        <el-tab-pane label="知识库索引" name="knowledge">
          <el-button size="small" style="margin-bottom: 10px" @click="loadKnowledge">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
          <AsyncState
            :loading="knowledgeLoading"
            :error="errOf('knowledge')"
            :trace-id="traceOf('knowledge')"
            skeleton="detail"
            @retry="loadKnowledge"
          >
          <el-row :gutter="14">
            <el-col :xs="24" :md="12">
              <el-descriptions :column="1" border size="small" title="索引状态">
                <el-descriptions-item label="索引片段数">
                  <b>{{ knowledge?.indexSize ?? 0 }}</b>
                </el-descriptions-item>
                <el-descriptions-item label="索引版本">{{ knowledge?.indexVersion ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="TopK">{{ knowledge?.topK ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="最低分阈值">{{ knowledge?.minScore ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="MMR 去重">
                  <el-tag :type="knowledge?.mmrEnabled ? 'success' : 'info'" size="small">
                    {{ knowledge?.mmrEnabled ? '已启用' : '未启用' }}
                  </el-tag>
                </el-descriptions-item>
              </el-descriptions>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-descriptions :column="1" border size="small" title="最近一次加载报告">
                <el-descriptions-item label="文档数">
                  {{ knowledge?.lastLoadReport?.documentCount ?? 0 }}
                </el-descriptions-item>
                <el-descriptions-item label="片段数">
                  {{ knowledge?.lastLoadReport?.chunkCount ?? 0 }}
                </el-descriptions-item>
                <el-descriptions-item label="新建 / 更新 / 未变">
                  {{ knowledge?.lastLoadReport?.created ?? 0 }} /
                  {{ knowledge?.lastLoadReport?.updated ?? 0 }} /
                  {{ knowledge?.lastLoadReport?.unchanged ?? 0 }}
                </el-descriptions-item>
              </el-descriptions>
            </el-col>
          </el-row>
          </AsyncState>
        </el-tab-pane>

        <!-- ============================== RAG 质量 ============================== -->
        <el-tab-pane label="RAG 质量" name="rag">
          <el-button size="small" style="margin-bottom: 10px" @click="loadRag">
            <el-icon><Refresh /></el-icon> 刷新（近 30 天）
          </el-button>
          <AsyncState
            :loading="ragLoading"
            :error="errOf('rag')"
            :trace-id="traceOf('rag')"
            skeleton="card"
            :skeleton-rows="2"
            @retry="loadRag"
          >
          <el-row :gutter="14">
            <el-col :xs="12" :md="6" style="margin-bottom: 12px">
              <div class="metric">
                <div class="metric-value">{{ percent(rag?.noHitRate) }}</div>
                <div class="metric-label">无命中率（{{ rag?.noHitCount ?? 0 }} / {{ rag?.totalRetrievals ?? 0 }}）</div>
              </div>
            </el-col>
            <el-col :xs="12" :md="6" style="margin-bottom: 12px">
              <div class="metric">
                <div class="metric-value">{{ (rag?.avgTopScore ?? 0).toFixed(3) }}</div>
                <div class="metric-label">平均最高分</div>
              </div>
            </el-col>
            <el-col :xs="12" :md="6" style="margin-bottom: 12px">
              <div class="metric">
                <div class="metric-value">{{ dislikeRate }}</div>
                <div class="metric-label">点踩率（{{ rag?.dislikeCount ?? 0 }} 踩 / {{ rag?.likeCount ?? 0 }} 赞）</div>
              </div>
            </el-col>
            <el-col :xs="12" :md="6" style="margin-bottom: 12px">
              <div class="metric">
                <div class="metric-value">{{ rag?.avgLatencyMs ?? 0 }} ms</div>
                <div class="metric-label">平均检索耗时（缓存命中 {{ rag?.cacheHitCount ?? 0 }}）</div>
              </div>
            </el-col>
          </el-row>

          </AsyncState>

          <el-divider content-position="left">缺口问题榜（未命中/低分的用户提问）</el-divider>
          <AsyncState
            :loading="ragLoading"
            :error="errOf('rag')"
            :trace-id="traceOf('rag')"
            :empty="(rag?.topMissedQueries ?? []).length === 0"
            skeleton="list"
            :skeleton-rows="3"
            compact
            empty-title="暂无缺口问题"
            empty-description="近 30 天没有出现未命中或低分的用户提问，知识库覆盖良好。"
            @retry="loadRag"
          >
          <el-table :data="rag?.topMissedQueries ?? []" border stripe size="small">
            <el-table-column type="index" label="#" width="60" />
            <el-table-column prop="query" label="问题" min-width="300" show-overflow-tooltip />
            <el-table-column prop="count" label="出现次数" width="120" align="center" />
          </el-table>
          </AsyncState>
        </el-tab-pane>

        <!-- ============================== 工具清单 ============================== -->
        <el-tab-pane label="工具清单" name="tools">
          <el-button size="small" style="margin-bottom: 10px" @click="loadTools">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
          <el-descriptions v-if="tools" :column="3" border size="small" style="margin-bottom: 14px">
            <el-descriptions-item label="只读工具数">
              <b style="color: #67c23a">{{ (tools.tools ?? []).filter((x) => x.readOnly).length }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="写入工具数">
              <b style="color: #f56c6c">{{ (tools.tools ?? []).filter((x) => !x.readOnly).length }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="最大工具调用轮次">
              {{ tools.maxToolCallingRoundTrips ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="单轮超时">{{ tools.turnTimeoutSeconds ?? '-' }} 秒</el-descriptions-item>
            <el-descriptions-item label="已装配 Agent" :span="2">
              <el-tag v-for="a in tools.assembledAgents ?? []" :key="a" size="small" effect="plain" style="margin-right: 4px">
                {{ a }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>

          <AsyncState
            :loading="toolsLoading"
            :error="errOf('tools')"
            :trace-id="traceOf('tools')"
            :empty="toolRows.length === 0"
            skeleton="list"
            :skeleton-rows="4"
            compact
            empty-title="暂无工具"
            empty-description="Agent 没有装配任何工具，或工具目录尚未加载完成。"
            @retry="loadTools"
          >
          <el-table :data="toolRows" border stripe size="small">
            <el-table-column prop="name" label="工具名" min-width="240">
              <template #default="{ row }">
                <span class="mono">{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column label="读写属性" width="130">
              <template #default="{ row }">
                <el-tag :type="row.kind === '只读' ? 'success' : 'danger'" size="small" effect="plain">{{ row.kind }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          </AsyncState>

          <template v-if="tools?.keywordRouting">
            <el-divider content-position="left">关键词路由（问题 → Agent）</el-divider>
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item v-for="(words, agent) in tools.keywordRouting" :key="agent" :label="agent">
                <el-tag v-for="w in words" :key="w" size="small" effect="plain" style="margin: 0 4px 4px 0">
                  {{ w }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
          </template>
        </el-tab-pane>

        <!-- ============================== 会话回放 ============================== -->
        <el-tab-pane label="会话回放" name="replay">
          <div class="filter-bar">
            <el-select
              v-model="replayConversationId"
              placeholder="选择要回放的会话"
              filterable
              clearable
              style="width: 340px"
            >
              <el-option
                v-for="c in conversations"
                :key="c.id"
                :label="`${c.title || c.conversationNo} （${c.messageCount ?? 0} 条）`"
                :value="String(c.id)"
              />
            </el-select>
            <el-button type="primary" :loading="replayLoading" @click="loadReplay">加载回放</el-button>
            <el-button @click="loadConversations">刷新会话列表</el-button>
          </div>

          <AsyncState
            :loading="replayLoading"
            :error="errOf('replay')"
            :trace-id="traceOf('replay')"
            skeleton="list"
            :skeleton-rows="5"
            @retry="loadReplay"
          >
            <el-alert
              v-if="replay"
              type="success"
              :closable="false"
              show-icon
              style="margin-bottom: 12px"
              :title="`会话 ${replay.conversationId} 共 ${replay.nodeCount} 个节点`"
            />

            <el-timeline v-if="replay && replay.nodes.length > 0">
              <el-timeline-item
                v-for="node in replay.nodes"
                :key="node.seq"
                :timestamp="node.time ?? `#${node.seq}`"
                :type="isUserNode(node.role) ? 'primary' : 'success'"
                placement="top"
              >
                <el-card shadow="never">
                  <div style="display: flex; justify-content: space-between; align-items: center">
                    <div>
                      <el-tag size="small" :type="isUserNode(node.role) ? 'primary' : 'success'">
                        {{ isUserNode(node.role) ? '用户' : '助手' }}
                      </el-tag>
                      <span v-if="node.model" class="muted mono" style="margin-left: 8px">{{ node.model }}</span>
                      <span v-if="node.status" class="muted" style="margin-left: 8px">{{ node.status }}</span>
                    </div>
                    <span class="muted">
                      <template v-if="node.latencyMs != null">耗时 {{ node.latencyMs }}ms · </template>
                      <template v-if="node.firstTokenMs != null">首字 {{ node.firstTokenMs }}ms · </template>
                      tokens {{ node.tokensIn ?? 0 }}/{{ node.tokensOut ?? 0 }}
                    </span>
                  </div>

                  <el-collapse v-if="node.thinking" style="margin-top: 8px">
                    <el-collapse-item title="思考过程" name="t">
                      <div class="thinking-box">{{ node.thinking }}</div>
                    </el-collapse-item>
                  </el-collapse>

                  <div v-if="node.content" class="answer" style="margin-top: 8px">{{ node.content }}</div>

                  <div v-if="node.toolCalls && node.toolCalls.length > 0" style="margin-top: 8px">
                    <div class="muted" style="margin-bottom: 4px">工具调用（{{ node.toolCalls.length }}）</div>
                    <div
                      v-for="(tc, i) in node.toolCalls"
                      :key="i"
                      class="tool-card"
                      :class="{ failed: !toolOk(tc.success) }"
                    >
                      <el-icon v-if="toolOk(tc.success)"><CircleCheckFilled /></el-icon>
                      <el-icon v-else><CircleCloseFilled /></el-icon>
                      <span style="flex: 1">
                        {{ tc.tool }}
                        <span v-if="argText(tc.arguments)" class="muted mono"> {{ argText(tc.arguments) }}</span>
                      </span>
                      <el-tag v-if="tc.deduplicated" size="small" type="warning">已去重</el-tag>
                      <el-tag v-if="tc.permissionChecked" size="small" type="info">已鉴权</el-tag>
                      <span v-if="tc.costMs != null" class="muted mono">{{ tc.costMs }}ms</span>
                    </div>
                    <div v-if="node.toolCalls.some((t) => t.error)" class="tool-error-text">
                      {{ node.toolCalls.find((t) => t.error)?.error }}
                    </div>
                  </div>
                </el-card>
              </el-timeline-item>
            </el-timeline>

            <!-- 三态统一：空态用统一组件（说明"为什么是空的 + 下一步做什么"），
                 与加载中（骨架）、出错（StateError）在视觉上明确区分 -->
            <StateEmpty
              v-else-if="!replay"
              data-state="empty"
              compact
              title="请选择会话并加载回放数据"
              description="选择上方的一条会话后点击「加载回放」，这里会显示它的完整对话回放。"
            />
          </AsyncState>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
/* 指标卡：原来写死 #fff / #ebeef5 / #409eff / #909399，
   深色主题下白底会把整页戳出一个亮斑。全部改为设计令牌。 */
.metric {
  background: var(--d-surface-card);
  border: var(--d-border-width) solid var(--d-border-subtle);
  border-radius: var(--d-radius-md);
  padding: var(--d-space-4);
  text-align: center;
}

.metric-value {
  font-size: var(--d-font-size-2xl);
  font-weight: var(--d-font-weight-bold);
  color: var(--d-brand);
}

.metric-label {
  font-size: var(--d-font-size-xs);
  color: var(--d-text-tertiary);
  margin-top: var(--d-space-1);
}

/* 熔断剩余时间 / 工具错误：写死 #f56c6c 在深色下偏暗，改用令牌语义色 */
.breaker-open,
.tool-error-text {
  color: var(--d-danger-text);
}

.tool-error-text {
  font-size: var(--d-font-size-xs);
}
</style>
