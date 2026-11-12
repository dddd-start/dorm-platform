# 配置说明

- `config/.env`：本机运行凭据，由 `scripts/env-install.sh` 生成（权限 600，已 gitignore）。
- `config/.env.example`：变量清单与默认值，新增变量时同步更新。

两个后端也支持 Spring profile 覆盖：

- `dorm-service/src/main/resources/application-local.yml.example`
- `agent-service/src/main/resources/application-local.yml.example`

复制为同目录的 `application-local.yml`，启动时加 `--spring.profiles.active=local`。
但**推荐统一用环境变量**（`config/.env`），这样本机脚本与容器部署保持同一套配置口径。
