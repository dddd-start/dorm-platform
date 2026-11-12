# 面试展示截图与演示 GIF

> 统一口径：**1440×900 逻辑像素、deviceScaleFactor=2（2880×1800 物理像素）**，浅色主题，中文界面，
> 采集时等待网络空闲、无加载骨架、无 `el-message` 报错、控制台无 error；同一页面最多重试 3 次取通过校验的一张。
> 全部由 `scripts/capture-screenshots.mjs` 自动采集，可复跑（演示数据走 `/api/**` 正常接口，幂等）。

所有图片都由同一套校验产出：等待网络空闲 + 无加载骨架 + 无错误提示 + 表格/图表有数据 + 控制台无 error，未通过校验的那一次不会落盘（失败样本留在 `.work/capture/rejected-*`）。
GIF 规格：`agent-stream.gif` 1000px 宽、24 帧、11s、1.25MB（循环播放）。

## 清单

| 文件 | 页面 | 想向面试官证明什么 | 本次采集 |
| --- | --- | --- | --- |
| `01-login.png` | /login（未登录） | 登录页：三服务一键启动后的统一入口，含测试账号提示与品牌信息。 | ✅ 已采集（171KB） |
| `02-rooms.png` | /rooms（zhangwei 员工端） | 房源查询：按区域/日期/房型过滤，直接展示房态、可订床位数与预估总价。 | ✅ 已采集（262KB） |
| `03-apply-form.png` | /apply/create（已填好、未提交） | 提交入住申请：四步流程条 + 晚数与预估总价实时计算 + 幂等键防重复提交设计。 | ✅ 已采集（215KB） |
| `04-my-applies.png` | /apply/mine（zhangwei） | 我的申请：同一员工覆盖待审批/已通过/已入住/已退房/已驳回的完整状态流转。 | ✅ 已采集（245KB） |
| `05-admin-approve.png` | /admin/approve（superadmin） | 申请审批：管理员视角的申请池（清掉默认「待审批」过滤，10 行覆盖待审批/已通过/已入住/已退房/已驳回），操作列按状态给出审批分房/驳回/换房动作。 | ✅ 已采集（139KB） |
| `05b-approve-dialog.png` | /admin/approve → 审批分房弹窗 | 审批分房：按申请区域与日期过滤可订房间，右侧实时显示床位占用，选床后一次事务落库。 | ✅ 已采集（296KB） |
| `06-admin-orders.png` | /admin/orders（superadmin） | 订单管理：申请审批入库自动生成订单，含入住人、房间床位、金额币种、实际入住退房与状态。 | ✅ 已采集（308KB） |
| `07-dashboard.png` | /admin/dashboard（superadmin） | 统计看板：入住率仪表盘、申请状态分布、近 14 天趋势、各区域房源分布四张 ECharts 实测渲染。 | ✅ 已采集（111KB） |
| `08-agent-chat.png` | /agent（zhangwei，真实提问） | AI 助手：一屏同时看到「工具调用卡片 + 流式回答 + RAG 引用来源」，回答可点开引用溯源。 | ✅ 已采集（200KB） |
| `08b-agent-tools.png` | /agent（同一轮对话，滚到工具调用区） | 工具调用明细：每个工具的名称、业务入参（区域/城市/房型）与返回结果，证明回答来自真实业务接口而不是模型编造。 | ✅ 已采集（172KB） |
| `08c-agent-refs.png` | /agent（同一轮对话，滚到回答尾部） | 引用溯源：回答尾部的「参考来源」chip 列表 + 耗时/首字耗时/tokens/模型元信息，说明每个结论都能回到知识库原文。 | ✅ 已采集（199KB） |
| `09-agent-ops.png` | /admin/agent-ops → 工具清单（superadmin） | Agent 运营看板：20 个只读工具 + 5 个写工具、读写分离、已装配 Agent（APPLY#EMPLOYEE / POLICY#EMPLOYEE / ROOM#EMPLOYEE）与单轮超时，回答「Agent 到底能做什么、有没有危险动作」。 | ✅ 已采集（291KB） |
| `09b-agent-rag.png` | /admin/agent-ops → RAG 质量 | RAG 质量：无命中率、平均最高相似度、点踩率、平均检索耗时与缺口问题榜——回答「知识库到底够不够用」。 | ✅ 已采集（203KB） |
| `09c-agent-models.png` | /admin/agent-ops → 模型健康状态 | 模型健康：主模型 / 快速 / 两级 fallback 的可用性、成功率、连续失败与熔断剩余，外加限流与助手清单。 | ✅ 已采集（266KB） |
| `10-audits.png` | /admin/audits（superadmin） | 审计日志：谁在什么时候对哪条数据做了什么、结果与耗时，含 traceId 可串起整条调用链。 | ✅ 已采集（132KB） |
| `11-swagger.png` | http://127.0.0.1:8081/swagger-ui.html | 接口文档：业务服务的 OpenAPI，说明接口契约与统一响应包装。 | ✅ 已采集（239KB） |
| `agent-stream.gif` | /agent 提问后的流式过程（约 11s，1000px 宽，1.3MB） | 动态证明：回答是逐字流式输出（SSE）而非一次性返回，同时可看到工具调用与引用逐步出现。 | ✅ 已采集（1281KB） |

