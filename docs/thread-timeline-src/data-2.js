// ══════════════════════════════════════════════════════════════════
// agent-service · 会话与消息
// ══════════════════════════════════════════════════════════════════
const ENTRIES_2 = [

{
  id: 'conv-create', group: 'conv', method: 'POST', path: '/api/agent/conversations',
  title: '新建会话',
  desc: 'BizNo 取号走 Redis 原子递增，会话号与时间戳同源；撞号由唯一索引兜住并重试。全程单条请求线程。',
  alt: '新建会话：请求线程内经 Redis 取号后插入一行',
  lanes: ['REQ', 'REDIS', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'AgentController.createConversation(title?)' },
    { n: 2, lane: 0, text: 'UserContext.require()，取 userId', },
    { n: 3, lane: 0, text: 'ConversationService.createConversation\n@Transactional，重试上限 SEQ_RETRY=5', },
    { n: 4, lane: 0, text: 'BizNoGenerator.next(TYPE_CONVERSATION)\nsetIfAbsent 播种 + increment + expire', to: 1, hopTo: 5, hop: 'SETNX+INCR' },
    { n: 5, lane: 2, text: 'conversationMapper.insert\nuk_conversation_no 兜底', },
    { n: 6, lane: 0, text: '撞号 → DuplicateKeyException → 重试（最多 5 次）', },
    { n: 7, lane: 0, text: '返回 CreatedConversationVO(id, conversationNo, title)', },
  ],
  switches: [],
  refs: [
    ['AgentController.createConversation', 'agent-service/.../web/AgentController.java:95'],
    ['ConversationService.createConversation', 'agent-service/.../agent/stream/ConversationService.java:87-110'],
    ['BizNoGenerator.next', 'agent-service/.../domain/support/BizNoGenerator.java'],
  ],
  notes: [
    '单条虚拟线程，无跨线程。重试发生在同一条线程上，不涉及线程池。',
    '取号失败时的兜底是查库的最大值，这条路径会多一次 SELECT——仍然在同一条线程。',
  ],
},

{
  id: 'conv-list', group: 'conv', method: 'GET', path: '/api/agent/conversations',
  title: '会话列表（分页）',
  desc: '一页查询加一次 count，都在请求线程上同步完成。分页插件会额外占用 SQL 解析线程。',
  alt: '会话列表分页：请求线程两次查库，SQL 解析在插件线程池',
  lanes: ['REQ', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'current / size（PageMapper 归一化：size≤0→10，上限 200）' },
    { n: 2, lane: 0, text: 'ConversationService.listConversations\nLambdaQueryWrapper：userId + ACTIVE + orderBy lastMessageTime desc', },
    { n: 3, lane: 0, text: 'conversationMapper.selectPage\n分页插件先解析 SQL（mybatis-plus-jsqlParser-* 线程池）', to: 1, hopTo: 4, hop: 'SELECT + COUNT' },
    { n: 4, lane: 1, text: '两条 SQL：列表页 + 总数', },
    { n: 5, lane: 0, text: 'PageMapper.of(...) → ConversationVO 列表', },
  ],
  switches: [],
  refs: [
    ['AgentController.listConversations', 'agent-service/.../web/AgentController.java:106'],
    ['ConversationService.listConversations', 'agent-service/.../agent/stream/ConversationService.java:127-143'],
    ['PageMapper 归一化', 'dorm-contract/.../PageMapper.java:18'],
  ],
  notes: [
    '分页插件解析 SQL 时会用到 mybatis-plus-jsqlParser-* 线程池（实测 dump 里存在）；它只做 CPU 解析，不碰 I/O，不改变这张图的阻塞模型。',
    '上限 200 由 PageMapper 与 PaginationInterceptor 两处共同保证。',
  ],
},

