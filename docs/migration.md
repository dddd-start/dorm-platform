# 从单体到三服务：拆分说明与代码落位

> 读者：想快速了解“原来单体里的模块，现在落在哪个服务、为什么这么切”的人。

---

## 1. 模块映射

| 单体侧模块/目录 | 现在的位置 | 处理方式 |
|---|---|---|
| `dorm-common` | `dorm-contract` | 原包名 `com.overseas.dorm.common.*` 保留，整体迁入契约模块 |
| `dorm-domain/entity`、`dto`、`vo` | `dorm-contract` | 两个服务共享的数据结构，包名保持不变，避免大面积改 import |
| `dorm-domain/config/service/support/mapper` | `dorm-service` | 业务服务独占；删除 Agent 专属 Mapper 与 `UsageWriteBuffer` |
| `dorm-boot`（除 Agent 控制器） | `dorm-service` | 业务 Controller、JWT 拦截器、异常处理、TraceId、Flyway |
| `dorm-boot/.../AgentController`、`AgentAdminController` | `agent-service/.../web` | 包名改为 `com.overseas.dorm.agent.web`，权限注解改为 Agent 侧 `RequireRole` |
| `dorm-agent` | `agent-service` | 整体迁入；工具的数据来源由本地 Service 改为 `DormServiceClient` |
| `dorm-ui` | `ui-service` | 构建产物从后端 `static/` 改为独立 `dist/`；Vite 代理改为 8081/8082 |
| `dorm-boot/.../static/*` | `ui-service/dist/*` | 后端不再托管前端；接口 404 保持真实 404 |
| `knowledge/*.md` | `agent-service/src/main/resources/knowledge` | 知识库属于 Agent 服务 |
| `V1__init_schema.sql`（1~9 节） | `dorm-service/.../V1__init_schema.sql` | 业务表 |
| `V1__init_schema.sql`（10 节起） | `agent-service/.../V1__agent_schema.sql` | Agent/RAG 表 |
| `V2__seed_data.sql` | `dorm-service/.../V2__seed_data.sql` | 全部是业务种子数据 |
| `V3__agent_message_archive.sql` | `agent-service/.../V2__agent_message_archive.sql` | 归档表 |
| `V4__dedupe_agent_message_turn.sql` | `agent-service/.../V3__dedupe_agent_message_turn.sql` | 唯一索引修复 |

---

## 2. 代码改动清单

### 2.1 共享契约

新增 `dorm-contract`：

- 依赖：Lombok、Jackson、Jakarta Validation、MyBatis-Plus Annotation、SLF4J；
- **不依赖** Spring Boot、DataSource、Redis；
- 新增 `ResultCode.fromCode(int)`：Agent 收到业务服务的错误码后还原为枚举，
  工具层仍能按 3005（床位占用）等码给出原有纠偏提示。

### 2.2 工具层

原：`RoomQueryTools` 注入 `RegionService/BuildingService/RoomService` 并直接查库。

新：注入 `DormServiceClient`，方法名通过兼容别名保持接近：

| 原调用 | 新实现 |
|---|---|
| `regionService.options()` | `GET /internal/agent/regions/options` |
| `regionService.bedSummary(id)` | `GET /internal/agent/regions/{id}/beds/summary` |
| `roomService.searchAvailability(...)` | `GET /internal/agent/rooms/availability` |
| `roomService.bedDetails(id)` | `GET /internal/agent/rooms/{id}/beds` |
| `buildingService.buildingMap(ids)` | `GET /internal/agent/buildings/map?ids=` |
| `applyService.page(...)` | `GET /internal/agent/applies` |
| `applyService.create(...)` | `POST /internal/agent/applies`（固定 `source=AGENT`） |
| `applyService.checkIn/checkOut` | `POST /internal/agent/applies/check-in|check-out` |
| `orderService.myOrders()` | `GET /internal/agent/orders/mine` |
| `orderService.overdueOrders(limit)` | `GET /internal/agent/orders/overdue` |
| `statsService.overview(...)` | `GET /internal/agent/stats/overview` |
| `authService.currentUser(false)` | `GET /internal/agent/profile` |

