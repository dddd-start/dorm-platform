#!/usr/bin/env node
/**
 * ui-service 的本地静态服务器 + 反向代理。
 *
 * 生产可用 Nginx（见 deploy/nginx.conf）；本机没有 Nginx 时用这个脚本，
 * 同样能做到：
 *   1. 托管 dist 静态资源，history 路由回退 index.html；
 *   2. /api/agent 转发到 agent-service（SSE 不缓冲）；
 *   3. 其余 /api 转发到 dorm-service；
 *   4. 禁止外部访问 /internal（服务间接口）。
 *
 * 全流程零第三方依赖，只用 Node 标准库 —— 免得为了一个静态服务器
 * 再引入一套会过期的依赖。
 */
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const DIST = path.resolve(__dirname, 'dist')
const PORT = Number(process.env.UI_PORT || 8080)
const HOST = process.env.UI_HOST || '0.0.0.0'
const DORM = new URL(process.env.DORM_SERVICE_URL || 'http://127.0.0.1:8081')
const AGENT = new URL(process.env.AGENT_SERVICE_URL || 'http://127.0.0.1:8082')

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json; charset=utf-8',
}

function log(...args) {
  console.log(new Date().toISOString(), '[ui]', ...args)
}

function targetFor(url) {
  if (url.startsWith('/api/agent')) return AGENT
  if (url.startsWith('/api/')) return DORM
  return null
}

function proxy(req, res, target, url) {
  const headers = { ...req.headers, host: target.host }
  const upstream = http.request(
    {
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port || 80,
      method: req.method,
      path: url,
      headers,
      timeout: 180_000,
    },
    (upstreamRes) => {
      res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers)
      // SSE 必须逐块透传，不能攒包
      upstreamRes.pipe(res)
    },
  )
  upstream.on('timeout', () => {
    upstream.destroy(new Error('upstream timeout'))
  })
  upstream.on('error', (err) => {
    log('proxy error', url, err.message)
    if (!res.headersSent) {
      res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' })
    }
    res.end(JSON.stringify({ code: 9999, message: '上游服务暂时不可用：' + err.message }))
  })
  req.pipe(upstream)
}

function serveStatic(req, res, urlPath) {
  // 不允许路径穿越；非法编码（例如 %E0%A4%A）直接 400，不能让异常打挂进程
  let decoded
  try {
    decoded = decodeURIComponent(urlPath)
  } catch {
    res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' })
    res.end('bad request')
    return
  }
  const safePath = path.normalize(decoded).replace(/^(\.\.[/\\])+/, '')
  let file = path.join(DIST, safePath)
  if (!file.startsWith(DIST)) {
    res.writeHead(403)
    res.end('forbidden')
    return
  }

  const exists = fs.existsSync(file) && fs.statSync(file).isFile()
  if (!exists) {
    // 带扩展名 = 真实资源缺失，保持 404；否则交给 SPA history 路由
    if (path.extname(safePath)) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
      res.end('not found')
      return
    }
    file = path.join(DIST, 'index.html')
    if (!fs.existsSync(file)) {
      res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8' })
      res.end('UI 尚未构建：请先在 ui-service 执行 npm run build')
      return
    }
  }

  const stat = fs.statSync(file)
  const ext = path.extname(file).toLowerCase()
  const headers = {
    'Content-Type': MIME[ext] || 'application/octet-stream',
    'Content-Length': stat.size,
  }
  // 带 hash 的 assets 可以长缓存；index.html 必须每次校验
  if (file.includes(`${path.sep}assets${path.sep}`)) {
    headers['Cache-Control'] = 'public, max-age=31536000, immutable'
  } else {
    headers['Cache-Control'] = 'no-cache'
  }
  res.writeHead(200, headers)
  fs.createReadStream(file).pipe(res)
}

const server = http.createServer((req, res) => {
  const url = req.url || '/'

  if (url === '/healthz' || url === '/ui/health') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ service: 'ui-service', status: 'UP' }))
    return
  }

  if (url.startsWith('/internal')) {
    res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ code: 1004, message: 'not found' }))
    return
  }

  const target = targetFor(url)
  if (target) {
    proxy(req, res, target, url)
    return
  }

  serveStatic(req, res, url.split('?')[0])
})

server.listen(PORT, HOST, () => {
  log(`ui-service listening on http://${HOST}:${PORT}`)
  log(`  /api/agent -> ${AGENT.origin}`)
  log(`  /api       -> ${DORM.origin}`)
  log(`  static     -> ${DIST}`)
})
