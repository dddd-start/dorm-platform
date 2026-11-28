// ══════════════════════════════════════════════════════════════════
// dorm-service · 申请、订单、统计、审计（其余 /internal/agent/**）
// ══════════════════════════════════════════════════════════════════
const ENTRIES_4 = [

{
  id: 'int-apply-create', group: 'apply', method: 'POST', path: '/internal/agent/applies',
  title: '创建入住申请（AI 来源）',
  desc: 'Agent 代用户提交申请。这是整条链路里唯一带「事务重试 + 幂等键 + 号段生成」的写接口。',
  alt: '创建申请：请求线程内循环重试，每次新事务',
  lanes: ['DORM', 'REQ2', 'REDIS', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'Agent 侧工具调用：DormServiceClient.post\nApplySource.AGENT（与 WEB 来源区分，用于统计）' },
    { n: 2, lane: 1, text: 'InternalAuthInterceptor 全套校验\n（内部令牌 + 用户 JWT + Redis MGET 会话）', },
    { n: 3, lane: 1, text: 'ApplyService.create(request, AGENT)\n<b>无 @Transactional</b>：纯重试外壳，最多 4 次', },
    { n: 4, lane: 1, text: 'selfProvider.getObject().createInNewTransaction(...)\n通过 ObjectProvider 拿自身代理，绕过自调用失效', },
    { n: 5, lane: 1, text: 'createInNewTransaction：@Transactional(REQUIRES_NEW)\nUserContext / 日期窗口 / 区域启用 / 公寓归属校验', to: 2, hopTo: 6, hop: 'SELECT' },
    { n: 6, lane: 1, text: '幂等：StayApplyMapper.selectOne(by idempotencyKey LIMIT 1)\n命中直接返回 detail()，不再走后面', to: 3, hopTo: 7, hop: 'SELECT' },
    { n: 7, lane: 1, text: 'StayApplyMapper.selectOverlapping\n同人时间冲突检查', to: 3, hopTo: 8, hop: 'SELECT' },
    { n: 8, lane: 1, text: 'BizNoGenerator.next("DA")\nSETNX 播种 + INCR + EXPIRE；撞号时查库最大值兜底（最多 30 次）', to: 2, hopTo: 9, hop: 'SETNX+INCR' },
    { n: 9, lane: 1, text: 'StayApplyMapper.insert\nuk_apply_idempotency 唯一索引兜底', to: 3, hopTo: 10, hop: 'INSERT' },
    { n: 10, lane: 1, text: 'OutboxService.publish(APPLY_CREATED)\n@Transactional(MANDATORY) → OutboxEventMapper.insert', to: 3, hopTo: 11, hop: 'INSERT' },
    { n: 11, lane: 1, text: 'AuditService.success(...) → auditExecutor.execute\n异步交给 PT-AUDIT', to: 3, hopTo: 12, hop: '返回 ApplyVO' },
    { n: 12, lane: 1, text: 'detail() → 返回 ApplyVO', },
    { n: 13, lane: 1, text: '捕获 DuplicateKeyException → Thread.sleep(10ms × attempt)\n换<b>新事务</b>重试：REPEATABLE-READ 下旧事务看不到胜者', },
  ],
  switches: [
    { n: 4, lane: 'VT-REQ → VT-REQ（新事务）', how: 'ObjectProvider<ApplyService>.getObject() 取代理后调用', why: '@Transactional 自调用不生效，必须经代理才会开新事务' },
    { n: 11, lane: 'VT-REQ → PT-AUDIT', how: 'auditExecutor.execute()（ThreadPoolTaskExecutor，队列满丢弃并计数）', why: '审计是观测数据，不能让写库失败拖住申请提交' },
  ],
  refs: [
    ['InternalAgentController.createApply', 'dorm-service/.../boot/controller/InternalAgentController.java:190'],
    ['ApplyService.create（重试外壳）', 'dorm-service/.../domain/service/ApplyService.java:113-147'],
    ['createInNewTransaction', 'dorm-service/.../domain/service/ApplyService.java:154'],
    ['BizNoGenerator.next', 'dorm-service/.../domain/support/BizNoGenerator.java'],
    ['OutboxService.publish', 'dorm-service/.../domain/service/OutboxService.java'],
  ],
  notes: [
    '为什么要换新事务：REPEATABLE-READ 下旧事务的快照看不到并发胜者已插入的行，用同一个事务重试永远撞键。',
    '整个重试循环在<b>同一条虚拟线程</b>上；Thread.sleep 退避时虚拟线程让出载体，不占平台线程。',
    'Agent 提交的申请带 ApplySource.AGENT，与用户在页面提交的 WEB 区分开，便于统计 AI 代办的占比。',
    'Outbox 事件只是入库，真正的投递由 PT-OUTBOX 每 2s 扫描（见 int-audit 图旁注）。',
  ],
},