{
  id: 'msg-list', group: 'conv', method: 'GET', path: '/api/agent/conversations/{id}/messages',
  title: '会话消息历史',
  desc: '一次全量查询后取尾部 N 条，工具调用与引用在内存里反序列化。',
  alt: '消息历史：请求线程查一次后内存截取',
  lanes: ['REQ', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'limit 默认 100，Math.clamp 到 MAX_MESSAGE_LIMIT' },
    { n: 2, lane: 0, text: 'ConversationService.listMessages\nrequireOwned(conversationId, userId) 不通过则返回空', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 1, text: 'messageMapper.selectAll(conversationId)\n<b>全量</b>读出，不是 LIMIT', },
    { n: 4, lane: 0, text: '取尾部 last N 条 → toMessageVO\nparseToolCalls / parseReferences 反序列化 JSON', },
    { n: 5, lane: 0, text: '返回 List<AgentMessageVO>', },
  ],
  switches: [],
  refs: [
    ['AgentController.messages', 'agent-service/.../web/AgentController.java:115'],
    ['ConversationService.listMessages', 'agent-service/.../agent/stream/ConversationService.java:451-462'],
    ['toMessageVO / parseToolCalls', 'agent-service/.../agent/stream/ConversationService.java:472, :508'],
  ],
  notes: [
    'SQL 没有 LIMIT，长会话会把整段历史读进内存再截尾部；归档任务（MessageArchiveJob）负责把冷数据搬走以控制这条查询的代价。',
    '归属校验失败返回空列表而不是 403，避免暴露会话是否存在。',
  ],
},

{
  id: 'conv-archive', group: 'conv', method: 'DELETE', path: '/api/agent/conversations/{id}',
  title: '归档会话（软删除）',
  desc: '只改状态位，不删消息。消息的物理清理留给归档定时任务。',
  alt: '归档会话：请求线程一次条件更新',
  lanes: ['REQ', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'ConversationService.archive\n@Transactional', },
    { n: 2, lane: 0, text: 'requireOwned(conversationId, userId)\n非主人一律返回 null（不区分不存在与无权限）', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: 'updateById(status = ARCHIVED)', to: 1, hopTo: 4, hop: 'UPDATE' },
    { n: 4, lane: 0, text: '返回 Result<Void>', },
  ],
  switches: [],
  refs: [
    ['AgentController.archive', 'agent-service/.../web/AgentController.java:124'],
    ['ConversationService.archive / requireOwned', 'agent-service/.../agent/stream/ConversationService.java:113, :146-156'],
  ],
  notes: ['归档后的会话不再出现在列表（列表查询带 status=ACTIVE 条件），但消息仍在库里。'],
},

{
  id: 'msg-feedback', group: 'conv', method: 'POST', path: '/api/agent/messages/{messageId}/feedback',
  title: '提交回答反馈',
  desc: '先试插入，撞唯一索引则改更新。之后把评分回写到检索日志，让点踩率能落到具体检索记录上。',
  alt: '提交反馈：请求线程两次写库，撞号转更新',
  lanes: ['REQ', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'rating 必填，FeedbackRating.of 校验\nNONE 或非法值直接 400 参数错', },
    { n: 2, lane: 0, text: 'messageMapper.selectById(messageId)\nconversationService.requireOwned 校验归属', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: 'feedbackMapper.insert(entity)', to: 1, hopTo: 4, hop: 'INSERT' },
    { n: 4, lane: 0, text: 'DuplicateKeyException（uk_message_user）→ 查出旧行 → updateById', },
    { n: 5, lane: 0, text: 'syncRetrievalLog：retrievalLogMapper.updateFeedbackByMessage\n失败只记 warn，不影响反馈本身', to: 1, hopTo: 6, hop: 'UPDATE' },
    { n: 6, lane: 0, text: '返回 true=新增 / false=更新', },
  ],
  switches: [],
  refs: [
    ['AgentController.feedback', 'agent-service/.../web/AgentController.java:168'],
    ['AgentFeedbackService.submit', 'agent-service/.../agent/audit/AgentFeedbackService.java:49-90'],
    ['syncRetrievalLog', 'agent-service/.../agent/audit/AgentFeedbackService.java:103'],
  ],
  notes: [
    '两张表若不打通，运维看板的点踩率会恒为 0，「哪个问题答得不好」这条运营线索就丢了。',
    '回写检索日志失败被吞掉是有意的：主记录已落库，不该因为观测数据失败而让用户看到错误。',
  ],
},

