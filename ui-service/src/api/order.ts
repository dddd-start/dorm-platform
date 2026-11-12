import { api, cleanParams } from './http'
import type { OrderQuery, OrderVO, PageResult } from './types'

export const orderApi = {
  page: (params: OrderQuery) => api.get<PageResult<OrderVO>>('/orders', cleanParams({ ...params })),
  mine: (params?: OrderQuery) => api.get<PageResult<OrderVO>>('/orders/mine', cleanParams({ ...(params ?? {}) })),
  detail: (id: string) => api.get<OrderVO>(`/orders/${id}`),
  /** 逾期未退房 */
  overdue: (limit = 50) => api.get<OrderVO[]>('/orders/overdue', { limit }),
}