{
  id: 'int-apply-page', group: 'apply', method: 'GET', path: '/internal/agent/applies',
  title: '申请列表（Agent 查询）',
  desc: '按角色拼查询条件后分页，再逐条拼 VO——这是典型 N+1，Agent 一次调用可能触发几十次查询。',
  alt: '申请列表：请求线程分页后逐条补全关联数据',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验\ncurrent / size（上限 200）/ status / regionId / keyword / 日期窗' },
    { n: 2, lane: 0, text: 'ApplyService.page：按角色拼 LambdaQueryWrapper\n员工强制 employee_id = me', to: 1, hopTo: 3, hop: 'SELECT + COUNT' },
    { n: 3, lane: 0, text: 'StayApplyMapper.selectPage', },
    { n: 4, lane: 0, text: 'buildVO(apply, false) <b>逐条</b>：\nRegionMapper + DormBuildingMapper + DormRoomMapper + DormBedMapper + StayOrderMapper', to: 1, hopTo: 5, hop: 'SELECT × 5N' },
    { n: 5, lane: 0, text: 'PageMapper.of → PageResult<ApplyVO>', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.applies', 'dorm-service/.../boot/controller/InternalAgentController.java:166'],
    ['ApplyService.page / buildVO', 'dorm-service/.../domain/service/ApplyService.java:737, :777'],
  ],
  notes: [
    '一条申请最多触发 5 次关联查询；size=20 的一页理论上是 100 次往返，全部串行在同一条虚拟线程 + 同一条 Hikari 连接上。',
    '虚拟线程不会让这些往返变快——它只保证不占用平台线程。真正的上限是 Hikari 的 40 个连接。',
  ],
},

{
  id: 'int-apply-upcoming', group: 'apply', method: 'GET', path: '/internal/agent/applies/upcoming',
  title: '我的近期入住',
  desc: '查三种状态（待审/已通过/已入住）后逐条补全。AI 回答「我下周住哪」用这个。',
  alt: '近期入住：请求线程查询后逐条补全',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'ApplyService.myUpcoming()\nstatus IN (PENDING, APPROVED, CHECKED_IN)', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: 'StayApplyMapper.selectList', },
    { n: 4, lane: 0, text: '逐条 buildVO(a, false)：5 次关联查询（N+1）', to: 1, hopTo: 5, hop: 'SELECT × 5N' },
    { n: 5, lane: 0, text: '返回 List<ApplyVO>', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.upcomingApplies', 'dorm-service/.../boot/controller/InternalAgentController.java:180'],
    ['ApplyService.myUpcoming', 'dorm-service/.../domain/service/ApplyService.java:880'],
  ],
  notes: ['通常只有几条记录，N+1 影响不大；但这是 Agent 最常调用的接口之一。'],
},

