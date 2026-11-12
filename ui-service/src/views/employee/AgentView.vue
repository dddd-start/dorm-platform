<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { agentApi } from '@/api/agent'
import { useAgentStream, type AgentStreamState, type ToolCard } from '@/composables/useAgentStream'
import { useAuthStore } from '@/stores/auth'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AsyncState from '@/components/AsyncState.vue'
import MessageMarkdown from '@/components/MessageMarkdown.vue'
import type {
  AgentInfo,
  AgentMeta,
  ChatMessageVO,
  ConversationVO,
  SseReference,
  ToolCallRecord,
} from '@/api/types'
import { normalizeRole, formatArguments } from '@/api/types'

/**
 * AI 助手页。
 *
 * 架构说明：
 * - 历史消息与「已结束的当前轮」都放在 messages 数组里；
 * - 正在流式生成的这一轮不放进数组，而是由 useAgentStream 的响应式状态直接渲染
 *   （liveKey 控制显隐）。流一旦进入终态（DONE/BLOCKED/INTERRUPTED/ERROR），
 *   立刻把状态「快照」成一条普通消息追加进 messages，同时重置流状态，
 *   这样既不会重复渲染，也不会在切换会话时丢内容。
 * - 断线重连、seq 去重、指数退避全部封装在 useAgentStream 内（**本轮未改动该逻辑**）。
 *
 * 本轮打磨的 6 个交互点（都是"产品门面"级别的东西）：
 *   1. 流式光标：形态与节奏跟随真实分片到达，而不是恒定闪烁
 *   2. 工具调用卡片：工具名 / 入参 / 结果 / 进行中-成功-失败三态
 *   3. 「正在检索」骨架：RAG 检索阶段给出内容形状，而不是一个转圈
 *   4. 引用溯源：回答里的知识库引用可点开看到来源文档
 *   5. 重试 / 打断：生成中可中断，失败可重试；区分护栏拦截（code 6009）与上游故障
 *   6. 长回答折叠：超长回答默认折叠，可展开
 */

interface BubbleMessage {
  /** 前端唯一 key */
  key: string
  /** 后端 messageId（用于点赞/点踩/停止），历史消息也有 */
  id: string
  role: 'user' | 'assistant'
  content: string
  thinking: string
  tools: ToolCard[]
  references: SseReference[]
  status: 'streaming' | 'completed' | 'interrupted' | 'blocked' | 'failed'
  model?: string
  agent?: string
  tokensIn?: number
  tokensOut?: number
  latencyMs?: number
  firstTokenMs?: number
  toolCallCount?: number
  ragHits?: number
  errorMessage?: string
  /** 服务端业务码；6009 = 护栏拦截（prompt injection 等） */
  errorCode?: number
  errorTraceId?: string
  blockReason?: string
  notice?: string
  time?: string
  rating?: 'LIKE' | 'DISLIKE'
  /** 便于「重新生成」定位上一轮用户输入 */
  sourceUserText?: string
}

const auth = useAuthStore()
const { isMobile, isCompact } = useBreakpoint()

/** 超过这个字数即视为长回答，终态后默认折叠 */
const LONG_ANSWER_CHARS = 900

/* ------------------------------ 助手能力元数据 ------------------------------ */
const meta = ref<AgentMeta | null>(null)
/** 元数据加载失败（与"确实没有助手"要区分开） */
const metaError = ref('')

/** 助手能力卡片：普通助手 + 管理员专属助手 */
const agentCards = computed<AgentInfo[]>(() => {
  const list = [...(meta.value?.agents ?? [])]
  if (auth.isAdmin && meta.value?.adminAgents) list.push(...meta.value.adminAgents)
  return list
})

/** 空对话页的引导问题：优先用助手自带的 sample，不够时补几条通用问题 */
const suggestions = computed<string[]>(() => {
  const list = agentCards.value.map((a) => a.sample).filter((s): s is string => Boolean(s))
  const fallback = ['东南亚有哪些空房？', '提前退房有什么规定？', '宿舍费用报销需要哪些单据？']
  for (const f of fallback) if (list.length < 3 && !list.includes(f)) list.push(f)
  return list.slice(0, 4)
})

/* ------------------------------ 流式状态 ------------------------------ */
const stream = useAgentStream({
  onSettled: (phase, state) => finalizeTurn(phase, state),
})

const liveKey = ref<string | null>(null)
const liveSourceText = ref('')

/** 把流状态快照成一条普通消息 */
function snapshot(state: AgentStreamState, sourceText: string): BubbleMessage {
  let status: BubbleMessage['status'] = 'completed'
  if (state.phase === 'interrupted') status = 'interrupted'
  else if (state.phase === 'blocked') status = 'blocked'
  else if (state.phase === 'error') status = 'failed'

  return {
    key: `live-${Date.now()}`,
    id: String(state.messageId || `local-${Date.now()}`),
    role: 'assistant',
    content: state.content,
    thinking: state.thinking,
    // 兜底：把仍在转圈的工具卡片标记为结束
    tools: state.tools.map((t) => ({ ...t, running: false })),
    references: [...state.references],
    status,
    model: state.model,
    agent: state.agent,
    tokensIn: state.tokensIn,
    tokensOut: state.tokensOut,
    latencyMs: state.latencyMs,
    firstTokenMs: state.firstTokenMs,
    toolCallCount: state.toolCallCount || state.tools.length,
    ragHits: state.ragHits,
    errorMessage: state.errorMessage,
    errorCode: state.errorCode,
    errorTraceId: state.errorTraceId,
    blockReason: state.blockReason,
    notice: state.notice,
    time: new Date().toISOString(),
    sourceUserText: sourceText,
  }
}

/** 流进入终态：冻结为普通消息 */
function finalizeTurn(_phase: string, state: AgentStreamState): void {
  if (!liveKey.value) return
  const bubble = snapshot(state, liveSourceText.value)
  if (bubble.content || bubble.thinking || bubble.tools.length > 0 || bubble.errorMessage) {
    messages.value.push(bubble)
  }
  liveKey.value = null
  stream.reset()
  void scrollToBottom()
  void loadConversations()
}

/* ------------------------------ 消息与滚动 ------------------------------ */
const messages = ref<BubbleMessage[]>([])
const scrollRef = ref<HTMLElement | null>(null)

