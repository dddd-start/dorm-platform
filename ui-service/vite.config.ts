import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// UI 现在是独立服务：
//  - 开发期：Vite 直接代理到 dorm-service / agent-service；
//  - 构建产物：输出到 ui-service/dist，由静态服务器（Node 或 Nginx）托管。
const DORM_TARGET = process.env.VITE_DORM_TARGET ?? 'http://127.0.0.1:8081'
const AGENT_TARGET = process.env.VITE_AGENT_TARGET ?? 'http://127.0.0.1:8082'

export default defineConfig({
  plugins: [vue()],
  base: '/',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    host: true,
    proxy: {
      // 顺序重要：/api/agent 必须先命中，否则会被 /api 的兜底规则截走
      '/api/agent': {
        target: AGENT_TARGET,
        changeOrigin: true,
      },
      '/api': {
        target: DORM_TARGET,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500,
  },
})
