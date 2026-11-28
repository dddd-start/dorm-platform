// ══════════════════════════════════════════════════════════════════
// dorm-service · /internal/**（27 个）
// 共同前提：InternalAuthInterceptor 先做常量时间比较内部令牌；
//   /internal/catalog/** → 伪主体 userId=0 放行，不校验用户令牌
//   其余 /internal/agent/** → 再校验用户 JWT + Redis 会话（MGET）
//   之后整条链在 dorm-service 的 VT-REQ 上同步执行
// ══════════════════════════════════════════════════════════════════
const ENTRIES_3 = [

{
  id: 'int-health', group: 'region', method: 'GET', path: '/internal/health',
  title: '内部健康检查',
  desc: '只有内部令牌校验，返回常量。不碰库、不碰 Redis。',
  alt: '内部健康检查：单条虚拟线程返回常量',
  lanes: ['REQ2'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor：X-Internal-Token 常量时间比较\n（MessageDigest.isEqual，不走 JWT）' },
    { n: 2, lane: 0, text: '返回 Result.ok("ok")' },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.health', 'dorm-service/.../boot/controller/InternalAgentController.java:82'],
    ['InternalAuthInterceptor.preHandle', 'dorm-service/.../boot/security/InternalAuthInterceptor.java:39'],
  ],
  notes: ['与 /api/health 一样是纯内存；差别只在校验的是服务令牌而不是用户 JWT。'],
},

{
  id: 'int-profile', group: 'region', method: 'GET', path: '/internal/agent/profile',
  title: '当前用户资料',
  desc: 'Agent 每轮组 Prompt 时会调用，用于把真实姓名、区域填进系统提示。会打一次 Redis 校验会话。',
  alt: '内部用户资料：拦截器校验会员后查库',
  lanes: ['DORM', 'REQ2', 'REDIS', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'POST/GET 携带 X-Internal-Token + Authorization: Bearer（来自 AgentTokenContext）' },
    { n: 2, lane: 1, text: 'InternalAuthInterceptor.preHandle\n常量时间比较内部令牌', },
    { n: 3, lane: 1, text: 'tokenProvider.parse + authService.verifySession\nMGET 会话键 + epoch 键，任一失配即拒绝', to: 2, hopTo: 4, hop: 'MGET' },
    { n: 4, lane: 1, text: 'UserContext.set(loginUser)，TraceContext 透传 X-Trace-Id' },
    { n: 5, lane: 1, text: 'authService.currentUser(false)\nSysUserMapper.selectById + RegionMapper.selectById', to: 3, hopTo: 6, hop: 'SELECT × 2' },
    { n: 6, lane: 1, text: '返回 UserVO（不加密手机号，false 表示不解密）', },
  ],
  switches: [
    { n: 3, lane: 'VT-REQ(dorm) ← Redis', how: 'Lettuce 同步 MGET', why: '每个内部调用都验一次会话；这是 AI 链路里「每次工具调用 = 一次完整 HTTP + 一次 Redis」的来源' },
  ],
  refs: [
    ['InternalAgentController.profile', 'dorm-service/.../boot/controller/InternalAgentController.java:87'],
    ['AuthService.currentUser', 'dorm-service/.../domain/service/AuthService.java:302'],
    ['InternalAuthInterceptor 会话校验', 'dorm-service/.../boot/security/InternalAuthInterceptor.java:65-80'],
  ],
  notes: [
    'Agent 侧由 SystemPromptBuilder 每轮调用一次（AgentMemoryService.currentUser）。',
    '注意拦截器用 MGET 一次取两个键，而不是两次 GET——这是刻意减少往返。',
  ],
},

