/**
 * JSON 安全解析工具。
 *
 * 背景：后端主键使用雪花算法，长度 19 位，超过 JS `Number.MAX_SAFE_INTEGER`
 * （9007199254740991）。直接 `JSON.parse` 会丢精度，例如
 * `2110070374499643393` 会被解析成 `2110070374499643400`，
 * 回传给后端就会变成错误的 id（审批、退房等操作会失败）。
 *
 * 解决方式：解析前用正则把「16 位及以上的整数字面量」改写成字符串，
 * 使其以字符串形式进入 JS（TS 侧对应 `LongId = string`）。
 * 正则只匹配紧跟在 `:`、`,`、`[`、`]` 之后的数字，因此不会误伤字符串内容，
 * 也不会影响 16 位以下的小 id（区域 / 公寓 / 房间 / 床位 / 用户 id）。
 */

/** 匹配 JSON 中作为值出现的超长整数 */
const BIG_INT_RE = /([:,[\]]\s*)(-?\d{16,})(?=\s*[,}\]])/g

/** 是否包含可能超长的整数（用于快速跳过） */
const HAS_BIG_INT_RE = /\d{16,}/

export function parseJsonSafe(text: string): unknown {
  if (typeof text !== 'string' || text.length === 0) return text
  if (!HAS_BIG_INT_RE.test(text)) {
    try {
      return JSON.parse(text)
    } catch {
      return text
    }
  }
  try {
    return JSON.parse(text.replace(BIG_INT_RE, '$1"$2"'))
  } catch {
    // 兜底：还原失败时退回普通解析，至少不抛错
    try {
      return JSON.parse(text)
    } catch {
      return text
    }
  }
}