/** 正在流式生成的实时消息（由 stream.state 派生） */
const liveBubble = computed<BubbleMessage | null>(() => {
  const s = stream.state
  if (!liveKey.value || s.phase === 'idle') return null
  return {
    key: liveKey.value,
    id: String(s.messageId || ''),
    role: 'assistant',
    content: s.content,
    thinking: s.thinking,
    tools: s.tools,
    references: s.references,
    status: 'streaming',
    model: s.model,
    tokensIn: s.tokensIn,
    tokensOut: s.tokensOut,
    latencyMs: s.latencyMs,
    firstTokenMs: s.firstTokenMs,
    toolCallCount: s.toolCallCount || s.tools.length,
    ragHits: s.ragHits,
    notice: s.notice,
    sourceUserText: liveSourceText.value,
  }
})

const displayMessages = computed<BubbleMessage[]>(() => {
  const live = liveBubble.value
  return live ? [...messages.value, live] : messages.value
})

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const el = scrollRef.value
  if (el) el.scrollTop = el.scrollHeight
}

// 流式输出期间持续吸底
watch(
  () => [stream.state.content, stream.state.thinking, stream.state.tools.length],
  () => {
    void scrollToBottom()
  },
)

/* ============================================================================
 * 1) 流式光标：形态与「打字节奏」
 *
 * 恒定闪烁的光标传达不了"还在生成"与"卡住了"的区别。这里用**真实分片到达时刻**
 * 驱动节奏：内容长度变化后的 260ms 内光标处于 typing（快节奏、不透明），
 * 之后转入 waiting（慢节奏、半透明）—— 用户能一眼看出"模型还在吐字"还是"在等上游"。
 *   · 长内容（>120 字）时把光标换成更细的插入条，避免长文末尾出现一个大色块
 *   · prefers-reduced-motion 由 tokens.css 的全局规则统一降级（动效不能只做加法不做退让）
 * ========================================================================= */
const typingRhythm = ref<'typing' | 'waiting'>('waiting')
let typingTimer: ReturnType<typeof setTimeout> | null = null

watch(
  () => stream.state.content.length,
  (len, prev) => {
    if (len === (prev ?? 0)) return
    typingRhythm.value = 'typing'
    if (typingTimer) clearTimeout(typingTimer)
    typingTimer = setTimeout(() => {
      typingRhythm.value = 'waiting'
    }, 260)
  },
)

/* ============================================================================
 * 2) 「正在检索」骨架
 *
 * RAG 检索发生在**第一个 DELTA 之前**，这段窗口原来是空白（只有一个转圈），
 * 用户不知道系统在干什么。这里按"首字之前 = 检索阶段"来做内容形状提示：
 *   · connecting / 尚无任何正文与工具事件的 streaming → 展示检索骨架（分步文案）
 *   · 一旦有正文、思考或工具事件，立即让位给真实内容（不能拿骨架糊住已有内容）
 * ========================================================================= */
const retrievalPending = computed(() => {
  const s = stream.state
  if (!liveKey.value) return false
  if (s.phase === 'connecting' || s.phase === 'reconnecting') return true
  if (s.phase !== 'streaming') return false
  return !s.content && !s.thinking && s.tools.length === 0
})

/** 生成中已过去的秒数：等待越久，越需要给出"正在做什么"的交代 */
const streamingSeconds = ref(0)
let elapsedTimer: ReturnType<typeof setInterval> | null = null

const streaming = computed(
  () =>
    stream.state.phase === 'connecting' ||
    stream.state.phase === 'streaming' ||
    stream.state.phase === 'reconnecting',
)

watch(streaming, (on) => {
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
  if (!on) return
  streamingSeconds.value = 0
  elapsedTimer = setInterval(() => {
    streamingSeconds.value += 1
  }, 1000)
})

/** 检索阶段的分步文案：随着等待时间推进，告诉用户系统在哪一步 */
const retrievalStage = computed(() => {
  if (stream.state.phase === 'reconnecting') return '连接中断，正在续传已生成的内容…'
  if (stream.state.phase === 'connecting') return '正在连接助手…'
  if (streamingSeconds.value < 3) return '正在检索知识库…'
  if (streamingSeconds.value < 8) return '已命中相关资料，正在组织答案…'
  return '回答较长或上游较慢，仍在生成中…'
})

/* ============================================================================
 * 3) 长回答折叠
 * ========================================================================= */
const expanded = reactive<Record<string, boolean>>({})

function isLong(msg: BubbleMessage): boolean {
  return msg.content.length > LONG_ANSWER_CHARS
}

function isCollapsed(msg: BubbleMessage): boolean {
  // 生成中不折叠：用户要看到文字在流动；到终态后再收起，避免长回答把后续内容推到屏幕外
  return msg.status !== 'streaming' && isLong(msg) && !expanded[msg.key]
}

function toggleExpand(msg: BubbleMessage): void {
  expanded[msg.key] = !expanded[msg.key]
}

/* ============================================================================
 * 4) 引用溯源
 * ========================================================================= */
const refDrawerVisible = ref(false)
const activeReference = ref<SseReference | null>(null)
const activeReferenceIndex = ref(0)
/** 当前抽屉对应的引用集合（回答里点进来时用那一条的集合） */
const activeReferenceList = ref<SseReference[]>([])

function openReference(ref: SseReference, index: number, list: SseReference[]): void {
  activeReference.value = ref
  activeReferenceIndex.value = index
  activeReferenceList.value = list
  refDrawerVisible.value = true
}

function switchReference(index: number): void {
  const list = activeReferenceList.value
  if (index < 0 || index >= list.length) return
  activeReferenceIndex.value = index
  activeReference.value = list[index] ?? null
}

/* ============================================================================
 * 5) 工具调用卡片：进行中 / 成功 / 失败 + 入参展示
 * ========================================================================= */
const toolExpanded = reactive<Record<string, boolean>>({})

function toolState(tool: ToolCard): 'running' | 'ok' | 'failed' {
  if (tool.running) return 'running'
  return tool.failed ? 'failed' : 'ok'
}

function toolStateLabel(tool: ToolCard): string {
  const s = toolState(tool)
  if (s === 'running') return '进行中'
  return s === 'failed' ? '失败' : '成功'
}

function toolArgsText(tool: ToolCard): string {
  return tool.arguments && tool.arguments.length > 0 ? tool.arguments : '（无参数）'
}

/* ------------------------------ 会话管理 ------------------------------ */
const conversations = ref<ConversationVO[]>([])
const activeConversationId = ref<string>('')
const convLoading = ref(false)
const convError = ref('')
const creating = ref(false)
/** 移动端：会话列表抽屉开关 */
const sideOpen = ref(false)

const activeConversation = computed(() =>
  conversations.value.find((c) => String(c.id) === activeConversationId.value),
)