工具逻辑（参数解析、返回结构、脱敏、给模型的纠偏提示）未改。

### 2.3 身份透传

- `JwtAuthInterceptor`（Agent）验签后把用户 JWT 放进 `AgentTokenContext`；
- `AgentTurnContext` 增加 `token` 字段，`runAs` 时一并恢复，
  因此工具在模型流的虚拟线程（`agent-model-stream-*`）执行时仍能带对令牌；
- `DormServiceClient` 统一加 `Authorization` + `X-Internal-Token` + `X-Trace-Id`；
- 异步生成入口用 `AgentTokenContext.runAs` 包住，避免虚拟线程丢失 ThreadLocal。

### 2.4 审计

原 `AgentTurnService` 直接调用业务 `AuditService.success/fail`。
新实现通过 `POST /internal/agent/audit` 写入 dorm-service 的 `audit_log`，
审计链仍然集中在一张表；调用失败只记日志，不影响对话。

### 2.5 记忆与知识库

- `AgentMemoryService.renderUserProfile`：`SysUserMapper/RegionMapper` → `DormServiceClient.currentUser()`；
- `KnowledgeBaseLoader` 解析区域编码：`RegionMapper` → `/internal/catalog/regions/options`；
  该接口只校验服务令牌（启动时尚无用户请求），使用“系统只读主体”执行。
- 修复了“文档 version 未变但 region_id 从 null 变为有效值时不更新”的问题，
  否则业务分库后首次启动写入的空 `region_id` 会永久保留。

### 2.6 配置

- 单体 `application.yml` 拆成两份：`dorm-service/.../application.yml`（端口 8081、业务库）、
  `agent-service/.../application.yml`（端口 8082、Agent 库 + `dorm.agent.*`）；
- 新环境变量前缀统一为 `DORM_*`，数据库 URL 默认指向 3307；
- UI 不再输出到后端 `static/`，Vite `outDir=dist`。

---

## 3. 行为保持与差异

保持：

- 登录、JWT 会话白名单、epoch、角色与数据权限；
- 房源可用性算法、床位分配锁、审批/分房原子性、状态机；
- Agent 的 SSE 事件契约、断线续传、护栏、工具权限、去重、限流、RAG；
- 全部业务页面与 Agent 页面。

差异（有意为之）：

1. **Agent 工具多一次 HTTP 往返**：单次约几十毫秒；换取边界与独立扩容。
2. **Agent 启动依赖 dorm-service**：知识库区域解析需要业务服务；不可用时降级为全局文档并告警。
3. **共享 Redis**：登录会话必须跨服务可见；其余键按前缀隔离。
4. **UI 独立托管**：后端不再返回 `index.html`，拼错接口会得到真实 404（与原设计意图一致）。
5. **Docker 配置未本机验证**：本机无 Docker；本机脚本路径已实测。

---

## 4. 验证证据

- `scripts/smoke.sh`：12 项全通过（健康、登录、业务、Agent meta/admin、会话、SSE）。
- `scripts/ui-check.mjs`：登录 + 4 个关键页面渲染，控制台 0 错误。
- 数据库：`dorm_db` 10 张业务/基础表，`agent_db` 13 张 Agent/RAG 表，无交集。
- Agent 工具：`listRegions` 经 HTTP 调用成功，SSE `TOOL` 帧 `failed:false`。
- RAG：10 篇文档、110 切片；区域文档 `region_id` 正确写入 1001/1002/1003/1004。

---

## 5. 后续可演进项

- 服务间 mTLS / 短期服务令牌，替代静态 `X-Internal-Token`；
- Agent 工具调用增加熔断与超时分级（目前统一 10s）；
- 把 `dorm-contract` 发布为版本化制品，支持两个服务独立仓库发布；
- 增加 Testcontainers 集成测试，把 Compose 路径纳入 CI；
- 将 Agent 事件缓冲从 Redis Stream 迁移到专用消息系统（仅在并发进一步增长时）。
