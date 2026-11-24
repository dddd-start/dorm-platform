# 演示动图

两段 GIF 都不是剪辑拼接，是 **Playwright 驱动真实 Chromium 跑在本机三服务上逐帧截图**，
再用 Pillow 合成（具体见 `scripts/capture-screenshots.mjs` 的同类做法）。

| 文件 | 时长 | 内容 |
|---|---|---|
| `agent-stream.gif` | ~13s / 12 帧 | AI 助手回答「上海员工去新加坡出差，怎么申请宿舍？」：思考过程 → 工具调用（`searchPolicies` / `listRegions`，含真实入参与返回）→ 逐字生成的流式回答，带制度条款溯源 |
| `demo-walkthrough.gif` | ~11s / 19 帧 | 登录 → 统计看板 → 房源查询 → 审批页 → AI 助手，验证三服务联调与区域数据权限 |

## 重新录制

```bash
bash scripts/env-start.sh     # MySQL + Redis
bash scripts/start.sh          # 三服务
# 然后用 Playwright 按上面路线逐帧截图，Pillow 合成：
#   python3 -c "from PIL import Image; ..."   # 见 git 历史里的录制脚本
```

> 录制时用的是真实的 `deepseek-v4.1-flash` 模型与 RAG 知识库，没有走 mock；
> 无 Key 时助手会降级成离线 mock（README「已知限制」有说明），画面会不一样。