async function loadConversations(): Promise<void> {
  convLoading.value = true
  convError.value = ''
  try {
    const page = await agentApi.conversations({ current: 1, size: 50 })
    conversations.value = page.records ?? []
  } catch (err) {
    // 会话列表加载失败要能重试；清空列表会让用户以为"历史会话被清掉了"
    convError.value = err instanceof Error ? err.message : '会话列表加载失败'
    conversations.value = []
  } finally {
    convLoading.value = false
  }
}

/** 新建会话；标题用时间戳便于识别 */
async function handleNewConversation(): Promise<void> {
  creating.value = true
  try {
    const title = `新会话 ${new Date().toLocaleString('zh-CN', { hour12: false })}`
    const conv = await agentApi.createConversation(title)
    conversations.value.unshift(conv)
    activeConversationId.value = String(conv.id)
    messages.value = []
    inputText.value = ''
    sideOpen.value = false
  } finally {
    creating.value = false
  }
}

async function handleSelectConversation(id: string): Promise<void> {
  if (liveKey.value) {
    ElMessage.warning('当前回复正在生成，请先停止或等待完成')
    return
  }
  activeConversationId.value = id
  sideOpen.value = false
  await loadHistory(id)
}

/** 删除（归档）会话 */
async function handleDeleteConversation(conv: ConversationVO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除会话「${conv.title ?? conv.conversationNo}」吗？`, '删除会话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await agentApi.deleteConversation(conv.id)
  ElMessage.success('会话已删除')
  const wasActive = String(conv.id) === activeConversationId.value
  await loadConversations()
  if (wasActive) {
    activeConversationId.value = ''
    messages.value = []
  }
}

/** 解析历史工具调用（后端可能以 JSON 字符串存储） */
function parseToolCalls(raw: ChatMessageVO['toolCalls']): ToolCard[] {
  if (!raw) return []
  let records: ToolCallRecord[] = []
  if (typeof raw === 'string') {
    try {
      const parsed: unknown = JSON.parse(raw)
      if (Array.isArray(parsed)) records = parsed as ToolCallRecord[]
    } catch {
      return []
    }
  } else if (Array.isArray(raw)) {
    records = raw
  }
  return records.map((r, idx) => ({
    key: `hist-${idx}-${r.tool}`,
    tool: r.tool,
    // 历史接口不下发中文 label，退回工具名展示
    label: r.tool,
    arguments: formatArguments(r.arguments),
    running: false,
    failed: r.success === false,
  }))
}

const historyLoading = ref(false)
const historyError = ref('')

async function loadHistory(conversationId: string): Promise<void> {
  historyLoading.value = true
  historyError.value = ''
  try {
    const list = await agentApi.messages(conversationId)
    const bubbles: BubbleMessage[] = []
    for (const m of list ?? []) {
      // 后端返回的角色是大写（USER/ASSISTANT），这里统一归一化
      const role = normalizeRole(m.role)
      if (role === 'user') {
        bubbles.push({
          key: `h-${m.id}-u`,
          id: String(m.id),
          role: 'user',
          content: m.content ?? '',
          thinking: '',
          tools: [],
          references: [],
          status: 'completed',
          time: m.time,
        })
      } else if (role === 'assistant') {
        let status: BubbleMessage['status'] = 'completed'
        if (m.status === 'INTERRUPTED') status = 'interrupted'
        else if (m.status === 'BLOCKED') status = 'blocked'
        else if (m.status === 'FAILED') status = 'failed'
        // 遗留的 STREAMING 状态（例如上次生成时页面被关闭）按中断处理，允许重新生成
        else if (m.status === 'STREAMING') status = 'interrupted'
        bubbles.push({
          key: `h-${m.id}-a`,
          id: String(m.id),
          role: 'assistant',
          content: m.content ?? '',
          thinking: m.thinking ?? '',
          tools: parseToolCalls(m.toolCalls),
          references: m.references ?? [],
          status,
          model: m.model,
          tokensIn: m.tokensIn,
          tokensOut: m.tokensOut,
          latencyMs: m.latencyMs,
          firstTokenMs: m.firstTokenMs,
          blockReason: m.blockReason,
          errorMessage: status === 'interrupted' ? m.blockReason || '该回复未生成完成' : undefined,
          time: m.time,
          // 让「重新生成」能定位上一轮提问
          sourceUserText: [...bubbles].reverse().find((b) => b.role === 'user')?.content,
        })
      }
    }
    messages.value = bubbles
    await scrollToBottom()
  } catch (err) {
    historyError.value = err instanceof Error ? err.message : '历史消息加载失败'
    messages.value = []
  } finally {
    historyLoading.value = false
  }
}

/* ------------------------------ 发送 / 停止 / 重试 ------------------------------ */
const inputText = ref('')

const currentModel = computed(
  () => stream.state.model || activeConversation.value?.agentType || meta.value?.primaryModel || '默认模型',
)

async function handleSend(): Promise<void> {
  const text = inputText.value.trim()
  if (!text) return
  if (streaming.value) {
    ElMessage.warning('请等待当前回复完成或先停止生成')
    return
  }

  // 没有会话时先自动创建一个
  if (!activeConversationId.value) {
    try {
      const title = text.slice(0, 20)
      const conv = await agentApi.createConversation(title)
      conversations.value.unshift(conv)
      activeConversationId.value = String(conv.id)
    } catch {
      return
    }
  }

  const convId = activeConversationId.value
  messages.value.push({
    key: `u-${Date.now()}`,
    id: '',
    role: 'user',
    content: text,
    thinking: '',
    tools: [],
    references: [],
    status: 'completed',
    time: new Date().toISOString(),
  })
  inputText.value = ''
  liveSourceText.value = text
  liveKey.value = `live-${Date.now()}`
  await scrollToBottom()

  // turnId 在 composable 内生成，重连/重试复用同一个
  await stream.send(convId, text)
}

/** 打断：生成中随时可停（后端 move to INTERRUPTED，已生成的部分内容保留） */
async function handleStop(): Promise<void> {
  await stream.stop()
  ElMessage.success('已停止生成，已生成的内容会保留')
}

/** 重新生成 / 失败重试：用同一条用户提问再发一次（新的 turnId） */
async function handleRegenerate(bubble: BubbleMessage): Promise<void> {
  const text = bubble.sourceUserText
  if (!text) {
    ElMessage.warning('找不到对应的提问内容')
    return
  }
  if (streaming.value) {
    ElMessage.warning('请等待当前回复完成')
    return
  }
  inputText.value = text
  await handleSend()
}

/**
 * 失败原因的人话解释。
 * 关键：护栏拦截（code 6009）与上游故障是**两种完全不同的处置**——
 * 前者应提示用户改写提问（重试同样内容大概率仍被拦），后者才建议直接重试。
 */
function failureTitle(msg: BubbleMessage): string {
  if (msg.errorCode === 6009) return '请求被安全护栏拦截'
  if (msg.errorCode) return `生成失败（业务码 ${msg.errorCode}）`
  return '生成失败'
}

function failureHint(msg: BubbleMessage): string {
  if (msg.errorCode === 6009) {
    return '提问疑似包含提示词注入或越权指令，已被服务端护栏拦截。请改写提问后重新发送；直接重试同样的内容大概率仍会被拦截。'
  }
  return msg.errorMessage || '上游模型暂时不可用，可直接重试。'
}

/* ------------------------------ 消息操作 ------------------------------ */
const copiedKey = ref('')

async function handleCopy(bubble: BubbleMessage): Promise<void> {
  try {
    await navigator.clipboard.writeText(bubble.content)
    copiedKey.value = bubble.key
    ElMessage.success('已复制到剪贴板')
    setTimeout(() => {
      if (copiedKey.value === bubble.key) copiedKey.value = ''
    }, 2000)
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

/** 点赞 / 点踩 */
async function handleFeedback(bubble: BubbleMessage, rating: 'LIKE' | 'DISLIKE'): Promise<void> {
  if (!bubble.id || bubble.id.startsWith('local-')) {
    ElMessage.warning('该消息暂不支持反馈')
    return
  }
  let reason = ''
  if (rating === 'DISLIKE') {
    try {
      const res = await ElMessageBox.prompt('请说明不满意的原因（选填）', '点踩反馈', {
        confirmButtonText: '提交',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：回答不准确 / 没有找到房源',
      })
      reason = res.value ?? ''
    } catch {
      return
    }
  }
  try {
    await agentApi.feedback(bubble.id, rating, reason || undefined)
    bubble.rating = rating
    ElMessage.success(rating === 'LIKE' ? '感谢点赞' : '已记录反馈')
  } catch {
    /* 已提示 */
  }
}

/* ------------------------------ 页面初始化 ------------------------------ */

/** 点击能力卡片，把示例问题填入输入框 */
function fillSample(agent: AgentInfo): void {
  if (agent.sample) inputText.value = agent.sample
}

/** 点击空态里的引导问题：直接填入并发送 */
async function useSuggestion(text: string): Promise<void> {
  inputText.value = text
  await handleSend()
}

function formatMs(ms?: number): string {
  if (ms === undefined || ms === null) return '-'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(2)} s`
}