{
  id: 'mem-list', group: 'conv', method: 'GET', path: '/api/agent/memories',
  title: '我的长期记忆',
  desc: '查最近 50 条，纯读。长期记忆在每轮组 Prompt 时被 SystemPromptBuilder 读取，影响回答。',
  alt: '长期记忆列表：请求线程一次查询',
  lanes: ['REQ', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'UserContext.require() 取 userId' },
    { n: 2, lane: 0, text: 'AgentMemoryService.listMemories(userId)\nmemoryMapper.selectTop(userId, 50)', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: '映射为 AgentMemoryVO（含 typeLabel）', },
  ],
  switches: [],
  refs: [
    ['AgentController.memories', 'agent-service/.../web/AgentController.java:182'],
    ['AgentMemoryService.listMemories', 'agent-service/.../agent/memory/AgentMemoryService.java:300'],
  ],
  notes: ['记忆的写入发生在轮次结束后的摘要任务里（dorm-agent-task-*），不在这个接口。'],
},

{
  id: 'mem-clear', group: 'conv', method: 'DELETE', path: '/api/agent/memories',
  title: '清空长期记忆',
  desc: '一条事务内最多删 500 行，逐条 deleteById。仍是单条请求线程。',
  alt: '清空记忆：请求线程事务内逐条删除',
  lanes: ['REQ', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'AgentMemoryService.clearMemories\n@Transactional', },
    { n: 2, lane: 0, text: 'selectTop(userId, 500) 取出待删 ID', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: '逐条 deleteById（500 次往返，同一事务）', to: 1, hopTo: 4, hop: 'DELETE × N' },
    { n: 4, lane: 0, text: '返回 ClearMemoriesVO(deleted)', },
  ],
  switches: [],
  refs: [
    ['AgentController.clearMemories', 'agent-service/.../web/AgentController.java:188'],
    ['AgentMemoryService.clearMemories', 'agent-service/.../agent/memory/AgentMemoryService.java:319'],
  ],
  notes: ['逐条删除意味着 500 行就是 500 次数据库往返，全部压在这一条虚拟线程的同一条连接上。批量 DELETE 会更省，但当前上限是 500，还在可接受范围。'],
},

{
  id: 'agent-meta', group: 'conv', method: 'GET', path: '/api/agent/meta',
  title: '前端元数据（能力开关）',
  desc: '不查库，只把配置里的限流、流式、模型参数按角色裁剪后交给前端。',
  alt: '元数据接口：单条虚拟线程读配置',
  lanes: ['REQ'],
  steps: [
    { n: 1, lane: 0, text: 'UserContext.require().isAdmin()\n决定是否下发 OPS 类 Agent 元信息' },
    { n: 2, lane: 0, text: 'AgentProperties 直接读取：ratelimit / stream / model / mock / rag' },
    { n: 3, lane: 0, text: '拼 AgentMetaVO 返回，无 DB / Redis / 跨服务' },
  ],
  switches: [],
  refs: [
    ['AgentController.meta', 'agent-service/.../web/AgentController.java:197'],
    ['AgentProperties', 'agent-service/.../config/AgentProperties.java'],
  ],
  notes: ['前端据此决定是否显示「停止生成」按钮、心跳间隔如何显示等。纯内存，无阻塞。'],
},

{
  id: 'replay-conv', group: 'conv', method: 'GET', path: '/api/agent/replay/{conversationId}',
  title: '会话回放（用户视角）',
  desc: '查消息再逐条查工具调用。非会话主人需要管理员权限。',
  alt: '会话回放：请求线程查询消息后逐条查工具调用',
  lanes: ['REQ', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'requireOwned；非主人 → dataScope.assertAdmin()', },
    { n: 2, lane: 0, text: 'messageId != null ? selectById : messageMapper.selectAll', to: 1, hopTo: 3, hop: 'SELECT' },
    { n: 3, lane: 0, text: '每条消息 → toolCallMapper.selectByMessage(messageId)\n<b>N+1</b>：一条消息一次查询', to: 1, hopTo: 4, hop: 'SELECT × N' },
    { n: 4, lane: 0, text: '组装 AgentReplayVO（消息 + 工具调用 + 反馈评分）', },
  ],
  switches: [],
  refs: [
    ['AgentController.replay', 'agent-service/.../web/AgentController.java:231'],
    ['AgentAuditService.replay / listToolCalls', 'agent-service/.../agent/audit/AgentAuditService.java:216, :253-281'],
  ],
  notes: ['N+1 在这里不会拖垮系统（回放是低频管理操作），但长会话的回放会明显变慢。'],
},

