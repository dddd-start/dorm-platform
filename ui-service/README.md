# ui-service

Vue 3 + Vite 前端，作为独立静态服务运行，并反向代理两个后端。

## 开发

```bash
npm ci
npm run dev          # http://127.0.0.1:5173
```

开发代理：

- `/api/agent` → `http://127.0.0.1:8082`
- `/api` → `http://127.0.0.1:8081`

可用 `VITE_AGENT_TARGET` / `VITE_DORM_TARGET` 覆盖。

## 构建与运行

```bash
npm run build        # 产物：dist/
node server.mjs      # http://127.0.0.1:8080
```

`server.mjs` 是零第三方依赖的静态服务器 + 反向代理：

- 托管 `dist/`，history 路由回退 `index.html`；
- 带扩展名的缺失资源返回 404（不会把 HTML 当 JS 返回）；
- SSE 直接 pipe 上游响应，不缓冲；
- `/internal/*` 一律 404，避免服务间接口暴露。

环境变量：`UI_PORT`（默认 8080）、`DORM_SERVICE_URL`、`AGENT_SERVICE_URL`。

## Nginx / Docker

- 配置：`deploy/nginx.conf`
- 镜像：`Dockerfile`（多阶段构建 + Nginx）
- Compose：`../deploy/docker-compose.yml`

Nginx 版本同样关闭了 `/api/agent/` 的 `proxy_buffering` 并拉长读超时。