## 采集口径与取舍

- **AI 助手的三张图（08 / 08b / 08c）取自同一轮真实对话**：提问后等流式结束（DONE），再刷新页面从历史打开该会话。
  历史里较长的回答会折叠成摘要，这样才能在 900px 高度里同时放下「工具调用卡片 + 回答 + 参考来源」；
  `agent-stream.gif` 用的是同一轮对话的连续截图，能看到正文逐字出现、工具卡片与引用先后出现。
- **申请审批页**默认只看「待审批」，截图前清掉状态过滤并重新查询，一屏 10 行覆盖 5 种状态；
  宽表在 1440 宽下会折叠左侧导航，否则「状态」列会被固定操作列挡住。
- **演示数据**由脚本通过 `/api/**` 造（幂等，按 `tripNo` 判重），不直接改库；申请人只用种子账号，不含真实姓名/手机号。
- **统计看板**四张图在 900px 高度内只能完整露出前两张，下方趋势与区域分布属正常滚动区域。

## 怎么复跑

```bash
# 前置：三个服务已启动（UI 8080 / 业务 8081 / Agent 8082）
node scripts/capture-screenshots.mjs            # 造演示数据 + 截图 + 合成 GIF
node scripts/capture-screenshots.mjs --no-gif   # 只截图（GIF 耗时且会多打一轮模型）
node scripts/capture-screenshots.mjs --skip-prep --only=07-dashboard
```

环境变量（都有默认值，一般不用改）：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `UI_URL` | `http://127.0.0.1:8080` | 前端入口（`/api`、`/api/agent` 由前端同源代理转发） |
| `SWAGGER_URL` | `http://127.0.0.1:8081/swagger-ui.html` | 业务服务接口文档 |
| `PLAYWRIGHT_MODULE` | `playwright` | 用已安装的 Playwright 模块（可用路径覆盖） |
| `PLAYWRIGHT_BROWSERS_PATH` | Playwright 默认缓存 | 未安装浏览器时先 `npx playwright install chromium` |
| `DEMO_ADMIN` / `DEMO_PASSWORD` | `superadmin` / `Dorm@123456` | 造数据与管理员页面用 |

## 演示数据是怎么来的

截图里的数据不是手改库：脚本用种子账号（`zhangwei` / `liuyang` / `chenjing` / `wangfang`，密码 `Dorm@123456`）
调 `POST /api/applies` 提交申请，再用 `superadmin` 调 `POST /api/applies/approve` / `check-in` / `check-out` / `reject`
把 10 条申请推进到状态机的每个状态（待审批 / 已通过 / 已入住 / 已退房 / 已驳回），订单由入住动作自动生成，
审计日志由这些真实写操作自然产生。全程幂等：以 `tripNo=TRIP-DEMO-xxx` 判重，重复执行不会产生重复数据。

## 建议 README 用哪几张

主图 4 张（讲清"是什么、谁在用、AI 怎么落地、怎么运维"）：

1. `07-dashboard.png` —— 一眼看懂业务规模与数据真实性；
2. `04-my-applies.png` + `05b-approve-dialog.png` —— 讲清「申请 → 审批分房 → 入住」的核心链路；
3. `08-agent-chat.png` —— 讲清 AI 助手不是"套壳聊天"，而是有工具调用与引用溯源的业务 Agent；
4. `09-agent-ops.png` + `09b-agent-rag.png` —— 讲清 Agent 上线后的可观测性（工具清单与读写分离、模型熔断、RAG 质量）。

次图：`01-login.png`（首图氛围）、`agent-stream.gif`（流式证据）、`10-audits.png`（合规与排障）、
`11-swagger.png`（接口契约）。`02-rooms.png` / `03-apply-form.png` / `06-admin-orders.png` 作为功能细节补充。

## 采集环境

- UI：`http://127.0.0.1:8080`（Vue3 + Element Plus，浅色主题，history 路由）
- 业务服务：`http://127.0.0.1:8081`（Swagger UI 即有效）
- Agent 服务：`http://127.0.0.1:8082`（SSE 流式对话，经前端 `/api/agent` 代理）
- Playwright：`npm i -D playwright && npx playwright install chromium`（或 `PLAYWRIGHT_MODULE` 指向已有安装）
- GIF 合成：系统 `python3` + Pillow（本机 Playwright 自带 ffmpeg 为裁剪版，不含 gif muxer）

采集时间：2026-11-12 18:57:34（本地时区）