// ══════════════════════════════════════════════════════════════════
// agent-service · 管理后台（类级 @RequireRole SUPER_ADMIN / REGION_ADMIN）
// ══════════════════════════════════════════════════════════════════
{
  id: 'admin-models', group: 'admin', method: 'GET', path: '/api/agent/admin/models',
  title: '模型健康与熔断状态',
  desc: '读进程内的熔断计数器，不查库、不探活上游。',
  alt: '模型健康：请求线程读内存中的熔断状态',
  lanes: ['REQ'],
  steps: [
    { n: 1, lane: 0, text: 'JwtAuthInterceptor 校验 + @RequireRole(SUPER_ADMIN, REGION_ADMIN)' },
    { n: 2, lane: 0, text: 'AgentAdminController.models → modelRegistry.health()\n读内存里的成功/失败计数与熔断状态' },
    { n: 3, lane: 0, text: '返回 List<ModelHealthVO>' },
  ],
  switches: [],
  refs: [
    ['AgentAdminController 类级权限', 'agent-service/.../web/AgentAdminController.java:50-54'],
    ['AgentAdminController.models', 'agent-service/.../web/AgentAdminController.java:92'],
    ['ModelRegistry.health', 'agent-service/.../agent/model/ModelRegistry.java:159'],
  ],
  notes: ['熔断状态由每一轮对话的成功/失败回调维护（reportSuccess / reportFailure，在 VT-MODEL 上），这个接口只读。'],
},

{
  id: 'admin-usage', group: 'admin', method: 'GET', path: '/api/agent/admin/usage',
  title: '用量与成本趋势',
  desc: '两次聚合查询：按天用量、按工具统计。days 被 clamp 到 90。',
  alt: '用量趋势：请求线程两次聚合查询',
  lanes: ['REQ', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'days clamp 到 1..90' },
    { n: 2, lane: 0, text: 'agentAuditService.usageTrend(days)\n按天聚合 token / 调用次数', to: 1, hopTo: 3, hop: 'GROUP BY' },
    { n: 3, lane: 0, text: 'agentAuditService.toolStatistics(days)\n按工具名聚合调用与失败数', to: 1, hopTo: 4, hop: 'GROUP BY' },
    { n: 4, lane: 0, text: '组 UsageStatsVO', },
  ],
  switches: [],
  refs: [
    ['AgentAdminController.usage', 'agent-service/.../web/AgentAdminController.java:98'],
    ['AgentAuditService.usageTrend', 'agent-service/.../agent/audit/AgentAuditService.java:195'],
    ['AgentAuditService.toolStatistics', 'agent-service/.../agent/audit/AgentAuditService.java:284'],
  ],
  notes: [
    '用量数据由 UsageWriteBuffer 先在内存聚合、每 5s 批量落库（@Scheduled），所以这里读到的是秒级延迟的快照。',
    '两条查询串行，都在同一条虚拟线程的同一条 Hikari 连接上。',
  ],
},

{
  id: 'admin-knowledge', group: 'admin', method: 'GET', path: '/api/agent/admin/knowledge',
  title: '知识库索引状态',
  desc: '内存向量库的自检信息：文档数、切片数、生效参数、上次装载报告。',
  alt: '知识库状态：请求线程读内存索引元信息',
  lanes: ['REQ', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'ragService.indexStatus()' },
    { n: 2, lane: 0, text: 'embeddingStore.size()（内存）\ndocumentMapper.countByStatus / chunkMapper 计数', to: 1, hopTo: 3, hop: 'COUNT' },
    { n: 3, lane: 0, text: '拼 RagIndexStatusVO：topK / minScore / mmr 等实际生效参数', },
  ],
  switches: [],
  refs: [
    ['AgentAdminController.knowledge', 'agent-service/.../web/AgentAdminController.java:109'],
    ['RagService.indexStatus', 'agent-service/.../agent/rag/RagService.java:351'],
    ['MysqlEmbeddingStore', 'agent-service/.../agent/rag/MysqlEmbeddingStore.java'],
  ],
  notes: ['「实际生效参数」值得看：yml 里的值与 AgentProperties 默认值有差异（bufferMaxLength 默认 4000，yml 覆盖为 1000）。'],
},