{
  id: 'int-region-options', group: 'region', method: 'GET', path: '/internal/agent/regions/options',
  title: '区域下拉选项',
  desc: '启用状态的区域，按可见范围收窄。Agent 用它把用户说的「欧洲区」映射成 regionId。',
  alt: '区域选项：请求线程一次查询',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor：内部令牌 + 用户 JWT + Redis 会话' },
    { n: 2, lane: 0, text: 'RegionService.options()\nDataScope.visibleRegionId 收窄', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: 'RegionMapper.selectList(status=1)，返回 RegionOptionVO 列表', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.regionOptions', 'dorm-service/.../boot/controller/InternalAgentController.java:94'],
    ['RegionService.options', 'dorm-service/.../domain/service/RegionService.java:88'],
    ['RegionResolver 调用点', 'agent-service/.../agent/tool/RegionResolver.java:32'],
  ],
  notes: ['非管理员只看到自己区域的选项——这一点在 Agent 上体现为「问别的区域会查不到房」。'],
},

{
  id: 'int-catalog-regions', group: 'region', method: 'GET', path: '/internal/catalog/regions/options',
  title: '区域目录（仅服务令牌）',
  desc: '唯一不校验用户 JWT 的内部接口，因为调用发生在启动装载知识库时，彼时没有任何用户上下文。',
  alt: '区域目录：只验服务令牌，用伪主体查询',
  lanes: ['DORM', 'REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'KnowledgeBaseLoader.resolveRegionId → dormService.catalogRegionOptions()\n发在 agent-service 启动的 ApplicationReadyEvent 上' },
    { n: 2, lane: 1, text: 'InternalAuthInterceptor：只比较内部令牌\nURI 以 /internal/catalog/ 开头 → 直接放行，不再验 JWT', },
    { n: 3, lane: 1, text: 'UserContext.set(伪主体 userId=0 / system / SUPER_ADMIN)' },
    { n: 4, lane: 1, text: 'RegionService.options() → RegionMapper.selectList', to: 2, hopTo: 5, hop: 'SELECT' },
    { n: 5, lane: 1, text: '返回全部启用区域，供 knowledge/*.md 的 regionCode 解析成 ID', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.catalogRegionOptions', 'dorm-service/.../boot/controller/InternalAgentController.java:100'],
    ['InternalAuthInterceptor catalog 分支', 'dorm-service/.../boot/security/InternalAuthInterceptor.java:57-67'],
    ['KnowledgeBaseLoader.resolveRegionId', 'agent-service/.../agent/rag/KnowledgeBaseLoader.java:267'],
  ],
  notes: [
    '伪主体是 SUPER_ADMIN，因此 DataScope 不会收窄——这正是「目录」语义需要的：知识库要认得所有区域的编码。',
    '这条路径上 dorm-service 完全不碰 Redis，是内部接口里最轻的。',
  ],
},

{
  id: 'int-regions', group: 'region', method: 'GET', path: '/internal/agent/regions',
  title: '区域列表（可带床位统计）',
  desc: '管理员与员工看到不同范围；withStats 打开时额外跑一次分组聚合。',
  alt: '区域列表：请求线程查询并按需聚合床位',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验（内部令牌 + JWT + Redis 会话）' },
    { n: 2, lane: 0, text: 'RegionService.list(status, withStats)\nDataScope.visibleRegionId 收窄', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: 'RegionMapper.selectList', },
    { n: 4, lane: 0, text: 'managerNames()：SysUserMapper.selectList(role=REGION_ADMIN)\n一次全表读，内存里按区域归并', to: 1, hopTo: 5, hop: 'SELECT' },
    { n: 5, lane: 0, text: 'withStats 时：DormRoomMapper.statisticsByRegion(null)\n单次 GROUP BY 聚合全部区域', to: 1, hopTo: 6, hop: 'GROUP BY' },
    { n: 6, lane: 0, text: '组装 RegionVO 列表返回', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.regions', 'dorm-service/.../boot/controller/InternalAgentController.java:105'],
    ['RegionService.list / managerNames / roomStats', 'dorm-service/.../domain/service/RegionService.java:55'],
  ],
  notes: ['三次查询全串行在同一条虚拟线程上；没有并发分支，因为 MyBatis 同步会话不能跨线程共享。'],
},

{
  id: 'int-region-detail', group: 'region', method: 'GET', path: '/internal/agent/regions/{id}',
  title: '单个区域（含启用校验）',
  desc: 'Agent 查某区域是否还在运营时用。停用的区域直接抛业务异常。',
  alt: '区域详情：请求线程查询并校验启用状态',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'RegionService.requireEnabled(id)\nRegionMapper.selectById', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: '为 null 或 status != 1 → BizException（AGENT 会转成工具失败回给模型）', },
    { n: 4, lane: 0, text: '直接返回 Region 实体（不是 VO）', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.region', 'dorm-service/.../boot/controller/InternalAgentController.java:112'],
    ['RegionService.requireEnabled', 'dorm-service/.../domain/service/RegionService.java:233'],
  ],
  notes: ['与 /api/** 不同，这里直返实体：字段暴露面更大，但调用方是可信的内部服务。'],
},

