# dorm-service

宿舍业务服务：区域、用户、公寓、房间、床位、入住申请、订单、统计、审计。

## 边界

- 独占 `dorm_db`，是业务数据的唯一写入方；
- 对外 `/api/**`（浏览器，JWT）；对内 `/internal/**`（agent-service，内部令牌 + 用户 JWT）；
- 不含模型、Prompt、SSE、知识库代码。

## 构建与启动

```bash
# 仓库根目录
mvn -pl dorm-service -am -DskipTests package
java -jar dorm-service/target/dorm-service.jar
```

或使用统一脚本：`bash scripts/start.sh dorm`。

## 环境变量

| 变量 | 说明 |
|---|---|
| `DORM_PORT` | 端口，默认 8081 |
| `DORM_DB_URL` / `DORM_DB_USER` / `DORM_DB_PASSWORD` | 业务库 |
| `DORM_REDIS_HOST/PORT/PASSWORD` | 会话白名单、分布式锁、业务缓存 |
| `DORM_JWT_SECRET` | 与 agent-service 一致 |
| `DORM_INTERNAL_TOKEN` | 与 agent-service 一致 |
| `DORM_PHONE_AES_KEY` / `DORM_PHONE_HMAC_SECRET` | 手机号加密与检索摘要 |

## 内部接口（供 Agent）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/internal/health` | 健康检查（**需服务令牌**，2026-11 起不再免鉴权） |
| GET | `/internal/catalog/regions/options` | 启动期区域目录（只需服务令牌） |
| GET | `/internal/agent/regions/options` | 当前用户可见区域 |
| GET | `/internal/agent/regions/{id}/beds/summary` | 区域床位汇总 |
| GET | `/internal/agent/buildings/options` | 公寓下拉 |
| GET | `/internal/agent/buildings/map?ids=` | 公寓详情映射 |
| GET | `/internal/agent/rooms/availability` | 房源可用性检索 |
| GET | `/internal/agent/rooms/{id}` / `/beds` | 房间与床位明细 |
| GET | `/internal/agent/rooms/statistics` | 区域房源统计 |
| GET | `/internal/agent/applies` | 申请分页（数据权限生效） |
| GET | `/internal/agent/applies/{id}` | 申请详情 |
| GET | `/internal/agent/applies/upcoming` | 我的行程 |
| POST | `/internal/agent/applies` | 创建申请（source=AGENT） |
| POST | `/internal/agent/applies/{id}/cancel` | 取消申请 |
| POST | `/internal/agent/applies/check-in` / `check-out` | 入住/退房 |
| GET | `/internal/agent/orders/mine` / `overdue` | 我的订单/逾期订单 |
| GET | `/internal/agent/stats/overview` / `occupancy` | 统计 |
| GET | `/internal/agent/profile` | 当前用户 |
| POST | `/internal/agent/audit` | Agent 轮次审计落库 |

所有 `/internal/agent/**` 都要求：

```
X-Internal-Token: <DORM_INTERNAL_TOKEN>
Authorization: Bearer <用户 JWT>
```

身份只从 JWT 解析，调用方无法用请求参数指定 userId。
