import axios, { type AxiosError, type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { parseJsonSafe } from '@/utils/json'
import type { ApiResult } from './types'

/**
 * 统一 HTTP 封装。
 *
 * 关键点：
 * 1. 请求拦截器自动附加 `Authorization: Bearer <token>`（token 从 localStorage 读取，
 *    避免与 Pinia store 形成循环依赖）。
 * 2. 响应拦截器统一判断业务码：`code !== 0` 时用后端返回的 `message` 弹错，并 reject。
 * 3. 401 时清理登录态并跳转登录页（通过可注册的钩子，避免循环 import）。
 * 4. transformResponse 使用 `parseJsonSafe` 做「大整数还原」——后端雪花主键有 19 位，
 *    超出 JS 安全整数范围，直接 JSON.parse 会丢精度导致回传后端 id 错误。
 */

export const TOKEN_KEY = 'dorm_token'

/** 401 处理钩子，由 main.ts / router 注入，默认整页跳转登录 */
let unauthorizedHandler: (() => void) | null = null
export function setUnauthorizedHandler(fn: () => void): void {
  unauthorizedHandler = fn
}

// 大整数还原逻辑见 @/utils/json（纯函数，已单独验证）
export { parseJsonSafe }

export const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' },
  transformResponse: [
    (data: unknown) => {
      if (typeof data === 'string') return parseJsonSafe(data)
      return data
    },
  ],
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers = config.headers ?? {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown> | undefined
    // 非包装响应（例如静态资源）直接放行
    if (!body || typeof body !== 'object' || typeof body.code !== 'number') {
      return response
    }
    if (body.code !== 0) {
      const msg = body.message || '请求失败'
      // 401 也可能以业务码形式返回
      if (body.code === 401) {
        handleUnauthorized()
      } else {
        ElMessage.error(msg)
      }
      return Promise.reject(new ApiError(msg, body.code, body.traceId))
    }
    return response
  },
  (error: AxiosError) => {
    const status = error.response?.status
    const body = error.response?.data as ApiResult<unknown> | undefined
    const msg = body?.message || error.message || '网络异常'

    if (status === 401) {
      handleUnauthorized()
    } else if (status === 403) {
      ElMessage.error(body?.message || '没有权限执行该操作')
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请稍后重试')
    } else {
      ElMessage.error(msg)
    }
    return Promise.reject(new ApiError(msg, status ?? -1, body?.traceId))
  },
)

function handleUnauthorized(): void {
  localStorage.removeItem(TOKEN_KEY)
  ElMessage.error('登录已过期，请重新登录')
  if (unauthorizedHandler) unauthorizedHandler()
  else window.location.href = '/login'
}

/** 业务异常，携带后端 traceId 便于排查 */
export class ApiError extends Error {
  code: number
  traceId?: string
  constructor(message: string, code: number, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

/**
 * 把任意异常转成「三态组件」需要的错误描述。
 *
 * 为什么单独抽出来：页面里 `catch {}` 一吞了事，是"空态/错误态不分"的根源。
 * 统一转成 { message, traceId } 后，页面只需要把它塞给 AsyncState 的 error/traceId，
 * 错误态就必然带上可读文案与追踪号（用户报障时能直接给号，而不是"我这边报错了"）。
 */
export function toLoadError(err: unknown): { message: string; traceId: string } {
  if (err instanceof ApiError) {
    return {
      message: err.code === -1 ? '网络异常或服务不可用，请检查网络后重试。' : err.message,
      traceId: err.traceId ?? '',
    }
  }
  if (err instanceof Error) return { message: err.message, traceId: '' }
  return { message: '加载失败，请稍后重试。', traceId: '' }
}

/** 发起请求并解包到 data 字段 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const res = await http.request<ApiResult<T>>(config)
  return res.data.data
}

export const api = {
  get: <T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'GET', url, params }),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'POST', url, data }),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'PUT', url, data }),
  delete: <T>(url: string, config?: AxiosRequestConfig) =>
    request<T>({ ...config, method: 'DELETE', url }),
}

/** 把对象里的 undefined / 空字符串剔除，避免后端把空串当条件 */
export function cleanParams<T extends Record<string, unknown>>(params: T): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue
    out[k] = v
  }
  return out
}
