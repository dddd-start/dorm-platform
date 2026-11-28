// ── 数据：每个接口一张图 ────────────────────────────────────────────
// lanes: 每张图的列 = 一条线程。k 决定配色（vt / pt / ext / caller）。
// steps: { n: 步骤号, lane: 列索引, text: 盒内文字, to: 跨列目标索引, hop: 跨列时横段上的说明 }
//   · 无 to  → 自消息，盒内是「组件.方法 → 下游」的直调
//   · 有 to  → 从本列盒底肘形连到目标列，hop 写在横段
// notes: 读图要点。switches: 线程切换点表。
const GROUPS = [
  { id: 'overview', title: '全局线程模型' },
  { id: 'sse',      title: 'agent · SSE 主链路' },
  { id: 'conv',     title: 'agent · 会话与消息' },
  { id: 'admin',    title: 'agent · 管理后台' },
  { id: 'region',   title: 'dorm · 区域与公寓' },
  { id: 'room',     title: 'dorm · 房间与床位' },
  { id: 'apply',    title: 'dorm · 申请与订单' },
  { id: 'stats',    title: 'dorm · 统计与审计' },
];

// 线程字典：所有图共用，保证命名与配色在 49 张图里一致
const LANES = {
  CALLER:   { name: '浏览器',            k: 'caller', sub: '前端 useAgentStream' },
  REQ:      { name: 'VT-REQ',            k: 'vt',     sub: 'Tomcat 请求线程 · 虚拟线程' },
  REQ2:     { name: 'VT-REQ (dorm)',     k: 'vt',     sub: 'dorm-service 请求线程' },
  GEN:      { name: 'VT-GEN',            k: 'vt',     sub: 'agentStreamExecutor · 虚拟线程' },
  MODEL:    { name: 'VT-MODEL',          k: 'vt',     sub: 'agent-model-stream-N · 虚拟线程' },
  SSE:      { name: 'VT-SSE',            k: 'vt',     sub: 'sse-writer-{id} · 虚拟线程' },
  AUDIT:    { name: 'VT-AUDIT',          k: 'vt',     sub: 'DormServiceClient · 虚拟线程' },
  RAGLOG:   { name: 'VT-RAGLOG',         k: 'vt',     sub: 'retrievalLogExecutor · 虚拟线程' },
  SCHED:    { name: 'VT-SCHED',          k: 'vt',     sub: 'scheduling-1 · 虚拟线程' },
  PSSE:     { name: 'PT-SSE-1..4',       k: 'pt',     sub: 'dorm-sse-* · 平台线程' },
  PPUB:     { name: 'PT-PUBSUB',         k: 'pt',     sub: 'redis-pubsub-* · 平台线程' },
  PTASK:    { name: 'PT-TASK',           k: 'pt',     sub: 'dorm-agent-task-* · 平台线程' },
  PAUDIT:   { name: 'PT-AUDIT',          k: 'pt',     sub: 'dorm-audit-* · 平台线程' },
  POUTBOX:  { name: 'PT-OUTBOX',         k: 'pt',     sub: 'dorm-outbox-* · 平台线程' },
  MYSQL:    { name: 'MySQL',             k: 'ext',    sub: 'Hikari 上限 40' },
  REDIS:    { name: 'Redis',             k: 'ext',    sub: 'Lettuce 同步命令' },
  LLM:      { name: '上游模型',           k: 'ext',    sub: 'HTTPS · 流式 SSE' },
  DORM:     { name: 'dorm-service',      k: 'ext',    sub: 'HTTP /internal/**' },
};