/** 重新拉取助手元数据（元数据加载失败时的重试入口） */
async function loadMeta(): Promise<void> {
  metaError.value = ''
  try {
    meta.value = await agentApi.meta()
  } catch (err) {
    meta.value = null
    metaError.value = err instanceof Error ? err.message : '助手元数据加载失败'
  }
}

onMounted(async () => {
  await loadMeta()
  await loadConversations()
})

onBeforeUnmount(() => {
  if (typingTimer) clearTimeout(typingTimer)
  if (elapsedTimer) clearInterval(elapsedTimer)
})
</script>

<template>
  <div class="agent-wrap" :class="{ 'is-narrow': isCompact, 'is-mobile': isMobile }">
    <!-- 小屏遮罩：点击空白处关闭会话抽屉 -->
    <div v-if="isMobile && sideOpen" class="agent-side-backdrop" @click="sideOpen = false" />

    <!-- ------------------------------ 左侧会话列表 ------------------------------ -->
    <aside class="agent-side" :class="{ 'is-drawer': isMobile, 'is-open': sideOpen }" data-test="agent-side">
      <div class="side-head">
        <el-button type="primary" style="width: 100%" :loading="creating" @click="handleNewConversation">
          <el-icon><Plus /></el-icon> 新建会话
        </el-button>
      </div>
      <el-scrollbar class="side-list">
        <!-- 会话列表同样走三态：加载骨架 / 失败可重试 / 确实没有会话 -->
        <AsyncState
          :loading="convLoading"
          :error="convError"
          :empty="conversations.length === 0"
          skeleton="list"
          :skeleton-rows="4"
          compact
          empty-title="暂无会话"
          empty-description="点击上方「新建会话」，或在下方输入框直接提问（会自动建会话）。"
          @retry="loadConversations"
        >
          <div
            v-for="conv in conversations"
            :key="conv.id"
            class="conv-item"
            :class="{ active: String(conv.id) === activeConversationId }"
            data-test="conv-item"
            @click="handleSelectConversation(String(conv.id))"
          >
            <div class="conv-main">
              <div class="conv-title">{{ conv.title || conv.conversationNo }}</div>
              <div class="conv-meta">
                <span>{{ conv.messageCount ?? 0 }} 条</span>
                <span v-if="conv.agentTypeLabel"> · {{ conv.agentTypeLabel }}</span>
              </div>
              <div class="conv-meta">{{ conv.lastMessageTime ?? '' }}</div>
            </div>
            <el-icon class="conv-del" @click.stop="handleDeleteConversation(conv)">
              <Delete />
            </el-icon>
          </div>
        </AsyncState>
      </el-scrollbar>
    </aside>

    <!-- ------------------------------ 中间对话区 ------------------------------ -->
    <section class="agent-main">
      <header class="chat-head">
        <div class="chat-head-left">
          <!-- 小屏：会话列表收成抽屉，用一个按钮唤出（三栏布局在 390px 下必然溢出） -->
          <el-button
            v-if="isMobile"
            class="side-toggle"
            text
            data-test="side-toggle"
            @click="sideOpen = !sideOpen"
          >
            <el-icon><Fold /></el-icon>
          </el-button>
          <b class="chat-title">{{ activeConversation?.title || 'AI 助手' }}</b>
          <el-tag size="small" effect="plain" class="model-tag">
            <el-icon><Cpu /></el-icon> {{ currentModel }}
          </el-tag>
          <el-tag v-if="meta?.ragEnabled" size="small" type="success" effect="plain" class="rag-tag">
            RAG 已启用
          </el-tag>
        </div>
        <div v-if="!isMobile" class="muted">
          限流：{{ meta?.limits?.turnsPerMinute ?? '-' }} 轮/分 · 单条上限
          {{ meta?.limits?.maxMessageLength ?? '-' }} 字
        </div>
      </header>

      <div ref="scrollRef" class="chat-scroll" data-test="chat-log">
        <!-- 历史加载：骨架（形状预示消息流）/ 失败（可重试）/ 空（给引导问题） -->
        <AsyncState
          :loading="historyLoading && messages.length === 0"
          :error="historyError"
          :empty="displayMessages.length === 0 && !liveBubble"
          skeleton="list"
          :skeleton-rows="3"
          data-test="chat-state"
          empty-title="向 AI 助手提问"
          empty-description="可以问房源、费用、退房规定等；回答会引用知识库文档（可点开溯源）。"
          @retry="activeConversationId && loadHistory(activeConversationId)"
        >
          <template #empty-action>
            <div class="suggestions" data-test="empty-suggestions">
              <el-button
                v-for="s in suggestions"
                :key="s"
                size="small"
                plain
                class="suggestion-chip"
                @click="useSuggestion(s)"
              >
                {{ s }}
              </el-button>
            </div>
          </template>

          <div v-for="msg in displayMessages" :key="msg.key" class="bubble-row" :class="msg.role">
            <!-- 用户气泡 -->
            <div v-if="msg.role === 'user'" class="bubble user">{{ msg.content }}</div>

            <!-- 助手气泡 -->
            <div v-else class="bubble assistant" data-test="assistant-bubble">
              <!-- 断线续传提示 -->
              <el-alert
                v-if="msg.status === 'streaming' && stream.state.connectionLost"
                class="reconnect-tip"
                type="warning"
                :closable="false"
                show-icon
                title="连接中断，正在续传…"
                :description="`已自动重试 ${stream.state.retryCount} 次（指数退避，最长 10 秒）`"
              />
              <el-alert
                v-else-if="msg.status === 'streaming' && stream.state.recovered"
                class="reconnect-tip"
                type="success"
                :closable="false"
                show-icon
                title="已恢复"
                description="断线期间的增量内容已按 seq 去重补齐，未重复也未丢失"
              />

              <!-- 2) 「正在检索」骨架：RAG 检索阶段给出内容形状与分步文案，而不是一个转圈 -->
              <div v-if="retrievalPending" class="retrieval" data-test="retrieval-skeleton" role="status" aria-live="polite">
                <div class="retrieval-head">
                  <el-icon class="is-loading"><Search /></el-icon>
                  <span data-test="retrieval-stage">{{ retrievalStage }}</span>
                  <span class="muted">{{ streamingSeconds }}s</span>
                </div>
                <div class="retrieval-steps">
                  <span class="retrieval-step" />
                  <span class="retrieval-step" />
                  <span class="retrieval-step retrieval-step--wide" />
                </div>
              </div>

              <!-- 思考过程折叠区 -->
              <el-collapse v-if="msg.thinking" style="margin-bottom: 10px">
                <el-collapse-item name="thinking">
                  <template #title>
                    <span class="muted"><el-icon><MagicStick /></el-icon> 思考过程</span>
                  </template>
                  <div class="thinking-box">{{ msg.thinking }}</div>
                </el-collapse-item>
              </el-collapse>

              <!-- 1) 工具调用卡片：工具名 / 入参 / 结果 / 进行中-成功-失败 -->
              <div v-if="msg.tools.length > 0" class="tool-block" data-test="tool-block">
                <div class="muted tool-block-title">
                  <el-icon><Tools /></el-icon> 工具调用（{{ msg.tools.length }}）
                </div>
                <div
                  v-for="tool in msg.tools"
                  :key="tool.key"
                  class="tool-card"
                  :class="`is-${toolState(tool)}`"
                  data-test="tool-card"
                  :data-tool-name="tool.tool"
                  :data-tool-state="toolState(tool)"
                >
                  <div class="tool-card-head">
                    <el-icon v-if="tool.running" class="is-loading" data-test="tool-spinner"><Loading /></el-icon>
                    <el-icon v-else-if="tool.failed"><CircleCloseFilled /></el-icon>
                    <el-icon v-else><CircleCheckFilled /></el-icon>
                    <span class="tool-card-label">{{ tool.label }}</span>
                    <span class="muted mono tool-card-name">{{ tool.tool }}</span>
                    <span class="tool-card-state">{{ toolStateLabel(tool) }}</span>
                  </div>
                  <div class="tool-card-args">
                    <div class="tool-card-args-head">
                      <span class="muted">入参</span>
                      <el-button
                        v-if="toolArgsText(tool).length > 60"
                        link
                        size="small"
                        @click="toolExpanded[tool.key] = !toolExpanded[tool.key]"
                      >
                        {{ toolExpanded[tool.key] ? '收起' : '展开' }}
                      </el-button>
                    </div>
                    <pre
                      class="tool-card-args-body mono"
                      :class="{ 'is-clamped': toolArgsText(tool).length > 60 && !toolExpanded[tool.key] }"
                    >{{ toolArgsText(tool) }}</pre>
                  </div>
                  <div v-if="tool.failed" class="tool-card-result" data-test="tool-result">
                    结果：工具执行失败，本轮回答可能缺少这部分信息。
                  </div>
                  <div v-else-if="!tool.running" class="tool-card-result" data-test="tool-result">
                    结果：已返回并用于生成本次回答。
                  </div>
                </div>
              </div>

              <!-- 正文（DELTA 增量渲染） -->
              <div
                class="answer"
                :class="{
                  'answer-clamp': isCollapsed(msg),
                  'cursor-blink': msg.status === 'streaming',
                  'is-typing': msg.status === 'streaming' && typingRhythm === 'typing',
                  'is-long': isLong(msg),
                }"
                data-test="answer"
                :data-collapsed="isCollapsed(msg) ? 'true' : 'false'"
                :data-streaming="msg.status === 'streaming' ? 'true' : 'false'"
              >
                <MessageMarkdown
                  v-if="msg.content || msg.status !== 'streaming'"
                  :text="msg.content || '（无正文内容）'"
                  :streaming="msg.status === 'streaming'"
                />
              </div>

              <!-- 长回答折叠开关 -->
              <el-button
                v-if="isLong(msg) && msg.status !== 'streaming'"
                link
                type="primary"
                size="small"
                class="answer-toggle"
                data-test="answer-toggle"
                @click="toggleExpand(msg)"
              >
                <el-icon><ArrowDown v-if="isCollapsed(msg)" /><ArrowUp v-else /></el-icon>
                {{ isCollapsed(msg) ? `展开全文（共 ${msg.content.length} 字）` : '收起' }}
              </el-button>

              <!-- 拦截 / 中断 / 失败提示（均带重试入口） -->
              <el-alert
                v-if="msg.status === 'blocked'"
                type="error"
                :closable="false"
                show-icon
                class="status-alert"
                data-test="status-blocked"
                title="内容被安全策略拦截"
                :description="msg.blockReason || '请改写提问后重试。'"
              />
              <el-alert
                v-else-if="msg.status === 'interrupted'"
                type="warning"
                :closable="false"
                show-icon
                class="status-alert"
                data-test="status-interrupted"
                title="生成已中断"
                :description="msg.errorMessage || '你可以点击「重新生成」继续，已生成的内容会保留。'"
              />
              <!-- 失败态：区分护栏拦截（6009）与上游故障 -->
              <div v-else-if="msg.status === 'failed'" class="status-error" data-test="status-failed">
                <el-alert
                  type="error"
                  :closable="false"
                  show-icon
                  :title="failureTitle(msg)"
                  :description="failureHint(msg)"
                  data-test="failed-hint"
                />
                <div class="status-error-actions">
                  <el-button
                    v-if="msg.errorCode !== 6009"
                    size="small"
                    type="primary"
                    plain
                    data-test="agent-retry"
                    @click="handleRegenerate(msg)"
                  >
                    <el-icon><RefreshRight /></el-icon> 重试
                  </el-button>
                  <el-button v-else size="small" plain data-test="agent-rewrite" @click="inputText = msg.sourceUserText ?? ''">
                    <el-icon><EditPen /></el-icon> 取回提问并改写
                  </el-button>
                  <span v-if="msg.errorCode || msg.errorTraceId" class="muted mono error-meta">
                    <template v-if="msg.errorCode">code={{ msg.errorCode }}</template>
                    <template v-if="msg.errorTraceId"> · traceId={{ msg.errorTraceId }}</template>
                  </span>
                </div>
              </div>
              <el-alert
                v-else-if="msg.notice && msg.status === 'streaming'"
                type="info"
                :closable="false"
                show-icon
                class="status-alert"
                :title="msg.notice"
              />

              <!-- 4) 引用溯源：回答里的知识库引用可点开看到来源文档 -->
              <div v-if="msg.references.length > 0" class="ref-block" data-test="ref-block">
                <div class="muted" style="margin-bottom: 6px">
                  <el-icon><Collection /></el-icon> 参考来源（{{ msg.references.length }}）· 点击查看来源文档
                </div>
                <div class="ref-chips">
                  <button
                    v-for="(ref, i) in msg.references"
                    :key="i"
                    type="button"
                    class="ref-chip"
                    data-test="ref-chip"
                    :data-ref-index="i"
                    @click="openReference(ref, i, msg.references)"
                  >
                    <span class="ref-chip-index">[{{ i + 1 }}]</span>
                    <span class="ref-chip-title">{{ ref.title || `来源 ${i + 1}` }}</span>
                  </button>
                </div>
              </div>

              <!-- 底部操作条 -->
              <div v-if="msg.status !== 'streaming'" class="bubble-actions">
                <el-button link size="small" @click="handleCopy(msg)">
                  <el-icon><DocumentCopy /></el-icon>
                  {{ copiedKey === msg.key ? '已复制' : '复制' }}
                </el-button>
                <el-button link size="small" data-test="regen" @click="handleRegenerate(msg)">
                  <el-icon><RefreshRight /></el-icon> 重新生成
                </el-button>
                <el-button
                  link
                  size="small"
                  :type="msg.rating === 'LIKE' ? 'success' : 'default'"
                  @click="handleFeedback(msg, 'LIKE')"
                >
                  <el-icon><Select /></el-icon> 点赞
                </el-button>
                <el-button
                  link
                  size="small"
                  :type="msg.rating === 'DISLIKE' ? 'danger' : 'default'"
                  @click="handleFeedback(msg, 'DISLIKE')"
                >
                  <el-icon><CloseBold /></el-icon> 点踩
                </el-button>
                <span class="muted">
                  <el-icon><Timer /></el-icon> 耗时 {{ formatMs(msg.latencyMs) }}
                  · 首字 {{ formatMs(msg.firstTokenMs) }}
                  · tokens {{ msg.tokensIn ?? 0 }}/{{ msg.tokensOut ?? 0 }}
                  <template v-if="msg.toolCallCount"> · 工具 {{ msg.toolCallCount }}</template>
                  <template v-if="msg.ragHits"> · 召回 {{ msg.ragHits }}</template>
                  <template v-if="msg.model"> · {{ msg.model }}</template>
                </span>
              </div>

              <!-- 生成中：只显示一个明确的"进行中"指示，避免和上面的骨架重复 -->
              <div v-if="msg.status === 'streaming' && !retrievalPending" class="stream-status">
                <span class="stream-dot" />
                生成中… {{ streamingSeconds }}s
                <span class="muted">· 可随时点「停止生成」打断</span>
              </div>
            </div>
          </div>
        </AsyncState>
      </div>

      <!-- 输入区 -->
      <footer class="chat-input">
        <el-input
          v-model="inputText"
          type="textarea"
          :rows="isMobile ? 2 : 3"
          resize="none"
          :maxlength="meta?.limits?.maxMessageLength ?? 4000"
          show-word-limit
          placeholder="输入问题，Enter 发送，Shift + Enter 换行"
          @keydown.enter.exact.prevent="handleSend"
        />
        <div class="input-actions">
          <span class="muted input-hint">
            当前模型：<b>{{ currentModel }}</b>
            <template v-if="meta?.limits?.maxStreamsPerUser">
              · 并发上限 {{ meta.limits.maxStreamsPerUser }}
            </template>
          </span>
          <div>
            <el-button v-if="streaming" type="danger" data-test="agent-stop" @click="handleStop">
              <el-icon><VideoPause /></el-icon> 停止生成
            </el-button>
            <el-button v-else type="primary" :disabled="!inputText.trim()" @click="handleSend">
              <el-icon><Promotion /></el-icon> 发送
            </el-button>
          </div>
        </div>
      </footer>
    </section>

    <!-- ------------------------------ 右侧能力说明（窄屏隐藏，避免三栏挤压） ------------------------------ -->
    <aside v-if="!isCompact" class="agent-right">
      <h4 class="side-title">助手能做什么</h4>
      <el-scrollbar class="cap-list">
        <el-card
          v-for="agent in agentCards"
          :key="agent.type"
          shadow="hover"
          class="cap-card"
          @click="fillSample(agent)"
        >
          <div class="cap-head">
            <b>{{ agent.label }}</b>
            <el-tag size="small" effect="plain">{{ agent.type }}</el-tag>
          </div>
          <div class="muted">{{ agent.description }}</div>
          <div v-if="agent.sample" class="cap-sample">「{{ agent.sample }}」</div>
        </el-card>
        <!-- 元数据加载失败 vs 确实没有助手：语义不同，分开渲染 -->
        <AsyncState
          v-if="agentCards.length === 0"
          :error="metaError"
          empty
          skeleton="card"
          :skeleton-rows="2"
          compact
          empty-title="未获取到助手信息"
          empty-description="助手元数据为空，请联系管理员检查 Agent 配置。"
          @retry="loadMeta"
        />
      </el-scrollbar>
      <div class="muted" style="padding: 8px 4px">点击卡片可把示例问题填入输入框。</div>
    </aside>

    <!-- ------------------------------ 引用溯源抽屉 ------------------------------ -->
    <el-drawer v-model="refDrawerVisible" title="引用来源" size="520px" data-test="ref-drawer">
      <template v-if="activeReference">
        <div class="ref-detail-head">
          <el-tag size="small" effect="plain">[{{ activeReferenceIndex + 1 }}]</el-tag>
          <b data-test="ref-detail-title">{{ activeReference.title || `来源 ${activeReferenceIndex + 1}` }}</b>
        </div>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="ref-detail-note"
          title="以下为该引用在知识库中的原文片段，助手在生成本次回答时参考了它。"
        />
        <div class="ref-detail-body" data-test="ref-detail-snippet">{{ activeReference.snippet || '（该引用未返回原文片段）' }}</div>

        <template v-if="activeReferenceList.length > 1">
          <el-divider content-position="left">本次回答的全部引用（{{ activeReferenceList.length }}）</el-divider>
          <div class="ref-chips">
            <button
              v-for="(ref, i) in activeReferenceList"
              :key="i"
              type="button"
              class="ref-chip"
              :class="{ 'is-active': i === activeReferenceIndex }"
              data-test="ref-item"
              @click="switchReference(i)"
            >
              <span class="ref-chip-index">[{{ i + 1 }}]</span>
              <span class="ref-chip-title">{{ ref.title || `来源 ${i + 1}` }}</span>
            </button>
          </div>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
