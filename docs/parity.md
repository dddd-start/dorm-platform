# 功能一致性与已知差异

> 结论：业务与 UI 功能完整，27 项全流程冒烟验证通过；
> Agent 功能面已覆盖，调用方式由“本地 Service”改为“HTTP 调用业务服务”，
> 这是拆分的设计目标而非功能缺失。本轮另修复两个拆分引入的退化点，
> 以及一个审计字段丢失问题。下面把证据、差异和剩余风险分开写清楚。

---

## 1. 对比方法

不是只看“能不能启动”，而是四条线同时对比：

1. **源码差异**：`diff -rq` 对比原 `dorm-common/dorm-domain/dorm-boot/dorm-agent/dorm-ui`
   与新仓库对应目录；
2. **接口/工具清单**：对比 Controller、Agent 工具方法、`DormServiceClient` 映射；
3. **全流程回归**：27 项断言直接打 `dorm-service`；
4. **单元测试与运行时**：138 个单测 + 三服务启动 + SSE/工具/RAG/审计实测。

---

## 2. 业务服务：高一致

### 2.1 源码

- `dorm-common` → `dorm-contract`：除 `ResultCode.fromCode(int)`（Agent 还原业务错误码用）
  外无差异；
- `dorm-domain` 的 `entity/dto/vo` 整体迁入 `dorm-contract`，逐文件 diff **无差异**；
- `dorm-domain` 的 Service/Mapper/Support：除 `ExecutorConfig`（删掉 Agent 专属线程池）
  外无差异；
- `dorm-boot`：除 `WebMvcConfig`（前端不再由后端托管）外无差异；
- 新增 `InternalAgentController` 与 `InternalAuthInterceptor`，是拆分新增的内部入口，
  不改变原有 `/api/**` 行为。

### 2.2 全流程冒烟

27 项断言对新服务执行，**27/27 通过**，覆盖：

- 登录、错误密码、未登录拦截；
- 查空房、申请创建、幂等键、时间冲突；
- 未入住退房、员工越权审批、跨区域审批；
- 审批分床、重复抢同一张床、入住、重复入住；
- 订单查询、提前退房原因校验、退房释放床位；
- 越权查看他人申请、审计读写权限；
- 统计看板、批量开关房。

这说明业务规则、状态机、数据权限、床位并发控制没有在搬迁中改变。

---

## 3. UI：源码一致

`dorm-ui/src` 与新 `ui-service/src` 逐文件 diff **无差异**。变化只在工程外侧：

- 构建产物从后端 `static/` 改为独立 `dist/`；
- 开发/生产代理分别指向 8081（业务）与 8082（Agent）；
- 新增零依赖 `server.mjs` 与 Nginx 配置；
- 后端不再做 SPA 回退，拼错接口返回真实 404。

Playwright 验证：登录跳转、`/rooms`、`/admin/dashboard`、`/agent`、
`/admin/agent-ops` 均正常，控制台 0 错误。

---

## 4. Agent：功能面覆盖，集成方式改变

### 4.1 工具映射

Agent 的 7 组工具全部保留，原 `XxxService` 注入改为 `DormServiceClient`，
方法名通过兼容别名保持接近，工具内部的参数解析、返回结构、脱敏、给模型的纠偏提示未改。

| 工具组 | 原依赖 | 新路径 |
|---|---|---|
| `RoomQueryTools` | Region/Building/RoomService | `/internal/agent/regions|buildings|rooms/**` |
| `ApplyTools` | ApplyService | `/internal/agent/applies/**` |
| `OrderTools` | Order/Apply/StatsService | `/internal/agent/orders|applies|stats/**` |
| `OpsTools` | Stats/Apply/Room/OrderService | 同上 |
| `ProfileTools` | AuthService | `/internal/agent/profile` |
| `RegionResolver` | RegionService | `/internal/agent/regions/options` |
| 知识库加载 | RegionMapper | `/internal/catalog/regions/options` |

### 4.2 运行时验证

- SSE 正常产出 `START/REASONING/DELTA/TOOL/DONE` 帧；
- `listRegions` 经 HTTP 调业务成功，`TOOL` 帧 `failed:false`；
- 员工通过内部接口创建申请、取消申请成功；
- RAG 10 篇文档 / 110 切片，区域文档 `region_id` 正确；
- 审计 `AGENT_TURN` 落库，`cost_ms` 与明细字段完整。

### 4.3 单测

Agent 侧 91 个测试通过，覆盖：

