# 老项目《注意事项》37 条在新项目的落地状态

> 老项目根目录的 `注意事项.md` 列了 **37 个生产化设计问题**。
> 老项目自己在 `docs/notes-mapping.md` 里做过一次独立调用方审计，结论不是“全绿”：
> **27 条名实相符、7 条部分实现、2 条假实现、1 条明确不做**。
> 新项目是功能对等拆分，不是重新实现，因此**大部分状态被原样继承**。
> 本文只列“有变化/没解决”的部分，避免把 37 条再抄一遍。

## 1. 总账

| 状态 | 条目 | 新项目结论 |
|---|---|---|
| ✅ 保持 | 1–10、12、14、15、17–20、22–27、32、34、36、37（27 条） | 代码保留；其中 1/6/7/15/34 因拆分**增强**，3/12/24/32 **缺少新环境复测** |
| ⚠️ 仍部分 | 11、16、21、28、29、33、35（7 条） | 与老项目相同的边界，未补齐 |
| ❌ 仍假实现 | 30、31（2 条） | `agent_task` 表/Mapper 在，但无生产者/工作者/端点 |
| ❌ 明确不做 | 13（1 条） | 不提供代码执行工具，理由不变 |

## 2. 仍未解决的关键项

### 30/31 长尾任务 / >10s 异步化 —— 仍然是假实现

新仓库实测：

```text
agent_service.agent_task 行数 = 0
AgentTaskMapper 全仓库调用方 = 0
/api/agent/tasks 端点 = 不存在
```

`@Bean("agentTaskExecutor")` 只被 `AgentTurnService` 用来异步生成会话摘要，
**不是长任务工作者**。所以“提交月报任务→拿 taskNo→轮询”仍然不可用，
超长任务只能同步跑到超时。这一点与老项目一致，没有因为拆分变好。

### 16/28 人工干预 / 运维 Agent —— 待办表仍是死脚手架

```text
agent_pending_approval 行数 = 0
AgentPendingApprovalMapper 调用方 = 0
HUMAN_REQUIRED 生产发送点 = 0（只在 Controller 文档字符串/枚举/前端分支里）
```

回放、打断真实可用；但“需要管理员确认后继续”没有闭环。
运维 Agent 的 4 个只读工具与权限边界真实，月报/批量申请队列不存在。

### 21 成本控制 —— 仍有一半指标不可信

- `agent_usage_daily.est_cost` 恒 `0.000000`；
- `cache_hit_count` 恒 0；
- 预算 DEGRADE 只降级、不切模型；
- **RAG 命中数本轮已修**：老项目 M3 的 `rag_hit_count` 恒 0 问题被继承，
  现已改为由 `PolicyTools` 把真实检索命中写入 `AgentTurnContext`，
  SSE `ragHits` 与 `agent_usage_daily.rag_hit_count` 同步。

### 33 Outbox —— 业务侧保留，跨服务反而多了一个洞

- `dorm-service` 的 `OutboxService` 仍在（退避、死信、无处理器不标成功）；
- 老项目已确认的 **F4** 仍在：`dispatch()` 没有 `FOR UPDATE SKIP LOCKED`/`locked_by`/lease，
  多实例会重复投递，失败方可能把 `SENT` 覆写回 `FAILED`；
- 新拆分后，Agent 写 `agent_db` 与通过 HTTP 写业务库是**两个独立事务**，
  没有跨服务 Outbox/Saga。Agent 创建申请成功、但本地消息/工具审计写失败时，
  两边不会自动补偿。这是拆分引入的新一致性缺口，不是老项目原有能力。

### 35 缓存 —— 仍是“两级”而不是文档说的“四级”

嵌入缓存与检索缓存真实存在；语义问答缓存只删不写，房源可用性缓存没有常量/写入点。
新项目没有改变这一点。

### 11 优先级调度 —— 令牌桶真实，任务优先级没有消费者

并发限流/隔离是真实的；`agent_task.priority` 没有被任何工作者读取。

## 3. 拆分带来的增强

| 条目 | 新项目的变化 |
|---|---|
| 1/6 断点续传 | 流缓冲在共享 Redis，**跨实例续传**成立，不再依赖单机内存 |
| 7 全链路追踪 | `DormServiceClient` 透传 `X-Trace-Id`；实测 SSE 的 trace 与 `audit_log.trace_id` 一致 |
| 15 身份传递 | 用户 JWT + `X-Internal-Token` 双校验，dorm-service 重新解析身份；比“Agent 自己声明 userId”更强 |
| 23 审计 | 审计改异步 HTTP，`cost_ms` 与 model/tokens/toolCalls 明细已恢复 |
| 34 高可用 | dorm/agent/ui 无状态，SSE 状态外置 Redis，可独立扩容 |

## 4. 新环境缺少的验证

- **3（1000 SSE 并发）**：代码和虚拟线程/Redis 计数都在，但新仓库没有复跑 1000 并发；
- **12（工具超时/重试/熔断）**：`DormServiceClient` 有 10s 超时，但**没有 HTTP 重试**；
  跨服务失败会作为工具错误返回给模型，重试语义与单体本地调用不同，需重新压测；
- **24（RAG 延迟）**：没有在新环境复测 P50/P95；
- **32（压测脚本）**：老项目的 `ops/perf-test.*`、`concurrency-test.sh` **没有迁入**，
  新仓库目前只有 12 项冒烟与 130 个单测，没有千级并发脚本。

## 5. 本次实测发现的新问题

1. **RAG 指标为 0（已修）**：修复前 `rag_retrieval_log=4` 而
   `agent_usage_daily.rag_hit_count=0`；修复后同一类问题 SSE `ragHits=7`、
   库里 `rag_hit_count=7`。
2. **审计终态与消息终态不一致（已修）**：模型触发“超过 3 次工具往返”时，
   原实现 SSE 发 `DONE + COMPLETED + graceful=true`，`audit_log` 却写
   `AGENT_TURN_FAILED`。现已统一为 `AGENT_TURN_DEGRADED`（result=SUCCESS），
   并带 `status/graceful/toolCalls/ragHits/partialLength` 明细。
3. **残留 STREAMING 无回收（继承 M7）**：新代码里没有任何启动/定时补偿；
   进程在生成中被杀，消息会永久停在 STREAMING，同一 turnId 可能一直“请稍候”。
   当前库中为 0 行，属于“未触发”，不是“已修”。

## 6. 一句话结论

老项目的 37 条不是 37 个已解决问题：**27 条真实、7 条部分、2 条假实现、1 条不做**。
新项目**继承了这 27 条，也继承了 7+2 条缺口**；拆分额外改善了身份、追踪、跨实例续传与高可用，
但新增了“Agent 跨服务写没有 Outbox/Saga”的一致性缺口，并且没有把老项目的千级压测脚本带过来。
本轮额外修掉了 RAG 命中指标，其余缺口仍按上表存在。
