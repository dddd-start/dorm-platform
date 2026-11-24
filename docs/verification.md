# 验收记录

环境：Linux / JDK 21.0.12 / Node 24.19 / MySQL 8.4.11（项目自带，端口 3307）/
Redis 8.0.5（项目自带，端口 6380）/ Maven 3.9.16（项目自带）。

## 1. 构建

```bash
bash scripts/build.sh
```

结果：

- `dorm-service/target/dorm-service.jar`（49 MB）
- `agent-service/target/agent-service.jar`（183 MB，含本地 ONNX 嵌入模型）
- `ui-service/dist/`（Vue 产物，gzip 后约 1.2 MB）

## 2. 进程与端口

```text
dorm-service   :8081   运行中
agent-service  :8082   运行中
ui-service     :8080   运行中
MySQL          :3307   运行中（dorm_db + agent_db）
Redis          :6380   运行中
```

## 3. 冒烟（`bash scripts/smoke.sh`）

```text
== 健康检查 ==
  ✓ dorm-service /api/health
  ✓ agent-service /api/health
  ✓ ui-service /healthz
  ✓ ui index.html
== 登录 ==
  ✓ superadmin 登录
== 业务 API（经 UI 反代） ==
  ✓ region options
  ✓ room availability
  ✓ stats overview
== Agent ==
  ✓ agent meta
  ✓ agent admin models
  ✓ create conversation
  ✓ agent SSE
----------------------------------------
通过：12  失败：0
```

## 4. 浏览器（`node scripts/ui-check.mjs`）

```text
✓ 登录页加载
✓ 登录跳转（/rooms）
✓ 页面 /rooms
✓ 页面 /admin/dashboard
✓ 页面 /agent
✓ 页面 /admin/agent-ops
✓ 无控制台错误
```

## 5. 数据边界

```text
dorm_db:  region, sys_user, dorm_building, dorm_room, dorm_bed,
          stay_apply, stay_order, audit_log, outbox_event,
          flyway_schema_history

agent_db: agent_conversation, agent_message, agent_message_archive,
          agent_tool_call, agent_task, agent_pending_approval,
          agent_usage_daily, agent_long_memory, agent_feedback,
          rag_document, rag_chunk, rag_retrieval_log,
          flyway_schema_history
```

两库无同名表；Agent 服务没有业务库连接配置。

## 6. Agent 工具行走 HTTP 的实测

SSE 轮次（提示词要求调用 `listRegions`）：

```text
event:TOOL
data:{"phase":"start",...,"tool":"listRegions",...}

event:TOOL
data:{"phase":"end",...,"failed":false,"tool":"listRegions",...}

event:DONE
data:{...,"status":"COMPLETED","graceful":true,...}
```

同一条链路里还验证了内部写接口（员工 `zhangwei`）：

- `POST /internal/agent/applies` → 返回 `applyNo=DA20261021000001`，`applySource=AGENT`；
- `POST /internal/agent/applies/{id}/cancel` → 状态变为 `CANCELLED`；
- 验证后已清理测试申请，数据库恢复种子状态。

## 7. RAG

```text
documentCount=10, chunkCount=110, indexSize=110
REGION-GUIDE-SEA → region_id=1001
REGION-GUIDE-EU  → region_id=1002
REGION-GUIDE-ME  → region_id=1003
REGION-GUIDE-NEA → region_id=1004
```

## 8. 单元测试

```bash
mvn test
```

结果：

- `dorm-contract`：47 个测试通过（状态机、日期、加密、脱敏）；
- `agent-service`：91 个测试通过（轮次上下文、Markdown 切分与知识库不变量、
  向量编码、SSE 通道契约、工具目录、工具护栏/取消、提示词注入检测、内容护栏）；
- `dorm-service`：暂无单测，靠下面第 9 节的全流程回归兜底；
- 合计 **138 个测试，0 失败**，`BUILD SUCCESS`。

这些测试不依赖数据库或模型，适合作为拆分后的第一道回归线。

## 9. 业务全流程回归

用 27 项全流程冒烟脚本对新 `dorm-service`（`BASE=http://127.0.0.1:8081`）执行：

```text
员工登录 → 查空房 → 提交申请 → 幂等重复提交 → 时间冲突 → 未入住退房
→ 员工越权审批 → 跨区域审批 → 管理员审批分床 → 重复抢床 → 入住
→ 重复入住 → 查订单 → 提前退房校验 → 退房释放床位 → 越权查看他人申请
→ 审计权限 → 统计看板 → 批量开关房
```

结果：**通过 27 / 失败 0**。

Agent 审计回归：轮次结束后 `audit_log` 中 `action=AGENT_TURN`，
`cost_ms=8517`，`after_json` 含 `agent/model/status/tokens/toolCalls/ragHits/latencyMs`；
确认异步审计既没有阻塞 `DONE`，也没有丢失明细。

RAG 命中指标回归：修复前 `rag_retrieval_log=4` 而 `agent_usage_daily.rag_hit_count=0`；
修复后员工政策问答 SSE `ragHits=11`，同日 `agent_usage_daily.rag_hit_count=18`（累计）。

优雅收口审计：同一轮 SSE `DONE + COMPLETED + graceful=true`，审计为
`AGENT_TURN_DEGRADED / result=SUCCESS / cost_ms=17554`，明细含
`toolCalls=4 / ragHits=11 / partialLength=20`。

## 10. 已知边界与后续计划

这一节如实列出当前实现的边界。它们不影响主链路可用性，但生产评估时应当知道。

### 10.1 一致性与部署

- **没有分布式事务**：Agent 建申请成功、但本地工具审计写失败时，两边不会自动补偿；
  靠幂等键 + 审计对账兜底，不做两阶段提交。
- **MySQL / Redis 单节点**：生产需要主从、哨兵与备份策略；本仓库按单机演示配置。
- **Docker Compose 路径未在本机实测**：开发机没有 Docker，`deploy/docker-compose.yml`
  按容器网络写好但没有"跑起来"的证据；README 里的验收数字全部来自 `scripts/*.sh` 的本机路径。
- **多实例共享本地目录**：如需跨机部署，文件类存储要换成对象存储适配器。

### 10.2 测试覆盖

- **dorm-service 业务层没有单测**：靠 27 项全流程回归 + Playwright 页面检查兜底；
  后续计划用 Testcontainers 补服务层与 SQL 层集成测试。
- **CI 只跑零外部依赖的单测**：需要 MySQL/Redis 的集成测试、需要真实模型的 SSE 真流式、
  Playwright 冒烟都不在 GitHub Actions 里（会消耗额度或需要外部服务）。
- **前端只有类型检查与构建**：没有 vitest 单测、ESLint 与 E2E 门禁。

### 10.3 领域与集成

- **知识库制度文本是演示样例**（虚构内容），不是任何公司的真实制度；区域、公寓、价格为合成数据。
- **AI 助手不接真实工单 / K8s / 发布系统**：写操作只写业务库，不触发外部系统。
- **没有做压测**：并发相关结论来自代码层面（条件更新、限流、队列容量），不是压测数字。
- **AI Provider 在验收时出现限流/下线提示**，Agent 按降级链切换并最终 `DONE(graceful)` 收口；
  生产需配置稳定模型与额度。流式测试会消耗真实额度，离线演练可设 `DORM_AGENT_MOCK=true`。