/* ============================================================================
 * 布局：三栏（会话列表 / 对话 / 能力说明）
 * 窄屏（≤1024px）去掉右栏；小屏（≤768px）把会话列表改为离屏抽屉。
 * 实测改造前 390px 下右栏右边缘 x=520（超出视口 130px），整页横向溢出。
 * ========================================================================= */
.agent-wrap {
  display: flex;
  height: calc(100vh - 56px);
  background: var(--d-surface-page);
}

.agent-side {
  width: 250px;
  flex: 0 0 250px;
  background: var(--d-surface-card);
  border-right: var(--d-border-width) solid var(--d-border);
  display: flex;
  flex-direction: column;
}

.side-head {
  padding: var(--d-space-3);
  border-bottom: var(--d-border-width) solid var(--d-border-subtle);
}

.side-list {
  flex: 1;
  overflow: hidden;
}

.conv-item {
  display: flex;
  align-items: center;
  gap: var(--d-space-1);
  padding: 10px 12px;
  border-bottom: var(--d-border-width) solid var(--d-border-subtle);
  cursor: pointer;
  transition: background-color var(--d-duration-fast) var(--d-ease-standard);
}

.conv-item:hover {
  background: var(--d-surface-sunken);
}

.conv-item.active {
  background: var(--d-brand-subtle);
  border-left: 3px solid var(--d-brand);
}

