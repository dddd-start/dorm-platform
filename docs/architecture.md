# 架构设计：三服务拆分

> 内容涵盖边界划分依据、请求路径与故障隔离。
> 本仓库按生产形态重新划分边界；不依赖任何既有部署，可从零起环境。

---

## 1. 拆分目标与约束

目标：

1. 业务、AI、前端可以分别构建、分别发布、分别扩容；
2. 数据所有权清晰：没有“两个服务同时写一张表”；
3. 原有安全语义不降级：JWT、会话吊销、数据权限、工具权限仍然只在服务端判定；
4. 不为了“微服务”引入不必要的中间件：本阶段不引入注册中心、消息队列、Service Mesh。

约束：

- Agent 的业务工具必须拿到最新业务数据（床位是否被占、申请状态），因此**不能缓存业务库快照**；
- SSE 断点续传依赖跨实例共享的流缓冲，因此 Redis 必须共享；
- 本机无 Docker，交付“本机脚本可跑 + 容器配置可用”两条路径。

---

## 2. 服务职责

### 2.1 dorm-service

- 业务数据的唯一写入方；
- 对外 `/api/**`（浏览器）与对内 `/internal/**`（agent-service）；
- JWT 签发与校验、Redis 会话白名单/epoch、功能权限（`@RequireRole`）、
  行级数据权限（`DataScope`）、审计（`audit_log`）、Outbox、分布式锁；
- 不含任何模型、Prompt、SSE 代码。

### 2.2 agent-service

- LangChain4j 装配、意图路由、专家 Agent、Prompt、工具编排；
- SSE 事件协议、Redis Stream 缓冲、Pub/Sub 实时推送、断点续传、取消；
- 输入/输出护栏、注入检测、PII 脱敏；
- Agent 库：会话、消息、归档、工具调用审计、用量、长期记忆、RAG 文档/切片/检索日志；
- 业务工具通过 `DormServiceClient` 调 dorm-service，**不装配业务 Mapper**。

### 2.3 ui-service

- Vue 3 + Vite 构建产物；
- 静态资源与 SPA history 回退；
- 反向代理：`/api/agent/* → agent-service`，`/api/* → dorm-service`；
- SSE 透传（关闭缓冲、长读超时）；
- 不存业务状态、不直连数据库。

依赖方向：

```
ui-service ──HTTP──▶ dorm-service
     │                   ▲
     └──HTTP──▶ agent-service ──HTTP──▶ dorm-service
                      │
               (仅共享 dorm-contract 数据契约)
```

`dorm-contract` 只含数据与纯函数（响应包装、错误码、枚举、身份对象、DTO/VO、实体映射），
不含 Spring 容器、数据库访问与业务逻辑。两个后端各自打包，不共享运行时。

---

## 3. 数据所有权

| 库 | 表 | 所有者 |
|---|---|---|
| `dorm_db` | `region`、`sys_user`、`dorm_building`、`dorm_room`、`dorm_bed`、`stay_apply`、`stay_order`、`audit_log`、`outbox_event` | dorm-service |
| `agent_db` | `agent_conversation`、`agent_message`、`agent_message_archive`、`agent_tool_call`、`agent_task`、`agent_pending_approval`、`agent_usage_daily`、`agent_long_memory`、`agent_feedback`、`rag_document`、`rag_chunk`、`rag_retrieval_log` | agent-service |

每个服务独立 Flyway 历史表，独立账号：

- `dorm` 只授权 `dorm_db.*`；
- `agent` 只授权 `agent_db.*`；
- 没有跨库 JOIN，也没有跨库外键。

### 3.1 单号生成

`BizNoGenerator` 在两个服务各有一份实现：

- 业务单号（`DA`/`DO`）只在 dorm-service 生成，播种查询 `stay_apply/stay_order`；
- 会话/任务/审批号（`AC`/`AT`/`AP`）只在 agent-service 生成，播种查询 Agent 表；
- 两边都保留“Redis 丢失 → 用库内当天最大值重新播种”的兜底逻辑。

这不是重复代码，而是**每个服务只依赖自己的库**的必然结果。

---

## 4. 关键调用链

### 4.1 登录与会话

```
POST /api/auth/login
  ui-service ──▶ dorm-service
                 ├─ 校验 BCrypt 密码 / 失败锁定
                 ├─ 生成 sessionId（jti）
                 ├─ Redis: SET dorm:auth:session:{userId}:{sid}
                 ├─ 读取/写入 dorm:auth:epoch:{userId}
                 └─ 签发 JWT（含 sid + epoch）
```

Agent 侧不签发令牌，只验证：

```
agent-service JwtAuthInterceptor
  ├─ 用共享密钥验签/验过期/验 issuer
  ├─ 读 Redis：dorm:auth:session:{userId}:{sid} + dorm:auth:epoch:{userId}
  └─ 失败即 401（登出/禁用/角色变更立即生效）
```

### 4.2 一次 AI 工具调用