{
  id: 'admin-rebuild', group: 'admin', method: 'POST', path: '/api/agent/admin/knowledge/rebuild',
  title: '重建知识库索引',
  desc: '这是唯一会在请求线程上跑分钟级 CPU + 跨服务 HTTP 的接口。逐篇向量化、逐片缓存查 Redis。',
  alt: '重建索引：请求线程内逐篇向量化并跨服务解析区域',
  lanes: ['REQ', 'REDIS', 'MYSQL', 'DORM'],
  steps: [
    { n: 1, lane: 0, text: 'knowledgeBaseLoader.rebuildAll()\ntransactionTemplate.execute（类内自调用使 @Transactional 无效，故用模板）', },
    { n: 2, lane: 0, text: 'scanKnowledgeFiles：classpath:knowledge/*.md（10 篇）\nparse front-matter：docCode / title / version / regionCode', },
    { n: 3, lane: 0, text: 'resolveRegionId(regionCode)\ndormService.catalogRegionOptions() 跨服务', to: 3, hopTo: 4, hop: 'GET /internal/catalog' },
    { n: 4, lane: 3, text: 'RegionService.options()，只校验内部令牌', },
    { n: 5, lane: 0, text: 'documentMapper.selectByCode 比对版本\n新建 / 版本升级 / 区域变更才重新索引', to: 2, hopTo: 6, hop: 'SELECT' },
    { n: 6, lane: 0, text: '每片：embedWithCache(chunk)\n先 GET dorm:cache:embed:{sha256}，未命中跑 BGE 本地推理再 SET EX 86400', to: 1, hopTo: 7, hop: 'GET/SET' },
    { n: 7, lane: 0, text: 'VectorCodec.encode → chunkMapper.insert（含 MEDIUMBLOB）\ndocumentMapper.updateById(indexedVersion, chunkCount)', to: 2, hopTo: 8, hop: 'INSERT' },
    { n: 8, lane: 0, text: 'embeddingStore.reload()：写锁内全量重载向量到内存\ninvalidateRetrievalCache()：redis KEYS 删缓存', to: 1, hopTo: 9, hop: 'KEYS + DEL' },
    { n: 9, lane: 0, text: '自检：索引为空但库里有切片 → ERROR 日志\n返回 KnowledgeLoadReportVO', },
  ],
  switches: [
    { n: 3, lane: 'VT-REQ → dorm-service', how: 'DormServiceClient 用 RestClient（JDK HttpURLConnection）同步调用', why: '同步阻塞，虚拟线程在此让出载体；耗时受 dorm.service.timeout-seconds=10 约束' },
    { n: 8, lane: 'VT-REQ 内部', how: 'ReentrantReadWriteLock.writeLock()', why: '重载期间所有检索读锁阻塞；故意用 ReentrantLock 而非 synchronized，避免 pin 载体线程' },
  ],
  refs: [
    ['AgentAdminController.rebuild', 'agent-service/.../web/AgentAdminController.java:116'],
    ['KnowledgeBaseLoader.rebuildAll / doRebuild', 'agent-service/.../agent/rag/KnowledgeBaseLoader.java:126, :153'],
    ['embedWithCache', 'agent-service/.../agent/rag/KnowledgeBaseLoader.java:362'],
    ['invalidateRetrievalCache（用 KEYS）', 'agent-service/.../agent/rag/KnowledgeBaseLoader.java:392-399'],
    ['MysqlEmbeddingStore.reload', 'agent-service/.../agent/rag/MysqlEmbeddingStore.java:74'],
  ],
  notes: [
    '这个接口会在请求线程上跑几分钟：逐片做 BGE 本地推理（CPU 密集）+ 逐片 Redis + 逐片 INSERT。',
    '第 8 步的写锁期间所有检索被阻塞，线上应在低峰期调用。',
    'invalidateRetrievalCache 用的是 redis KEYS（阻塞单线程 Redis），与 AgentStreamBuffer 里改成 SCARD 的做法不一致。',
    '启动时 @EventListener(ApplicationReadyEvent) 会走同一条 rebuildAll，那时跑在启动线程上，不占请求线程。',
  ],
},