{
  id: 'int-region-beds', group: 'region', method: 'GET', path: '/internal/agent/regions/{id}/beds/summary',
  title: '区域床位汇总',
  desc: '两次 count：可用床位数与总床位数。Agent 回答「欧洲区还有多少空床」时的数据源。',
  alt: '区域床位汇总：请求线程两次计数',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'RegionService.bedSummary(id)\ndataScope.assertRegionAccess(regionId)', },
    { n: 3, lane: 0, text: 'DormBedMapper.selectCount(status = IDLE)', to: 1, hopTo: 4, hop: 'COUNT' },
    { n: 4, lane: 0, text: 'DormBedMapper.selectCount(status != DISABLED)', to: 1, hopTo: 5, hop: 'COUNT' },
    { n: 5, lane: 0, text: '返回 BedSummaryVO（available / total）', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.regionBedSummary', 'dorm-service/.../boot/controller/InternalAgentController.java:117'],
    ['RegionService.bedSummary', 'dorm-service/.../domain/service/RegionService.java:245'],
  ],
  notes: ['两次 count 而不是一次 GROUP BY：因为两个口径不同（IDLE vs 非 DISABLED），拆开更好读。'],
},

{
  id: 'int-building-options', group: 'building', method: 'GET', path: '/internal/agent/buildings/options',
  title: '公寓下拉选项',
  desc: '某个区域内启用中的公寓。Agent 解析「东区公寓」这类说法时用。',
  alt: '公寓选项：请求线程一次查询',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'BuildingService.options(regionId)\nDataScope.applyRegion 收窄', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: 'DormBuildingMapper.selectList(status=1)，返回 BuildingOptionVO', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.buildingOptions', 'dorm-service/.../boot/controller/InternalAgentController.java:122'],
    ['BuildingService.options', 'dorm-service/.../domain/service/BuildingService.java:76'],
  ],
  notes: [],
},

{
  id: 'int-building-map', group: 'building', method: 'GET', path: '/internal/agent/buildings/map',
  title: '按 ID 批量取公寓',
  desc: '一次 selectBatchIds 拿一批，避免 Agent 为每条房间记录单独查公寓。',
  alt: '批量取公寓：请求线程一次 IN 查询',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验\nids 为 @RequestParam List<Long>' },
    { n: 2, lane: 0, text: 'BuildingService.buildingMap(ids)\nDormBuildingMapper.selectBatchIds（IN 查询）', to: 1, hopTo: 3, hop: 'SELECT IN' },
    { n: 3, lane: 0, text: '转 Map<Long, DormBuilding> 后取 values() 返回', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.buildingsMap', 'dorm-service/.../boot/controller/InternalAgentController.java:128'],
    ['BuildingService.buildingMap', 'dorm-service/.../domain/service/BuildingService.java:204'],
  ],
  notes: ['这是消除 N+1 的做法：把「逐条查」换成「一次 IN 查」。'],
},

{
  id: 'int-room', group: 'room', method: 'GET', path: '/internal/agent/rooms/{id}',
  title: '房间详情',
  desc: '直接返回实体，不做区域校验——内部调用方已通过用户会话确认身份。',
  alt: '房间详情：请求线程一次查询',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'RoomService.requireRoom(id)\nDormRoomMapper.selectById，不存在抛 NOT_FOUND', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: '直接返回 DormRoom 实体', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.room', 'dorm-service/.../boot/controller/InternalAgentController.java:135'],
    ['RoomService.requireRoom', 'dorm-service/.../domain/service/RoomService.java:418'],
  ],
  notes: ['对照 /api/rooms/{id}：那条链路要做 assertRegionAccess 并拼 VO；这里刻意省掉，因为调用方是 Agent 且已鉴权。'],
},