```
Agent 模型决定调用 listRegions
  └─ GuardedToolProvider（工具白名单/去重/权限/取消/超时）
      └─ RoomQueryTools.listRegions()
          └─ DormServiceClient.regionOptions()
              GET /internal/agent/regions/options
              Headers: Authorization: Bearer <用户JWT>
                       X-Internal-Token: <服务令牌>
                       X-Trace-Id: <同一条链路>
                  └─ dorm-service InternalAuthInterceptor
                      ├─ 校验服务令牌
                      ├─ 解析用户 JWT + 会话白名单
                      ├─ UserContext.set(loginUser)
                      └─ RegionService.options()（数据权限仍在业务侧收口）
```

为什么同时要两道令牌：

| 只做用户 JWT | 只做内部令牌 |
|---|---|
| 浏览器也能打内部接口，攻击面变大 | Agent 可以伪造任意 userId，越权 |
| **两者同时校验**：调用方必须是可信服务，且代表一个真实、未吊销的用户 | |

内部令牌使用常量时间比较；生产建议再加 mTLS/网络策略。

### 4.3 SSE

事件契约不变（与原单体一致）：

```
id:<seq>
event:<START|REASONING|DELTA|TOOL|REFERENCE|HUMAN_REQUIRED|DONE|BLOCKED|INTERRUPTED|ERROR>
data:<json>
```

- 每个事件分配单调 `seq`，写 Redis Stream（历史）+ Pub/Sub（实时）；
- 重连：先订阅 → 补历史 → 切实时并按 seq 去重；
- 生成线程与 socket 写入解耦（每连接一个有界队列 + 虚拟线程 writer），
  慢客户端不会阻塞生产者；
- 前端可直接用 `fetch` 读流，也可用 `GET /api/agent/chat/stream/{messageId}?lastEventId=N`
  走 `EventSource`（token 用查询参数）。

逐图拆解（接入时序、生成与收口、续传、出站背压、状态机、LLM 装配）见
[`agent-sse-design.md`](agent-sse-design.md)。

---

## 5. 失败隔离

| 故障 | 表现 | 设计意图 |
|---|---|---|
| agent-service 挂 | UI 仍可登录、查房、提交申请；`/api/agent/*` 502 | AI 是增强能力，不阻塞主业务 |
| dorm-service 挂 | 登录/业务不可用；Agent 工具调用返回“业务服务暂时不可用” | Agent 不会拿旧数据编造结果 |
| MySQL 业务库挂 | 业务不可用；Agent 会话仍可读写 agent_db，但工具全部失败 | 数据边界清晰，故障可定位 |
| Redis 挂 | 登录校验 fail-closed（401）；Agent 流不可用 | 不把“读不到吊销状态”当成放行 |
| 模型 Provider 挂 | 按降级链切换；全部失败则 SSE ERROR/DONE 收口 | 与业务服务解耦，不拖垮业务线程池 |
| RAG 装载失败 | 记录告警，Agent 退化为无知识库模式 | 业务与对话主链路仍可用 |

---

## 6. 部署形态

### 6.1 本机（已验证）

```
scripts/env-start.sh     MySQL 3307 + Redis 6380（项目 data/ 内）
scripts/start.sh         dorm 8081 / agent 8082 / ui 8080（setsid 脱离会话）
scripts/stop.sh          停三服务
```

- UI 用 `ui-service/server.mjs`（Node 标准库，零依赖）；
- 反向代理与 SSE 透传在同一进程内完成，不需要额外 Nginx。

### 6.2 容器（配置已提供）

```
deploy/docker-compose.yml
  mysql:8.4 + redis:8-alpine + 三个服务镜像
  ui 用 Nginx（ui-service/Dockerfile + deploy/nginx.conf）
```

本机未安装 Docker，因此该路径未实测；配置按容器 DNS（`mysql`、`redis`、
`dorm-service`、`agent-service`）编写。

### 6.3 水平扩展

- dorm-service 无状态，可多副本（会话在 Redis，JWT 无状态）；
- agent-service 无状态，SSE 缓冲在 Redis，可多副本且**无需粘性会话**；
- ui-service 纯静态，可多副本；
- MySQL 与 Redis 是共享基础设施，需独立做高可用。

---

## 7. 可观测性

- `X-Trace-Id` 贯穿 UI → 两个服务 → 工具 HTTP 调用 → SSE 事件；
- `audit_log`：登录、业务变更、越权尝试、Agent 轮次；
- `agent_tool_call`：工具名、参数、结果、耗时、权限校验、去重；
- `agent_usage_daily`：按用户/天累加 token 与轮次（原子累加，不丢增量）；
- `/api/health`、`/actuator/health`、`/api/agent/admin/models`（熔断状态）。

---

## 8. 明确不做的事（本阶段）

- 不引入注册中心/配置中心：服务地址用环境变量，规模可控；
- 不引入 Kafka：Agent 事件是“单条流的实时 + 历史”，Redis Stream 足够；
- 不做跨服务分布式事务：业务写与 Agent 消息本来就不要求强一致；
- 不把 UI 做成 BFF：当前 UI 只做静态托管与反代，避免多一层业务逻辑。

这些都可以在需要时增量引入，而不破坏当前边界。
