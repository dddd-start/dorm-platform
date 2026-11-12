/**
 * 纯逻辑自测：大整数还原 + SSE 帧解析/去重/退避。
 * 运行：npx tsx 不必要，使用 esbuild 打包后由 node 执行（见 scripts/selftest.sh）。
 */
import { parseJsonSafe } from '../src/utils/json'
import { backoffDelay, extractFrames, parseFrame, type SseFrame } from '../src/utils/sse'

let passed = 0
let failed = 0

function check(name: string, cond: boolean, extra = ''): void {
  if (cond) {
    passed++
    console.log(`  ✓ ${name}`)
  } else {
    failed++
    console.log(`  ✗ ${name} ${extra}`)
  }
}

console.log('\n[1] 大整数还原（雪花 id 精度）')
{
  const raw =
    '{"code":0,"data":{"id":2110070374499643393,"applyId":2110070372222136322,"roomId":3001,"nested":{"orderId":-9223372036854775808}}}'
  const parsed = parseJsonSafe(raw) as {
    data: { id: unknown; applyId: unknown; roomId: unknown; nested: { orderId: unknown } }
  }
  check('apply id 还原为精确字符串', parsed.data.id === '2110070374499643393', String(parsed.data.id))
  check('order id 还原为精确字符串', parsed.data.applyId === '2110070372222136322', String(parsed.data.applyId))
  check('小整数保持 number 类型', parsed.data.roomId === 3001 && typeof parsed.data.roomId === 'number')
  check('负数大整数也能还原', parsed.data.nested.orderId === '-9223372036854775808', String(parsed.data.nested.orderId))

  // 对照组：直接 JSON.parse 会丢精度
  const lossy = JSON.parse(raw) as { data: { id: number } }
  check('对照：原生 JSON.parse 确实丢精度', String(lossy.data.id) !== '2110070374499643393', String(lossy.data.id))

  // 字符串里包含长数字不应被改写
  const inString = parseJsonSafe('{"a":"2110070374499643393","b":123}')
  check(
    '字符串内的长数字不被误改',
    (inString as { a: string }).a === '2110070374499643393' && (inString as { b: number }).b === 123,
  )

  // 数组中的大整数
  const arr = parseJsonSafe('[2110070374499643393,2110070372222136322]') as unknown[]
  check('数组内大整数还原', arr[0] === '2110070374499643393' && arr[1] === '2110070372222136322')
}

console.log('\n[2] SSE 帧解析')
{
  const frame = parseFrame('id:12\nevent:DELTA\ndata:{"text":"你好"}')
  check('id 解析为数字', frame?.id === 12)
  check('event 解析', frame?.event === 'DELTA')
  check('data 解析', frame?.data === '{"text":"你好"}')

  check('心跳注释帧返回 null', parseFrame(':ping') === null)
  check('空块返回 null', parseFrame('') === null)

  const multi = parseFrame('id:3\nevent:X\ndata:line1\ndata:line2')
  check('多行 data 用换行拼接', multi?.data === 'line1\nline2', multi?.data)

  const noId = parseFrame('event:DONE\ndata:{}')
  check('无 id 时 id 为 null', noId?.id === null && noId?.event === 'DONE')
}

console.log('\n[3] 分片缓冲与完整帧切分')
{
  // 模拟一帧被网络切成两半
  const half = 'id:1\nevent:DELTA\ndata:{"te'
  const first = extractFrames(half)
  check('半帧不产出、保留在 rest', first.frames.length === 0 && first.rest === half)

  const second = extractFrames(first.rest + 'xt":"a"}\n\nid:2\nevent:DELTA\ndata:{"text":"b"}\n\n')
  check('补齐后产出两帧', second.frames.length === 2, String(second.frames.length))
  check('第一帧 seq=1', second.frames[0]?.id === 1)
  check('第二帧 seq=2', second.frames[1]?.id === 2)

  // 心跳与数据混合
  const mixed = extractFrames(':ping\n\nid:5\nevent:DELTA\ndata:{"text":"x"}\n\n')
  check('心跳被忽略，只产出数据帧', mixed.frames.length === 1 && mixed.frames[0]?.id === 5)

  // \r\n 换行
  const crlf = extractFrames('id:7\r\nevent:DELTA\r\ndata:{"text":"y"}\r\n\r\n')
  check('兼容 CRLF 换行', crlf.frames.length === 1 && crlf.frames[0]?.id === 7)
}

console.log('\n[4] seq 去重（模拟断线续传重放）')
{
  // 用与 composable 相同的规则：只接受 seq 大于已处理最大值的帧
  const apply = (frames: SseFrame[], lastSeq: number): { accepted: string[]; lastSeq: number } => {
    const accepted: string[] = []
    let seq = lastSeq
    for (const f of frames) {
      if (f.id !== null) {
        if (f.id <= seq) continue // 重复帧丢弃
        seq = f.id
      }
      accepted.push(f.data)
    }
    return { accepted, lastSeq: seq }
  }

  const buf =
    'id:1\nevent:DELTA\ndata:A\n\nid:2\nevent:DELTA\ndata:B\n\n'
  const round1 = apply(extractFrames(buf).frames, 0)
  check('首次接收 A、B', round1.accepted.join('') === 'AB', round1.accepted.join(''))
  check('已处理最大 seq = 2', round1.lastSeq === 2)

  // 断线后服务端从 lastEventId=2 重放 2、3 —— 2 必须被丢弃，3 必须接收
  const replay =
    'id:2\nevent:DELTA\ndata:B\n\nid:3\nevent:DELTA\ndata:C\n\n'
  const round2 = apply(extractFrames(replay).frames, round1.lastSeq)
  check('重放的重复帧 B 被丢弃', !round2.accepted.includes('B'), round2.accepted.join(''))
  check('漏掉的增量 C 被补齐（不丢失）', round2.accepted.join('') === 'C', round2.accepted.join(''))
  check('最终内容 AB + C = ABC 无重复无丢失', round1.accepted.join('') + round2.accepted.join('') === 'ABC')
}

console.log('\n[5] 指数退避')
{
  const seq = [0, 1, 2, 3, 4, 5].map(backoffDelay)
  check('1s → 2s → 4s → 8s', seq[0] === 1000 && seq[1] === 2000 && seq[2] === 4000 && seq[3] === 8000, seq.join(','))
  check('上限 10s', seq[4] === 10000 && seq[5] === 10000, seq.join(','))
}

console.log(`\n结果：通过 ${passed} 项，失败 ${failed} 项\n`)
if (failed > 0) process.exit(1)