{
  id: 'int-apply-detail', group: 'apply', method: 'GET', path: '/internal/agent/applies/{id}',
  title: '申请详情（含时间线）',
  desc: '在列表版基础上多查一条时间线，用于向用户解释「这一步走到哪了」。',
  alt: '申请详情：请求线程查询后补全并构建时间线',
  lanes: ['DORM', 'REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'Agent 工具调用' },
    { n: 2, lane: 1, text: 'InternalAuthInterceptor 全套校验' },
    { n: 3, lane: 1, text: 'ApplyService.detail(id)\nrequireApply + 权限校验（员工本人或区域管理员）', to: 2, hopTo: 4, hop: 'SELECT' },
    { n: 4, lane: 1, text: 'buildVO(apply, true)：关联查询 + buildTimeline', to: 2, hopTo: 5, hop: 'SELECT × 5' },
    { n: 5, lane: 1, text: '返回 ApplyVO（含 timeLine 数组）', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.applyDetail', 'dorm-service/.../boot/controller/InternalAgentController.java:185'],
    ['ApplyService.detail / buildTimeline', 'dorm-service/.../domain/service/ApplyService.java:766'],
  ],
  notes: [],
},

{
  id: 'int-apply-cancel', group: 'apply', method: 'POST', path: '/internal/agent/applies/{id}/cancel',
  title: '取消申请',
  desc: '状态机校验后释放床位、取消订单、发 Outbox。全程一个事务，单条虚拟线程。',
  alt: '取消申请：请求线程事务内释放床位并发布事件',
  lanes: ['REQ2', 'MYSQL', 'PAUDIT'],
  steps: [
    { n: 1, lane: 0, text: 'Controller 内组装 ApplyCancelRequest\nreason 缺省为「用户通过 AI 助手取消」' },
    { n: 2, lane: 0, text: 'ApplyService.cancel：@Transactional\nrequireApply + 员工本人 / 管理员区域校验', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: 'ApplyStateMachine.assertTransition（纯内存）', },
    { n: 4, lane: 0, text: 'releaseBedIfAny：DormBedMapper.releaseBed\nStayApplyMapper.updateById', to: 1, hopTo: 5, hop: 'UPDATE × 2' },
    { n: 5, lane: 0, text: 'cancelOrderIfAny：StayOrderMapper.selectOne + updateById', to: 1, hopTo: 6, hop: 'SELECT + UPDATE' },
    { n: 6, lane: 0, text: 'OutboxService.publish(APPLY_CANCELLED)', to: 1, hopTo: 7, hop: 'INSERT' },
    { n: 7, lane: 0, text: 'AuditService.success → auditExecutor.execute\n返回 detail()', to: 2, hopTo: 8, hop: '异步' },
    { n: 8, lane: 2, text: 'dorm-audit-* 线程上写 audit_log', },
  ],
  switches: [
    { n: 7, lane: 'VT-REQ → PT-AUDIT', how: 'auditExecutor.execute()', why: '审计异步，业务事务不等它' },
  ],
  refs: [
    ['InternalAgentController.cancelApply', 'dorm-service/.../boot/controller/InternalAgentController.java:195'],
    ['ApplyService.cancel', 'dorm-service/.../domain/service/ApplyService.java:396'],
  ],
  notes: ['取消是唯一会同时改三张表（申请、床位、订单）的轻量操作；全部在一个事务里，失败整体回滚。'],
},

{
  id: 'int-check-in', group: 'apply', method: 'POST', path: '/internal/agent/applies/check-in',
  title: '办理入住',
  desc: '状态机 + 时间窗口校验；超期未入住会先失效释放床位再抛异常。',
  alt: '办理入住：请求线程校验后更新申请与订单',
  lanes: ['REQ2', 'MYSQL', 'PAUDIT'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'ApplyService.checkIn：@Transactional\n权限校验 + ApplyStateMachine.assertTransition', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: '时间窗口校验：超期 → expireInternal\n（releaseBed + updateById + cancelOrderIfAny + publish + audit）再抛异常', to: 1, hopTo: 4, hop: 'UPDATE × N' },
    { n: 4, lane: 0, text: 'StayApplyMapper.updateById(status=CHECKED_IN)', to: 1, hopTo: 5, hop: 'UPDATE' },
    { n: 5, lane: 0, text: 'StayOrderMapper.selectOne + updateById(已入住)', to: 1, hopTo: 6, hop: 'SELECT+UPDATE' },
    { n: 6, lane: 0, text: 'OutboxService.publish(ORDER_CHECKED_IN) → detail()', to: 2, hopTo: 7, hop: '异步' },
    { n: 7, lane: 2, text: '审计异步写 audit_log', },
  ],
  switches: [
    { n: 6, lane: 'VT-REQ → PT-AUDIT', how: 'auditExecutor.execute()', why: '同所有写接口' },
  ],
  refs: [
    ['InternalAgentController.checkIn', 'dorm-service/.../boot/controller/InternalAgentController.java:203'],
    ['ApplyService.checkIn / expireInternal', 'dorm-service/.../domain/service/ApplyService.java:436'],
  ],
  notes: [
    '超期分支在一个事务里做「先释放再抛异常」：抛异常会回滚整个事务，所以 expireInternal 的写入必须与异常在同一事务边界内被正确处理，这是这段代码最容易看错的地方。',
    'Agent 代办入住时，身份来自工具调用携带的用户 JWT，而不是 Agent 自己的身份。',
  ],
},