.conv-main {
  flex: 1;
  min-width: 0;
}

.conv-title {
  font-size: var(--d-font-size-sm);
  font-weight: var(--d-font-weight-semibold);
  color: var(--d-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-meta {
  font-size: var(--d-font-size-2xs);
  color: var(--d-text-tertiary);
}

.conv-del {
  color: var(--d-text-placeholder);
}

.conv-del:hover {
  color: var(--d-danger);
}

.agent-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--d-surface-card);
}

.chat-head {
  min-height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--d-space-2);
  padding: var(--d-space-2) var(--d-space-4);
  border-bottom: var(--d-border-width) solid var(--d-border);
  flex-wrap: wrap;
}

.chat-head-left {
  display: flex;
  align-items: center;
  gap: var(--d-space-2);
  min-width: 0;
}

.chat-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 40vw;
}

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: var(--d-space-4) var(--d-space-5);
}

.chat-input {
  border-top: var(--d-border-width) solid var(--d-border);
  padding: 10px 16px 12px;
}

.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--d-space-2);
  margin-top: var(--d-space-2);
  flex-wrap: wrap;
}

.agent-right {
  width: 270px;
  flex: 0 0 270px;
  background: var(--d-surface-card);
  border-left: var(--d-border-width) solid var(--d-border);
  display: flex;
  flex-direction: column;
  padding: var(--d-space-3);
}

