/**
 * SSE（Server-Sent Events）纯解析工具。
 *
 * 与框架无关，便于单独验证：
 * - 帧格式：`id:<seq>\nevent:<TYPE>\ndata:<json>\n\n`
 * - 服务端每 15 秒发送 `:ping` 心跳注释帧，必须忽略
 * - 断点续传依赖 `id:`（seq）做去重
 */

/** 解析后的单个 SSE 帧 */
export interface SseFrame {
  /** `id:` 字段，即 seq；缺失时为 null */
  id: number | null
  /** `event:` 字段，默认 message */
  event: string
  /** `data:` 字段（多行会以 \n 拼接） */
  data: string
}

/**
 * 解析单个 SSE 数据块。整块只有注释（如 `:ping`）时返回 null。
 */
export function parseFrame(block: string): SseFrame | null {
  let id: number | null = null
  let event = 'message'
  const dataLines: string[] = []
  let sawField = false

  for (const line of block.split('\n')) {
    if (line.length === 0) continue
    // 心跳 / 注释帧，直接忽略
    if (line.startsWith(':')) continue
    const idx = line.indexOf(':')
    const field = idx === -1 ? line : line.slice(0, idx)
    let value = idx === -1 ? '' : line.slice(idx + 1)
    // 规范允许冒号后跟一个空格，需去掉
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'id') {
      const n = Number(value)
      if (Number.isFinite(n)) id = n
      sawField = true
    } else if (field === 'event') {
      event = value
      sawField = true
    } else if (field === 'data') {
      dataLines.push(value)
      sawField = true
    }
  }
  if (!sawField) return null
  return { id, event, data: dataLines.join('\n') }
}

/**
 * 从待处理缓冲区中切出所有「完整」帧（以空行分隔），
 * 返回帧数组与尚未完整的剩余缓冲。
 * 网络分片可能把一帧切成两半，因此必须保留 rest 到下次拼接。
 */
export function extractFrames(buffer: string): { frames: SseFrame[]; rest: string } {
  const frames: SseFrame[] = []
  // 统一换行，兼容 \r\n
  const normalized = buffer.replace(/\r\n/g, '\n')
  const parts = normalized.split('\n\n')
  // 最后一段可能不完整，留到下次
  const rest = parts.pop() ?? ''
  for (const block of parts) {
    const frame = parseFrame(block)
    if (frame) frames.push(frame)
  }
  return { frames, rest }
}

/**
 * 断线重连的指数退避：1s → 2s → 4s → 8s，上限 10s。
 * @param attempt 第几次重试，从 0 开始
 */
export function backoffDelay(attempt: number): number {
  return Math.min(1000 * Math.pow(2, attempt), 10000)
}