{
  id: 'int-room-beds', group: 'room', method: 'GET', path: '/internal/agent/rooms/{id}/beds',
  title: '房间床位明细',
  desc: '一次查询带出房间负债信息与每个床位状态，供 Agent 判断「这间还能不能住人」。',
  alt: '房间床位明细：请求线程查询后校验区域',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'RoomService.bedDetails(roomId)\nrequireRoom → assertRegionAccess', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: 'DormRoomMapper.selectBedDetails\n一条 SQL 带出房间 + 床位明细', to: 1, hopTo: 4, hop: 'JOIN' },
    { n: 4, lane: 0, text: '返回 List<BedDetail>', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.roomBeds', 'dorm-service/.../boot/controller/InternalAgentController.java:140'],
    ['RoomService.bedDetails', 'dorm-service/.../domain/service/RoomService.java:180'],
  ],
  notes: ['注意这里<b>有</b> assertRegionAccess，和上一个接口不同：床位明细含住户状态，收窄范围更稳妥。'],
},

{
  id: 'int-availability', group: 'room', method: 'GET', path: '/internal/agent/rooms/availability',
  title: '房源可用性查询（Agent 主入口）',
  desc: 'AI 回答「下周欧洲区还有什么房」的数据源。一条带相关子查询的 SQL，算出每间房在时间窗内的可用床位数。',
  alt: '房源可用性：请求线程一次复杂查询',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'DateUtil.nightsBetween(checkIn, checkOut)\n日期窗口校验（纯内存）', },
    { n: 3, lane: 0, text: 'DataScope.visibleRegionId 收窄\n非员工强制限定在自己区域', },
    { n: 4, lane: 0, text: 'DormRoomMapper.selectAvailability\n相关子查询 + IDLE 床位计数，city / roomType / gender / minBeds 全下推', to: 1, hopTo: 5, hop: 'SELECT 子查询' },
    { n: 5, lane: 0, text: '逐行补 nights 与 roomTypeLabel', },
    { n: 6, lane: 0, text: '返回 List<RoomAvailabilityRow>', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.roomAvailability', 'dorm-service/.../boot/controller/InternalAgentController.java:145'],
    ['RoomService.searchAvailability', 'dorm-service/.../domain/service/RoomService.java:149'],
    ['selectAvailability SQL', 'dorm-service/src/main/resources/mapper/DormRoomMapper.xml'],
  ],
  notes: [
    '这是 AI 链路里最重的一条 SQL：相关子查询在时间窗上算可用床位。Agent 的单次工具调用因此可能耗时较长。',
    '历史坑：这条链路曾因 RestClient 的 URI 编码问题（中文 city 参数）排查过，见项目记忆。',
  ],
},

{
  id: 'int-room-stats', group: 'room', method: 'GET', path: '/internal/agent/rooms/statistics',
  title: '按区域统计房间',
  desc: '一次 GROUP BY，按房型汇总房数与床位数。',
  alt: '房间统计：请求线程一次分组聚合',
  lanes: ['REQ2', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'InternalAuthInterceptor 全套校验' },
    { n: 2, lane: 0, text: 'RoomService.statisticsByRegion(regionId)\nDataScope 收窄后', to: 1, hopTo: 3, hop: 'GROUP BY' },
    { n: 3, lane: 0, text: 'roomMapper.statisticsByRegion(scoped)\n返回 List<RoomStatsVO>', },
  ],
  switches: [],
  refs: [
    ['InternalAgentController.roomStatistics', 'dorm-service/.../boot/controller/InternalAgentController.java:159'],
    ['RoomService.statisticsByRegion', 'dorm-service/.../domain/service/RoomService.java:443'],
  ],
  notes: [],
},

];
