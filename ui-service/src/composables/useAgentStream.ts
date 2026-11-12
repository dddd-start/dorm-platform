import { reactive, ref } from 'vue'
import { agentApi, AGENT_STREAM_PATH, agentStreamResumePath } from '@/api/agent'
import { TOKEN_KEY } from '@/api/http'
import { backoffDelay, extractFrames, parseFrame, type SseFrame } from '@/utils/sse'
import { formatArguments } from '@/api/types'
import type {
  LongId,
  SseBlockedData,
  SseDoneData,
  SseErrorData,
  SseInterruptedData,
  SseReference,
  SseStartData,
  SseToolData,
  ToolCallView,
} from '@/api/types'

/**
 * AI 助手流式对话 composable。
 *
 * 需求要点（全部在此实现）：
 * 1. 因为需要 POST + 自定义 Authorization 头，不能使用 EventSource，
 *    改为 fetch + response.body.getReader() 手动解析 SSE 帧。
 * 2. 按 `id:`（seq）去重：只接受 seq 大于已处理最大 seq 的帧，
 *    断线续传时服务端会重放，靠这个保证「不重复、不丢失」。
 * 3. 断线自动重连：指数退避 1s → 2s → 4s，上限 10s；
 *    重连时把已收到的最大 seq 作为 lastEventId 带上。
 * 4. 收到 DONE / BLOCKED / INTERRUPTED / ERROR 后停止重连。
 * 5. turnId 由 crypto.randomUUID() 生成，重连/重试复用同一个。
 * 6. 忽略 15 秒一次的 `:ping` 心跳注释帧。
 */

/** 流式状态机 */
export type StreamPhase =
  | 'idle'
  | 'connecting'
  | 'streaming'
  | 'reconnecting'
  | 'done'
  | 'blocked'
  | 'interrupted'
  | 'error'

/** 对 UI 友好的工具调用过程卡片 */
export interface ToolCard extends ToolCallView {
  key: string
  tool: string
  label: string
  arguments?: string
  running: boolean
  failed: boolean
}

/** 流式会话的响应式状态，页面直接绑定渲染 */
export interface AgentStreamState {
  phase: StreamPhase
  /** 模型思考过程累计文本（REASONING） */
  thinking: string
  /** 答案正文累计文本（DELTA） */
  content: string
  /** 工具调用过程卡片 */
  tools: ToolCard[]
  /** RAG 引用来源 */
  references: SseReference[]
  messageId: LongId | ''
  conversationId: LongId | ''
  turnId: string
  model: string
  agent: string
  tokensIn: number
  tokensOut: number
  latencyMs: number
  firstTokenMs: number
  toolCallCount: number
  ragHits: number
  status: string
  notice: string
  errorMessage: string
  blockReason: string
  /**
   * 服务端错误业务码（只读扩展，不改变去重/续传/退避逻辑）。
   *
   * 为什么需要它：护栏拦截 prompt injection 时后端发的是 `event:ERROR` + `code:6009`
   * 且**不发 START 帧** —— 前端若只展示 message，用户与排障者都分不清
   * "模型超时"和"请求被安全策略拦截"。有了 code 就能给出准确文案与处理建议。
   */
  errorCode: number
  /** 服务端 traceId（排障用，展示在错误态里） */
  errorTraceId: string
  /** 是否处于「连接中断，正在续传」提示态 */
  connectionLost: boolean
  /** 续传成功后短暂展示「已恢复」 */
  recovered: boolean
  /** 重连尝试次数 */
  retryCount: number
}

function createState(): AgentStreamState {
  return {
    phase: 'idle',
    thinking: '',
    content: '',
    tools: [],
    references: [],
    messageId: '',
    conversationId: '',
    turnId: '',
    model: '',
    agent: '',
    tokensIn: 0,
    tokensOut: 0,
    latencyMs: 0,
    firstTokenMs: 0,
    toolCallCount: 0,
    ragHits: 0,
    status: '',
    notice: '',
    errorMessage: '',
    blockReason: '',
    errorCode: 0,
    errorTraceId: '',
    connectionLost: false,
    recovered: false,
    retryCount: 0,
  }
}