- 轮次上下文/跨线程恢复；
- Markdown 切分、知识库语料不变量、向量编码；
- SSE 通道契约；
- 工具目录、工具权限/取消；
- 提示词注入检测、内容护栏。

需要数据库/模型的集成型测试不在单测范围内（见第 6 节），单测只覆盖纯逻辑与契约。

---

## 5. 本轮发现并修复的问题

| 问题 | 影响 | 修复 |
|---|---|---|
| Agent 审计改为同步 HTTP | 业务服务网络异常时可能拖慢 `DONE`；与原“异步本地审计”不一致 | 改为虚拟线程异步审计，捕获 token/trace 后再提交 |
| 审计明细丢失 | `audit_log.after_json` 只剩 action，没有 model/tokens/toolCalls | 内部审计接口接收 `detail`，恢复原字段 |
| `cost_ms` 丢失 | 审计无法按耗时分析 | 内部审计接口接收 `costMs`，已实测 `8517` |
| 每轮拼 Prompt 强依赖业务服务 | 业务服务抖动会连带纯政策问答 | `AgentMemoryService` 捕获异常，降级用 JWT 内身份字段 |
| RAG 区域归属不更新 | 首次启动 `region_id=null` 永久残留 | 比较 `regionId` 变化也触发更新 |
| RAG 命中数恒 0 | `rag_hit_count`/SSE `ragHits` 与真实检索日志不一致 | `PolicyTools` 把命中写入 `AgentTurnContext`；实测 11 条命中同步到 SSE 与用量表 |
| 优雅收口写审计 FAILED | 用户看到 `DONE/COMPLETED`，审计看到失败 | 统一为 `AGENT_TURN_DEGRADED`（SUCCESS），并带降级明细 |

这些修复都在本轮重新构建、重启并验证过，不是“代码改了但没跑”。

---

## 6. 已知差异与剩余风险

### 6.1 有意差异（拆分目标，不是 bug）

1. **Agent 工具多一次 HTTP 往返**：增加延迟，但换来独立部署与数据边界；
2. **Agent 启动时区域知识库解析依赖业务服务**：业务未就绪时先按全局文档装载，
   下次启动修正；不影响对话主链路；
3. **共享 Redis**：登录会话/吊销必须跨服务可见；
4. **UI 独立托管**：后端不再返回 `index.html`；
5. **业务单号与 Agent 单号各自生成**：各自只依赖自己的库。

### 6.2 未迁移的测试（测试覆盖缺口，不是已知功能失败）

以下测试类未纳入单测（依赖真实模型/ONNX 或需外部服务）：

- `FailoverStreamingChatModelTest`（需要模型）
- `LocalEmbeddingModelTest`（加载 ONNX，慢）
- `MinimalAgentAssemblyTest`（Spring 全上下文）
- `OfflineRagRetrievalEvalTest`（需要嵌入模型）
- `OpsToolExposureTest`（工具构造器已改为 HTTP 客户端）
- `PolicyToolsUntrustedContentTest`
- `SystemPromptStructureTest`
- `ToolIdentityBoundaryTest`

建议后续用 Testcontainers 或 `DORM_AGENT_MOCK=true` 把这几个补成集成测试。

### 6.3 性能/运维风险

1. **工具调用是阻塞式 HTTP**：运行在模型流的虚拟线程上（`agent-model-stream-*`，
   见 `VirtualThreadJdkHttpClient`）。虚拟线程遇到阻塞 I/O 会让出载体线程，
   但工具内部若有 `synchronized`/native 调用仍可能 pin 住载体线程；
   当前 10s 超时 + 业务接口较轻，日常使用没问题；压测时应重点观察。
2. **所有 Agent 实例共享 Redis**：Redis 故障时登录校验 fail-closed、SSE 不可用；
   这是有意的安全取舍。
3. **Docker Compose 未本机实测**：本机没有 Docker；配置按容器 DNS 写好，
   但没有“跑起来”的证据。
4. **`/api/agent/admin/knowledge` 的 `indexSize` 报告值**：启动日志里曾出现 0，
   实际切片 110，属于报告时点问题，不影响检索；已记录，未作为功能阻塞。

### 6.4 结论

- **业务/UI：功能一致**，有 27 项全流程回归与逐文件对照支撑；
- **Agent：功能面覆盖，集成方式按拆分目标改变**；核心链路和 91 个单测通过；
- **没有发现“业务功能缺失”级别的 bug**；发现并修复了 2 个拆分退化点 + 2 个审计/RAG 问题；
- 剩余风险主要是**性能、未迁移的集成测试、Docker 未实测**，不是“已知业务错误”。