{
  id: 'int-check-out', group: 'apply', method: 'POST', path: '/internal/agent/applies/check-out',
  title: '办理退房',
  desc: '可传 orderId 或 applyId。释放床位、回写实际入住晚数、同步订单状态。',
  alt: '办理退房：请求线程释放床位并重算住宿晚数',
  lanes: ['REQ2', 'MYSQL', 'PAUDIT'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'ApplyService.checkOut：@Transactional\nresolveApplyForCheckOut（orderId 优先，否则 applyId）', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: '权限 + 状态机 + 提前退房原因校验', },
    { n: 4, lane: 0, text: 'releaseBedIfAny：DormBedMapper.releaseBed', to: 1, hopTo: 5, hop: 'UPDATE' },
    { n: 5, lane: 0, text: 'StayApplyMapper.updateById(status=CHECKED_OUT)', to: 1, hopTo: 6, hop: 'UPDATE' },
    { n: 6, lane: 0, text: 'StayOrderMapper：重算 actualNights → updateById\nDormRoomMapper.selectById + updateById（占用数回退）', to: 1, hopTo: 7, hop: 'SELECT+UPDATE' },
    { n: 7, lane: 0, text: 'OutboxService.publish(ORDER_CHECKED_OUT) → detail()', to: 2, hopTo: 8, hop: '异步' },
    { n: 8, lane: 2, text: '审计异步写 audit_log', },
  ],
  switches: [
    { n: 7, lane: 'VT-REQ → PT-AUDIT', how: 'auditExecutor.execute()', why: '同上' },
  ],
  refs: [
    ['InternalAgentController.checkOut', 'dorm-service/.../boot/controller/InternalAgentController.java:208'],
    ['ApplyService.checkOut / resolveApplyForCheckOut', 'dorm-service/.../domain/service/ApplyService.java:502'],
  ],
  notes: ['actualNights 按实际退房日重算，提前退房的差额逻辑就在这一段；Agent 回答「退了多少钱」时依赖它。'],
},

{
  id: 'int-orders-mine', group: 'apply', method: 'GET', path: '/internal/agent/orders/mine',
  title: '我的订单',
  desc: '查本人全部订单后逐条补全区域、公寓、房间、床位名称。',
  alt: '我的订单：请求线程查询后逐条补全',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'OrderService.myOrders()\nStayOrderMapper.selectList(employee_id = me)', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: 'stream().map(OrderVO::of).map(this::enrich)\nenrich 每条 4 次查询（区域/公寓/房间/床位）', to: 1, hopTo: 4, hop: 'SELECT × 4N' },
    { n: 4, lane: 0, text: '返回 List<OrderVO>', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.myOrders', 'dorm-service/.../boot/controller/InternalAgentController.java:219'],
    ['OrderService.myOrders / enrich', 'dorm-service/.../domain/service/OrderService.java:86, :94'],
  ],
  notes: [],
},