{
  id: 'admin-rag-quality', group: 'admin', method: 'GET', path: '/api/agent/admin/rag-quality',
  title: 'RAG 生产质量指标',
  desc: '从检索日志聚合：无命中率、平均最高相似度、缓存命中率、点赞点踩。',
  alt: 'RAG 质量指标：请求线程聚合检索日志',
  lanes: ['REQ', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'days clamp 到 1..90，默认 30' },
    { n: 2, lane: 0, text: 'ragService.qualityMetrics(days)\nragRetrievalLogMapper.qualityMetrics + topMissedQueries', to: 1, hopTo: 3, hop: '聚合 × 2' },
    { n: 3, lane: 0, text: '拼 RagQualityVO，含「一条都没查到」的问题榜', },
  ],
  switches: [],
  refs: [
    ['AgentAdminController.ragQuality', 'agent-service/.../web/AgentAdminController.java:123'],
    ['RagService.qualityMetrics', 'agent-service/.../agent/rag/RagService.java:370'],
  ],
  notes: ['点踩率能算出来，前提是 feedback 接口把评分回写到了 rag_retrieval_log（见 msg-feedback 图第 5 步）。'],
},

{
  id: 'admin-rag-eval', group: 'admin', method: 'POST', path: '/api/agent/admin/rag-eval',
  title: '运行 RAG 召回评测',
  desc: '对金标集逐题真跑一遍向量检索，耗时与题数成正比。全部在请求线程上同步完成。',
  alt: 'RAG 评测：请求线程逐题检索',
  lanes: ['REQ', 'REDIS', 'MYSQL', 'RAGLOG'],
  steps: [
    { n: 1, lane: 0, text: 'ragEvaluator.evaluate(topK)，k = max(topK, 5)' },
    { n: 2, lane: 0, text: 'loadGoldenSet：classpath:rag-eval/golden-set.jsonl' },
    { n: 3, lane: 0, text: '逐题：ragService.retrieveForEvaluation（不设 minScore）\n每题一次 embedWithCache → 缓存未命中则本地推理', to: 1, hopTo: 4, hop: 'GET/SET' },
    { n: 4, lane: 0, text: 'embeddingStore.searchByVector：内存暴力余弦\n（不查库，向量在启动时已全量载入）', to: 2, hopTo: 5, hop: '内存向量' },
    { n: 5, lane: 3, text: 'retrievalLogExecutor 异步落检索日志\nSemaphore(200) 限在途', },
    { n: 6, lane: 0, text: '算 Recall@1/3/5、MRR、关键词命中率、No-Hit 率\n返回逐题明细 + 未召回清单', },
  ],
  switches: [
    { n: 5, lane: 'VT-REQ → VT-RAGLOG', how: 'RagService.retrievalLogExecutor.execute()', why: '评测主循环不因日志落库变慢；信号量防止日志线程堆积' },
  ],
  refs: [
    ['AgentAdminController.ragEval', 'agent-service/.../web/AgentAdminController.java:136'],
    ['RagEvaluator.evaluate', 'agent-service/.../agent/rag/RagEvaluator.java:76'],
    ['MysqlEmbeddingStore.searchByVector', 'agent-service/.../agent/rag/MysqlEmbeddingStore.java:309'],
    ['retrievalLogExecutor + Semaphore', 'agent-service/.../agent/rag/RagService.java:415-425'],
  ],
  notes: [
    '这是唯一一张 VT-RAGLOG 作为主要参与者的图；生产对话里同一线程也落在检索日志上。',
    '向量检索不查 MySQL：MysqlEmbeddingStore 启动时把全部向量载进内存做暴力余弦，MySQL 只做持久化。',
    '评测题目数 × 单题检索耗时 = 接口总耗时，题多时会撞到网关超时。',
  ],
},