.side-title {
  margin: 0 0 10px;
  font-size: var(--d-font-size-md);
  color: var(--d-text-primary);
}

.cap-list {
  flex: 1;
  overflow: hidden;
}

.cap-card {
  margin-bottom: var(--d-space-2);
  cursor: pointer;
}

.cap-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--d-space-1);
}

.cap-sample {
  margin-top: var(--d-space-1);
  font-size: var(--d-font-size-xs);
  color: var(--d-brand);
}

/* ---------------------------------------------------------------- 空态建议 */
.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--d-space-2);
  justify-content: center;
  max-width: 46ch;
}

.suggestion-chip {
  white-space: normal;
  height: auto;
  padding: 6px 12px;
}

/* ------------------------------------------------------------------ 检索骨架 */
.retrieval {
  margin-bottom: var(--d-space-3);
  padding: var(--d-space-3);
  border: var(--d-border-width) solid var(--d-border-subtle);
  border-radius: var(--d-radius-lg);
  background: var(--d-surface-sunken);
}

.retrieval-head {
  display: flex;
  align-items: center;
  gap: var(--d-space-2);
  font-size: var(--d-font-size-sm);
  color: var(--d-text-secondary);
}

.retrieval-steps {
  display: flex;
  flex-direction: column;
  gap: var(--d-space-2);
  margin-top: var(--d-space-3);
}

.retrieval-step {
  display: block;
  height: 12px;
  width: 62%;
  border-radius: var(--d-radius-sm);
  background: linear-gradient(
    90deg,
    var(--d-surface-sunken) 25%,
    var(--d-border-subtle) 37%,
    var(--d-surface-sunken) 63%
  );
  background-size: 400% 100%;
  animation: retrieval-shimmer 1.4s var(--d-ease-standard) infinite;
}

.retrieval-step--wide {
  width: 88%;
}

@keyframes retrieval-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

/* ---------------------------------------------------------------- 工具调用卡片 */
.tool-block {
  margin-bottom: var(--d-space-3);
}

