# 设计决策与踩坑记录

这个目录只记两件事：**为什么这么设计**、**开发中真的踩过哪些坑**。
目的不是自述，而是让读代码的人能判断边界与代价。

| 文件 | 内容 | 适合回答 |
|---|---|---|
| [architecture-decisions.md](architecture-decisions.md) | 11 条关键决策：背景 → 备选方案 → 选择理由 → 代价/风险 → 其它方案的排除理由 | 「为什么拆三个服务」「为什么 Agent 不直连业务库」「SSE 续传为什么放 Redis」 |
| [pitfalls.md](pitfalls.md) | 17 个真实踩坑：现象 → 定位 → 修复 → 可复用排查法 | 「你遇到过什么难题」「怎么定位的」 |

## 最值得先看的 5 条

1. **边界按"数据所有权"划，不按"部署单元"划**：`dorm-service` 是唯一写入方，`agent-service` 没有业务 Mapper，
   身份靠透传用户 JWT 重新校验（[ADR-1](architecture-decisions.md)）。
2. **SSE 先写 Redis Stream 再 Pub/Sub**：投递失败不影响续传，`Last-Event-ID` 续传不需要粘性会话（[ADR-3](architecture-decisions.md)）。
3. **模型回调跑在专用虚拟线程上**：不占请求线程，也不受 `ForkJoinPool.commonPool` 并发上限支配（[ADR-5](architecture-decisions.md)）。
4. **JOIN 之后的聚合要问"这一行到底是几行"**：`SUM` 数房间被床位数放大，`openRoomCount` 一度大于房间总数（[P15](pitfalls.md)）。
5. **同一事实只允许一个权威来源**：seq、状态机、`ToolCatalog`、`DataScope`、`RedisKeys` 都按这个原则收口（[pitfalls 附](pitfalls.md)）。

## 怎么用

不需要背全文；记住每条决策的「代价」一栏——能不能说出代价，是判断"真做过"还是"看过文章"的分界线。