{
  id: 'admin-golden', group: 'admin', method: 'GET', path: '/api/agent/admin/rag-eval/golden-set',
  title: '金标集题目清单',
  desc: '读 classpath 下的 jsonl，纯文件读取，无检索。',
  alt: '金标集清单：请求线程读资源文件',
  lanes: ['REQ'],
  steps: [
    { n: 1, lane: 0, text: 'ragEvaluator.loadGoldenSet()' },
    { n: 2, lane: 0, text: '读 classpath:rag-eval/golden-set.jsonl 并解析' },
    { n: 3, lane: 0, text: '映射 GoldenItemVO，返回', },
  ],
  switches: [],
  refs: [
    ['AgentAdminController.goldenSet', 'agent-service/.../web/AgentAdminController.java:143'],
    ['RagEvaluator.loadGoldenSet', 'agent-service/.../agent/rag/RagEvaluator.java:192'],
  ],
  notes: ['纯 CPU + 本地文件，无阻塞 I/O。'],
},

{
  id: 'admin-tools', group: 'admin', method: 'GET', path: '/api/agent/admin/tools',
  title: '已装配的 Agent 与工具清单',
  desc: '把 AgentRegistry 的装配结果、工具目录与路由关键词表一次性吐出来，全是内存结构。',
  alt: '工具清单：请求线程读内存注册表',
  lanes: ['REQ'],
  steps: [
    { n: 1, lane: 0, text: 'ToolCatalog.descriptors()：工具名 / 标签 / 只读属性' },
    { n: 2, lane: 0, text: 'agentRegistry.assembled()：每个专家 Agent 挂了哪些工具' },
    { n: 3, lane: 0, text: 'AgentRouter.keywordTable()：意图路由关键词打分表' },
    { n: 4, lane: 0, text: '附 maxToolInvocations / maxToolRoundTrips / turnTimeoutSeconds' },
  ],
  switches: [],
  refs: [
    ['AgentAdminController.tools', 'agent-service/.../web/AgentAdminController.java:157'],
    ['ToolCatalog.descriptors', 'agent-service/.../agent/tool/ToolCatalog.java:136'],
    ['AgentRegistry.assembled', 'agent-service/.../agent/tool/AgentRegistry.java:223'],
  ],
  notes: ['要理解某轮对话为什么调了某个工具，先看这里：工具是按专家 Agent 装配的，不是全局可用。'],
},

{
  id: 'admin-replay', group: 'admin', method: 'GET', path: '/api/agent/admin/replay',
  title: '会话回放（管理员）',
  desc: '与用户版同一条路径，但跳过归属校验，可直接看任意会话。',
  alt: '管理员回放：请求线程查询消息后逐条查工具调用',
  lanes: ['REQ', 'MYSQL'],
  steps: [
    { n: 1, lane: 0, text: 'conversationId 必填（无默认值），类级 @RequireRole 已保证管理员' },
    { n: 2, lane: 0, text: 'agentAuditService.replay(conversationId, messageId)' },
    { n: 3, lane: 0, text: 'messageMapper.selectAll 或 selectById', to: 1, hopTo: 4, hop: 'SELECT' },
    { n: 4, lane: 0, text: '逐消息 toolCallMapper.selectByMessage（N+1）', to: 1, hopTo: 5, hop: 'SELECT × N' },
    { n: 5, lane: 0, text: '拼 AgentReplayVO', },
  ],
  switches: [],
  refs: [
    ['AgentAdminController.replay', 'agent-service/.../web/AgentAdminController.java:172'],
    ['AgentAuditService.replay', 'agent-service/.../agent/audit/AgentAuditService.java:253-281'],
  ],
  notes: ['与 replay-conv 唯一的差别是不做归属校验；两者共用同一个 service 方法，线程模型完全相同。'],
},

];
