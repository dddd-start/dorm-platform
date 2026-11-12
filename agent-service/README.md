# agent-service

AI Agent 服务：LangChain4j 装配、意图路由、工具编排、RAG、记忆、护栏、SSE 与断点续传。

## 边界

- 独占 `agent_db`（会话/消息/归档/工具审计/用量/长期记忆/RAG）；
- **不连接业务库**，所有业务数据通过 `DormServiceClient` 调 dorm-service；
- 与 dorm-service 共享 JWT 密钥与 Redis 会话白名单，用户登出/禁用立即生效；
- 知识库 Markdown 放在 `src/main/resources/knowledge/`。

## 构建与启动

```bash
mvn -pl agent-service -am -DskipTests package
java -jar agent-service/target/agent-service.jar
```

或：`bash scripts/start.sh agent`。

## 环境变量

| 变量 | 说明 |
|---|---|
| `DORM_AGENT_PORT` | 端口，默认 8082 |
| `DORM_AGENT_DB_URL` / `DORM_AGENT_DB_USER` / `DORM_AGENT_DB_PASSWORD` | Agent 库 |
| `DORM_REDIS_HOST/PORT/PASSWORD` | 流缓冲、限流、会话校验 |
| `DORM_JWT_SECRET` | 与 dorm-service 一致 |
| `DORM_INTERNAL_TOKEN` | 与 dorm-service 一致 |
| `DORM_SERVICE_URL` | 业务服务地址，默认 `http://127.0.0.1:8081` |
| `DORM_AI_BASE_URL` / `DORM_AI_API_KEY` / `DORM_AI_MODEL_PRIMARY` / `DORM_AI_MODEL_FAST` | 模型 |
| `DORM_AGENT_MOCK=true` | 离线模拟模型 |

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查 |
| POST | `/api/agent/conversations` | 新建会话 |
| GET | `/api/agent/conversations` | 会话列表 |
| GET | `/api/agent/conversations/{id}/messages` | 消息历史 |
| DELETE | `/api/agent/conversations/{id}` | 归档会话 |
| POST | `/api/agent/chat/stream` | SSE 对话 |
| GET | `/api/agent/chat/stream/{messageId}?lastEventId=` | 断点续传 |
| POST | `/api/agent/chat/{messageId}/stop` | 打断 |
| POST | `/api/agent/messages/{messageId}/feedback` | 反馈 |
| GET/DELETE | `/api/agent/memories` | 查看/清除记忆 |
| GET | `/api/agent/meta` | 能力说明 |
| GET | `/api/agent/replay/{conversationId}` | 回放 |
| GET | `/api/agent/admin/**` | 模型健康、用量、知识库、工具（管理员） |

## 工具调用链

```
LangChain4j → GuardedToolProvider → *Tools → DormServiceClient
                                                   │
              Authorization: Bearer <用户JWT>       │
              X-Internal-Token: <服务令牌>          ▼
                                          dorm-service /internal/agent/**
```

`GuardedToolProvider` 仍负责：工具白名单、角色权限、同轮去重、取消检查、熔断与审计。
业务规则（床位、状态机、数据权限）只在 dorm-service 执行一次。