/**
 * 退避序列与 SSE 帧解析已在 `@/utils/sse` 中实现（纯函数，可单独验证），
 * 这里重新导出以保持使用方只需引入本 composable。
 */
export { backoffDelay, extractFrames, parseFrame }
export type { SseFrame }

export interface UseAgentStreamOptions {
  /** 每次状态变化时的回调，便于页面把结果落到消息列表 */
  onPhaseChange?: (phase: StreamPhase, state: AgentStreamState) => void
  /** 续传成功后回调 */
  onRecovered?: () => void
  /** 终态回调 */
  onSettled?: (phase: StreamPhase, state: AgentStreamState) => void
  /** 最大重连次数，超过则进入 error */
  maxRetries?: number
}

export function useAgentStream(options: UseAgentStreamOptions = {}) {
  const state = reactive<AgentStreamState>(createState())
  /** 已处理的最大 seq，用于去重与断点续传 */
  const lastSeq = ref(0)
  const maxRetries = options.maxRetries ?? 8

  let controller: AbortController | null = null
  /**
   * 轮次代号。
   *
   * 为什么需要它（这是一个真实存在过的竞态，属于本轮交互打磨暴露出来的问题）：
   *   `settle()` 会**同步**回调 `onSettled`，页面在回调里把终态消息落库后调用 `reset()`；
   *   而 `reset()` 会把 settled/userStopped 复位。此时 connect() 的读取循环还没返回，
   *   它回到 `run()` 后看到 `!settled && !userStopped`，就把"正常结束"误判成"断线"，
   *   于是发起一次**多余的续传请求**并把 phase 置成 reconnecting —— 页面表现为
   *   "回答已经结束了，但停止按钮还在、发送被拒绝"。
   * 修法：给每一轮请求打一个代号，reset() 自增；遗留循环发现代号已变就直接返回，
   * 不再影响新一轮的状态。去重/续传/退避逻辑本身不变。
   */
  let generation = 0
  function isStale(gen: number): boolean {
    return gen !== generation
  }
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let recoveredTimer: ReturnType<typeof setTimeout> | null = null
  /** 是否已被用户主动停止 */
  let userStopped = false
  /** 是否已进入终态（终态后不再重连） */
  let settled = false
  /** 防止同一实例并发开流 */
  let streaming = false

  function setPhase(phase: StreamPhase): void {
    state.phase = phase
    options.onPhaseChange?.(phase, state)
  }

  function settle(phase: StreamPhase): void {
    if (settled) return
    settled = true
    streaming = false
    state.connectionLost = false
    setPhase(phase)
    options.onSettled?.(phase, state)
  }

  /** 读取一个 SSE 帧并分发 */
  function handleFrame(frame: SseFrame): void {
    // 去重：只接受比已处理最大 seq 更大的帧
    if (frame.id !== null) {
      if (frame.id <= lastSeq.value) return
      lastSeq.value = frame.id
    }
    // 断线后重新收到新帧 = 续传成功，提示「已恢复」
    if (state.connectionLost) markRecovered()
    if (frame.event === 'message' && frame.data.length === 0) return
    if (!frame.data) return

    let payload: unknown
    try {
      payload = JSON.parse(frame.data)
    } catch {
      return
    }

    switch (frame.event) {
      case 'START': {
        const d = payload as SseStartData
        state.messageId = d.messageId ?? ''
        state.conversationId = d.conversationId ?? ''
        if (d.turnId) state.turnId = d.turnId
        if (d.notice) state.notice = d.notice
        break
      }
      case 'REASONING': {
        // 思考过程与正文分开累加
        const text = (payload as { text?: string }).text ?? ''
        state.thinking += text
        break
      }
      case 'DELTA': {
        const text = (payload as { text?: string }).text ?? ''
        state.content += text
        break
      }
      case 'TOOL': {
        const d = payload as SseToolData
        if (d.phase === 'start') {
          state.tools.push({
            key: `${d.tool}-${state.tools.length}`,
            tool: d.tool,
            label: d.label || d.tool,
            // 入参后端以对象下发，统一格式化成可读字符串
            arguments: formatArguments(d.arguments),
            running: true,
            failed: false,
          })
        } else {
          // 结束态：回填最后一个同名且仍在运行的工具卡片
          for (let i = state.tools.length - 1; i >= 0; i--) {
            const card = state.tools[i]
            if (card && card.tool === d.tool && card.running) {
              card.running = false
              card.failed = d.failed === true
              break
            }
          }
        }
        break
      }
      case 'REFERENCE': {
        const d = payload as { count?: number; references?: SseReference[] }
        state.references = d.references ?? []
        break
      }
      case 'HUMAN_REQUIRED': {
        state.notice = '该请求需要人工介入'
        break
      }
      case 'DONE': {
        const d = payload as SseDoneData
        state.model = d.model ?? state.model
        state.agent = d.agent ?? state.agent
        state.tokensIn = d.tokensIn ?? 0
        state.tokensOut = d.tokensOut ?? 0
        state.latencyMs = d.latencyMs ?? 0
        state.firstTokenMs = d.firstTokenMs ?? 0
        state.toolCallCount = d.toolCalls ?? state.tools.length
        state.ragHits = d.ragHits ?? 0
        state.status = d.status ?? 'COMPLETED'
        state.tools.forEach((t) => (t.running = false))
        settle('done')
        break
      }
      case 'BLOCKED': {
        const d = payload as SseBlockedData
        state.blockReason = d.reason ?? '内容被安全策略拦截'
        state.status = 'BLOCKED'
        state.tools.forEach((t) => (t.running = false))
        settle('blocked')
        break
      }
      case 'INTERRUPTED': {
        const d = payload as SseInterruptedData
        state.errorMessage = d.message ?? '生成已中断'
        state.status = 'INTERRUPTED'
        state.tools.forEach((t) => (t.running = false))
        settle('interrupted')
        break
      }
      case 'ERROR': {
        const d = payload as SseErrorData
        state.errorMessage = d.message ?? '生成失败'
        // 保留业务码与 traceId：护栏拦截（code 6009）与上游超时是两种完全不同的处置方式
        state.errorCode = d.code ?? 0
        state.errorTraceId = d.traceId ?? ''
        state.status = 'FAILED'
        state.tools.forEach((t) => (t.running = false))
        settle('error')
        break
      }
      default:
        break
    }
  }

  /** 消费一个 fetch 响应体 */
  async function consume(response: Response): Promise<void> {
    if (!response.body) throw new Error('响应体为空')
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const { frames, rest } = extractFrames(buffer)
        buffer = rest
        for (const frame of frames) {
          handleFrame(frame)
          if (settled) {
            // 终态后主动中断读取
            await reader.cancel().catch(() => undefined)
            return
          }
        }
      }
      // 处理缓冲区中的残留帧
      if (buffer.trim().length > 0) {
        const frame = parseFrame(buffer)
        if (frame) handleFrame(frame)
      }
    } finally {
      reader.releaseLock?.()
    }
  }

  /** 建立一次连接（首帧用 POST，断线续传用 GET + lastEventId） */
  async function connect(conversationId: LongId, message: string): Promise<void> {
    controller = new AbortController()
    const token = localStorage.getItem(TOKEN_KEY)
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    }
    if (token) headers.Authorization = `Bearer ${token}`

    let url: string
    let init: RequestInit
    if (state.messageId) {
      // 断点续传：带上已收到的最大 seq
      url = `${agentStreamResumePath(state.messageId)}?lastEventId=${lastSeq.value}`
      init = { method: 'GET', headers, signal: controller.signal }
    } else {
      // 首次请求：复用同一个 turnId
      url = AGENT_STREAM_PATH
      init = {
        method: 'POST',
        headers,
        body: JSON.stringify({
          conversationId,
          turnId: state.turnId,
          message,
        }),
        signal: controller.signal,
      }
    }

    const response = await fetch(url, init)
    if (response.status === 401) {
      throw new Error('登录已过期，请重新登录')
    }
    if (!response.ok) {
      throw new Error(`流式接口异常（HTTP ${response.status}）`)
    }
    if (state.phase !== 'streaming') setPhase('streaming')
    await consume(response)
  }

  /** 断线重连调度：指数退避，终态后不再重连 */
  function scheduleReconnect(conversationId: LongId, message: string, gen: number): void {
    if (settled || userStopped || isStale(gen)) return
    if (state.retryCount >= maxRetries) {
      state.errorMessage = '多次重连失败，请稍后重试'
      settle('error')
      return
    }
    const attempt = state.retryCount
    state.retryCount = attempt + 1
    state.connectionLost = true
    setPhase('reconnecting')
    const delay = backoffDelay(attempt)
    retryTimer = setTimeout(() => {
      retryTimer = null
      // 定时器触发时再校验一次：等待期间可能已经 reset（切会话 / 新一轮）
      if (isStale(gen)) return
      void run(conversationId, message, true, gen)
    }, delay)
  }

  /** 执行一轮连接；isRetry 表示这是重连 */
  async function run(
    conversationId: LongId,
    message: string,
    isRetry: boolean,
    gen: number,
  ): Promise<void> {
    if (settled || isStale(gen)) return
    try {
      await connect(conversationId, message)
      // 本轮已被 reset（终态已落库并开启新一轮）→ 绝不能把"正常结束"当成断线
      if (isStale(gen)) return
      // 流正常结束但未收到终态事件 —— 视为断线，继续续传
      if (!settled && !userStopped) {
        scheduleReconnect(conversationId, message, gen)
      }
    } catch (err) {
      if (userStopped || isStale(gen)) return
      // AbortError 由主动停止触发
      if (err instanceof DOMException && err.name === 'AbortError') return
      if (settled) return
      state.errorMessage = err instanceof Error ? err.message : String(err)
      if (isRetry || state.retryCount > 0) {
        state.connectionLost = true
      }
      scheduleReconnect(conversationId, message, gen)
    }
  }

  /**
   * 发送一条消息并开始流式接收。
   * turnId 在此生成一次，后续重连 / 重试复用。
   */
  async function send(conversationId: LongId, message: string): Promise<void> {
    reset()
    // reset() 会自增代号，因此这里取到的就是本轮的代号
    const gen = generation
    state.conversationId = conversationId
    state.turnId = crypto.randomUUID()
    setPhase('connecting')
    streaming = true
    await run(String(conversationId), message, false, gen)
  }

  /** 主动停止生成 */
  async function stop(): Promise<void> {
    userStopped = true
    if (retryTimer) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
    controller?.abort()
    if (state.messageId) {
      try {
        await agentApi.stop(state.messageId)
      } catch {
        // 停止失败不阻塞 UI，后端会随连接关闭自然终止
      }
    }
    state.tools.forEach((t) => (t.running = false))
    state.status = 'INTERRUPTED'
    settle('interrupted')
  }

  /** 重置状态，准备下一轮 */
  function reset(): void {
    if (retryTimer) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
    if (recoveredTimer) {
      clearTimeout(recoveredTimer)
      recoveredTimer = null
    }
    controller?.abort()
    controller = null
    Object.assign(state, createState())
    lastSeq.value = 0
    userStopped = false
    settled = false
    streaming = false
    // 让任何仍在飞行中的读取循环 / 重连定时器立即失效
    generation += 1
  }

  /** 是否有正在进行的流 */
  function isStreaming(): boolean {
    return streaming && !settled
  }

  /** 标记续传成功，短暂显示「已恢复」 */
  function markRecovered(): void {
    state.connectionLost = false
    state.recovered = true
    state.retryCount = 0
    options.onRecovered?.()
    if (recoveredTimer) clearTimeout(recoveredTimer)
    recoveredTimer = setTimeout(() => {
      state.recovered = false
    }, 3000)
  }

  return {
    state,
    lastSeq,
    send,
    stop,
    reset,
    isStreaming,
    markRecovered,
    /** 供测试使用 */
    _handleFrame: handleFrame,
  }
}