{
  id: 'int-order-detail', group: 'apply', method: 'GET', path: '/internal/agent/orders/{id}',
  title: '订单详情',
  desc: '一次查订单加一次 enrich，与列表版共用补全逻辑。',
  alt: '订单详情：请求线程查询后补全',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'OrderService.detail(id)\nrequireOrder + 权限校验', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: 'enrich：4 次关联查询', to: 1, hopTo: 4, hop: 'SELECT × 4' },
    { n: 4, lane: 0, text: '返回 OrderVO', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.orderDetail', 'dorm-service/.../boot/controller/InternalAgentController.java:224'],
    ['OrderService.detail', 'dorm-service/.../domain/service/OrderService.java:74'],
  ],
  notes: [],
},

{
  id: 'int-orders', group: 'apply', method: 'GET', path: '/internal/agent/orders',
  title: '订单列表（分页）',
  desc: '分页后逐条 enrich。单条虚拟线程，N+1 的代价与申请列表相同。',
  alt: '订单列表：请求线程分页后逐条补全',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验\ncurrent / size / status / regionId / keyword / 日期窗' },
    { n: 2, lane: 0, text: 'OrderService.page：按角色拼 wrapper', to: 1, hopTo: 3, hop: 'SELECT + COUNT' },
    { n: 3, lane: 0, text: 'StayOrderMapper.selectPage', },
    { n: 4, lane: 0, text: 'enrich 逐条：区域 / 公寓 / 房间 / 床位 各一次', to: 1, hopTo: 5, hop: 'SELECT × 4N' },
    { n: 5, lane: 0, text: 'PageMapper.of → PageResult<OrderVO>', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.orders', 'dorm-service/.../boot/controller/InternalAgentController.java:229'],
    ['OrderService.page', 'dorm-service/.../domain/service/OrderService.java:47'],
  ],
  notes: [],
},

{
  id: 'int-orders-overdue', group: 'apply', method: 'GET', path: '/internal/agent/orders/overdue',
  title: '超期未退房订单',
  desc: '单条 SQL 按退房日期过滤，不包装 VO，直接返回实体。',
  alt: '超期订单：请求线程一次查询',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验\nlimit 默认 10' },
    { n: 2, lane: 0, text: 'OrderService.overdueOrders(limit)\nwhere status=CHECKED_IN and check_out_date < CURDATE()', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: '返回 List<StayOrder> 实体（不做 enrich）', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.overdueOrders', 'dorm-service/.../boot/controller/InternalAgentController.java:243'],
    ['OrderService.overdueOrders', 'dorm-service/.../domain/service/OrderService.java:129'],
  ],
  notes: ['唯一一个不补全关联数据的订单接口——因为实体里已含 roomId / bedId，Agent 需要详情时会再调一次订单详情。'],
},

{
  id: 'int-stats-overview', group: 'stats', method: 'GET', path: '/internal/agent/stats/overview',
  title: '运营总览',
  desc: '四次独立聚合查询串行执行：房态、申请状态、订单状态、按天趋势。',
  alt: '运营总览：请求线程串行四次聚合',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验\ntrendDays 默认 7' },
    { n: 2, lane: 0, text: 'DataScope.visibleRegionId + statisticsScope', },
    { n: 3, lane: 0, text: '① DormRoomMapper.statisticsByRegion', to: 1, hopTo: 4, hop: 'GROUP BY' },
    { n: 4, lane: 0, text: '② StayApplyMapper.countByStatus', to: 1, hopTo: 5, hop: 'GROUP BY' },
    { n: 5, lane: 0, text: '③ StayOrderMapper.countByStatus', to: 1, hopTo: 6, hop: 'GROUP BY' },
    { n: 6, lane: 0, text: '④ StayApplyMapper.countDailyTrend', to: 1, hopTo: 7, hop: 'GROUP BY' },
    { n: 7, lane: 0, text: '内存累加、算百分比 → StatsVO', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.overview', 'dorm-service/.../boot/controller/InternalAgentController.java:248'],
    ['StatsService.overview', 'dorm-service/.../domain/service/StatsService.java:42'],
  ],
  notes: [
    '四次查询串行，没有并发——MyBatis 的 SqlSession 不是线程安全的，要并发就得多借连接。',
    '这是 Agent 回答「现在空置率多少」的数据源，结果没有缓存。',
  ],
},

