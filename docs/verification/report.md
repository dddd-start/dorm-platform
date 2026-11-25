# 验收报告（dorm-platform）

> 生成时间 2026-11-25 17:57 · 数据来源：`*/target/surefire-reports/TEST-*.xml`（Maven Surefire 原始输出）

> 本文件由 `bash scripts/make-verification.sh` 生成，重跑即可复现同一组数字。


## 一、结论

| 验收项 | 结果 | 复跑命令 |
|---|---|---|
| 后端单元测试 | **138 / 138 通过**（失败 0、错误 0、跳过 0） | `mvn test` |
| 冒烟测试 | **12 / 12 通过** | `bash scripts/smoke.sh` |
| 全流程回归 | **27 / 27 通过** | `bash scripts/regression.sh` |
| GitHub Actions | 最近一次 CI **success** | 见 Repo 的 Actions 标签 |

> 单测不依赖 MySQL / Redis / 模型 Key，`mvn test` 在任意机器上结果一致。


## 二、单元测试分布

| 模块 | 测试类 | 用例数 | 耗时 |
|---|---|---|---|
| agent-service | 14 | 91 | 5.09s |
| dorm-contract | 4 | 47 | 0.58s |
| **合计** | **18** | **138** | **5.67s** |

## 三、逐类明细

| 模块 | 测试类 | 用例 | 耗时 | 失败 |
|---|---|---|---|---|
| agent-service | `AgentTurnContextTest` | 9 | 0.03s | 0 |
| agent-service | `AgentTurnServiceAdmissionTest` | 1 | 3.01s | 0 |
| agent-service | `ContentGuardrailTest` | 9 | 0.02s | 0 |
| agent-service | `DormChatMemoryStoreTest` | 1 | 0.10s | 0 |
| agent-service | `GuardedToolProviderCancelTest` | 2 | 0.01s | 0 |
| agent-service | `GuardedToolProviderPolicyTest` | 9 | 0.14s | 0 |
| agent-service | `KnowledgeChunkingInvariantTest` | 6 | 0.15s | 0 |
| agent-service | `MarkdownChunkerTest` | 6 | 0.04s | 0 |
| agent-service | `PromptInjectionDetectorTest` | 23 | 0.15s | 0 |
| agent-service | `SseChannelContractTest` | 10 | 0.04s | 0 |
| agent-service | `StreamingChatModelCallbackThreadTest` | 1 | 1.32s | 0 |
| agent-service | `ToolCatalogTest` | 5 | 0.02s | 0 |
| agent-service | `VectorCodecTest` | 7 | 0.04s | 0 |
| agent-service | `VirtualThreadJdkHttpClientTest` | 2 | 0.03s | 0 |
| dorm-contract | `ApplyStateMachineTest` | 25 | 0.20s | 0 |
| dorm-contract | `CryptoUtilTest` | 7 | 0.12s | 0 |
| dorm-contract | `DateUtilTest` | 7 | 0.21s | 0 |
| dorm-contract | `SensitiveMaskerTest` | 8 | 0.04s | 0 |

## 四、口径说明

- **138** = `dorm-contract` 47 + `agent-service` 91，与 README 声明一致；
- **12 冒烟** = 健康检查 4 + 登录 1 + 业务 API 3 + Agent 4；
- **27 回归** = 真实建单 → 审批分床 → 入住 → 退房的全流程（需要 MySQL/Redis，**不进 CI**）；
- 27 项回归不进 GitHub Actions 的原因见 README「已知限制」第 1 条。