.tool-block-title {
  margin-bottom: var(--d-space-1);
}

.tool-card {
  border: var(--d-border-width) solid var(--d-border);
  border-left: var(--d-border-width-strong) solid var(--d-text-placeholder);
  border-radius: var(--d-radius-md);
  padding: var(--d-space-2) 10px;
  margin-bottom: var(--d-space-1);
  font-size: var(--d-font-size-sm);
  background: var(--d-surface-card);
}

.tool-card.is-running {
  border-left-color: var(--d-brand);
}

.tool-card.is-ok {
  border-left-color: var(--d-success);
}

.tool-card.is-failed {
  border-left-color: var(--d-danger);
  background: var(--d-danger-subtle);
}

.tool-card-head {
  display: flex;
  align-items: center;
  gap: var(--d-space-2);
}

.tool-card-label {
  font-weight: var(--d-font-weight-medium);
  color: var(--d-text-primary);
}

.tool-card-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-card-state {
  font-size: var(--d-font-size-xs);
  color: var(--d-text-tertiary);
  white-space: nowrap;
}

.tool-card.is-running .tool-card-state {
  color: var(--d-brand);
}

.tool-card.is-failed .tool-card-state {
  color: var(--d-danger-text);
}

.tool-card-args {
  margin-top: var(--d-space-1);
}

.tool-card-args-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--d-font-size-xs);
}

.tool-card-args-body {
  margin: 2px 0 0;
  padding: 6px 8px;
  background: var(--d-surface-sunken);
  border-radius: var(--d-radius-sm);
  font-size: var(--d-font-size-xs);
  color: var(--d-text-secondary);
  white-space: pre-wrap;
  word-break: break-all;
  overflow: hidden;
}

.tool-card-args-body.is-clamped {
  max-height: 34px;
}

.tool-card-result {
  margin-top: var(--d-space-1);
  font-size: var(--d-font-size-xs);
  color: var(--d-text-tertiary);
}

/* ------------------------------------------------------------------ 流式光标 */
/* 光标形态随节奏变化：typing（刚收到分片）用实心块 + 快节奏；
   waiting（等待上游）用半透明插入条 + 慢节奏。 */
.answer {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* 正文改用 Markdown 渲染后，换行交给 HTML 结构，不再由 pre-wrap 撑开 */
.answer :deep(.md-body) {
  white-space: normal;
}

.cursor-blink::after {
  content: '▍';
  margin-left: 1px;
  color: var(--d-brand);
  opacity: 0.55;
  animation: cursor-wait 1.1s steps(2, start) infinite;
}

.cursor-blink.is-typing::after {
  content: '▊';
  opacity: 1;
  animation-duration: 0.42s;
}

@keyframes cursor-wait {
  to { visibility: hidden; }
}

/* ---------------------------------------------------------------- 长回答折叠 */
.answer-clamp {
  position: relative;
  max-height: 320px;
  overflow: hidden;
}

.answer-clamp::after {
  content: '';
  position: absolute;
  inset: auto 0 0 0;
  height: 64px;
  background: linear-gradient(to bottom, transparent, var(--d-surface-card));
  pointer-events: none;
}

.answer-toggle {
  margin-top: var(--d-space-1);
}

/* ------------------------------------------------------------------ 状态提示 */
.status-alert,
.status-error {
  margin-top: var(--d-space-3);
}

.status-error-actions {
  display: flex;
  align-items: center;
  gap: var(--d-space-2);
  flex-wrap: wrap;
  margin-top: var(--d-space-2);
}

.error-meta {
  overflow-wrap: anywhere;
}

.stream-status {
  display: flex;
  align-items: center;
  gap: var(--d-space-2);
  margin-top: var(--d-space-2);
  font-size: var(--d-font-size-xs);
  color: var(--d-text-tertiary);
}

.stream-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--d-radius-pill);
  background: var(--d-brand);
  animation: stream-pulse 1.4s var(--d-ease-standard) infinite;
}

@keyframes stream-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.35; transform: scale(0.72); }
}

/* ------------------------------------------------------------------ 引用溯源 */
.ref-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--d-space-2);
}

.ref-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 4px 10px;
  border: var(--d-border-width) solid var(--d-border-brand);
  border-radius: var(--d-radius-pill);
  background: var(--d-brand-subtle);
  color: var(--d-text-primary);
  font-size: var(--d-font-size-xs);
  cursor: pointer;
  transition: background-color var(--d-duration-fast) var(--d-ease-standard);
}

.ref-chip:hover,
.ref-chip.is-active {
  background: var(--d-brand);
  color: var(--d-text-on-brand);
}

.ref-chip-index {
  font-family: var(--d-font-mono);
  font-weight: var(--d-font-weight-semibold);
}

.ref-chip-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 30ch;
}

.ref-detail-head {
  display: flex;
  align-items: center;
  gap: var(--d-space-2);
  margin-bottom: var(--d-space-2);
  color: var(--d-text-primary);
}

.ref-detail-note {
  margin-bottom: var(--d-space-3);
}

.ref-detail-body {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: var(--d-font-size-sm);
  line-height: var(--d-line-relaxed);
  color: var(--d-text-secondary);
  background: var(--d-surface-sunken);
  border-radius: var(--d-radius-md);
  padding: var(--d-space-3);
}

/* ============================================================================
 * 小屏（≤768px）：会话列表改为离屏抽屉，输入区与气泡贴合屏幕
 * ========================================================================= */
.agent-side.is-drawer {
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  z-index: var(--d-z-overlay);
  height: 100vh;
  transform: translateX(-100%);
  transition: transform var(--d-duration-normal) var(--d-ease-standard);
  box-shadow: var(--d-shadow-lg);
}

.agent-side.is-drawer.is-open {
  transform: translateX(0);
}

.agent-side-backdrop {
  position: fixed;
  inset: 0;
  z-index: calc(var(--d-z-overlay) - 1);
  background: rgba(15, 23, 42, 0.45);
}

.agent-wrap.is-mobile .chat-scroll {
  padding: var(--d-space-3);
}

.agent-wrap.is-mobile .chat-input {
  padding: var(--d-space-2) var(--d-space-3) var(--d-space-3);
}

.agent-wrap.is-mobile .chat-title {
  max-width: 34vw;
}

.agent-wrap.is-mobile .input-hint {
  display: none;
}

/* 窄屏下气泡占满整行：78% 的限宽在 390px 上会让文字频繁折行 */
.agent-wrap.is-mobile :deep(.bubble) {
  max-width: 100%;
}
</style>