{
  id: 'int-stats-occupancy', group: 'stats', method: 'GET', path: '/internal/agent/stats/occupancy',
  title: '入住率',
  desc: '一条 SQL 内两个标量子查询，比分四次快得多。',
  alt: '入住率：请求线程一次单 SQL 查询',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'StatsService.occupancy(regionId)', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: 'StayOrderMapper.occupancy：一条 SQL 内两个标量子查询\n（已住床位数 / 总床位数）', },
    { n: 4, lane: 0, text: '算百分比 → OccupancyVO', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.occupancy', 'dorm-service/.../boot/controller/InternalAgentController.java:254'],
    ['StatsService.occupancy', 'dorm-service/.../domain/service/StatsService.java:113'],
  ],
  notes: ['与上一个接口的对比很有教学价值：同样的聚合，合并成一条 SQL 就少三次往返。'],
},

{
  id: 'int-audit', group: 'stats', method: 'POST', path: '/internal/agent/audit',
  title: 'Agent 业务审计回传',
  desc: 'Agent 把轮次与工具调用的结果回传给 dorm 统一落库。调用方是 VT-AUDIT，不是 VT-REQ。',
  alt: '审计回传：agent 的虚拟线程跨服务调用后异步写库',
  lanes: ['AUDIT', 'REQ2', 'REDIS', 'MYSQL', 'PAUDIT'],
  steps: [
    { n: 1, lane: 0, text: 'Agent 侧：DormServiceClient.audit(...)\n在 VT-AUDIT（虚拟线程）上提交' },
    { n: 2, lane: 0, text: 'POST /internal/agent/audit，带 X-Internal-Token + X-Trace-Id', to: 1, hopTo: 3, hop: 'RestClient（阻塞）' },
    { n: 3, lane: 1, text: 'InternalAuthInterceptor：内部令牌 + 用户 JWT + Redis 会话', to: 2, hopTo: 4, hop: 'MGET' },
    { n: 4, lane: 1, text: 'Controller 内解析 module（非法值回落 AGENT）\nsuccess ? auditService.success : auditService.fail', },
    { n: 5, lane: 1, text: 'AuditService.record → auditExecutor.execute(persist)', to: 4, hopTo: 6, hop: '异步' },
    { n: 6, lane: 4, text: 'dorm-audit-* 上 auditLogMapper.insert', to: 3, hopTo: 7, hop: 'INSERT' },
    { n: 7, lane: 1, text: '立刻返回 Result<Void>，不等落库', to: 0, hopTo: 8, hop: '200 OK' },
    { n: 8, lane: 0, text: 'VT-AUDIT 结束（DormServiceClient.audit 不关心结果）', },
  ],
  switches: [
    { n: 2, lane: 'VT-AUDIT → dorm VT-REQ', how: 'RestClient（JDK HttpURLConnection）同步 POST', why: '跨服务，agent 侧用的是虚拟线程，dorm 侧另起一条虚拟线程' },
    { n: 5, lane: 'dorm VT-REQ → PT-AUDIT', how: 'auditExecutor.execute()', why: '审计写入异步化，接口立刻返回' },
  ],
  refs: [
    ['InternalAgentController.audit', 'dorm-service/.../boot/controller/InternalAgentController.java:267'],
    ['DormServiceClient.audit', 'agent-service/.../agent/client/DormServiceClient.java:341-372'],
    ['AuditService.record / persist', 'dorm-service/.../domain/service/AuditService.java:101, :115'],
  ],
  notes: [
    '这是全项目唯一「虚拟线程 → 虚拟线程」的跨服务调用：agent 的 VT-AUDIT 打到 dorm 的 VT-REQ，各自独立。',
    'dorm 侧写审计是在 PT-AUDIT 上完成的，所以整条审计链路经过三种线程：VT-AUDIT → VT-REQ(dorm) → dorm-audit-*。',
    '审计线程池用「丢弃并计数」而不是 CallerRuns：实测 CallerRuns 在 1000 并发下造成连接耗尽（total=128/active=128/waiting=192）。',
  ],
},

];
