import { api, cleanParams } from './http'
import type {
  ApplyApproveRequest,
  ApplyCancelRequest,
  ApplyCheckInRequest,
  ApplyCheckOutRequest,
  ApplyCreateRequest,
  ApplyQuery,
  ApplyReassignRequest,
  ApplyRejectRequest,
  ApplyStatistics,
  ApplyVO,
  PageResult,
} from './types'

export const applyApi = {
  create: (data: ApplyCreateRequest) => api.post<ApplyVO>('/applies', data),
  page: (params: ApplyQuery) => api.get<PageResult<ApplyVO>>('/applies', cleanParams({ ...params })),
  detail: (id: string) => api.get<ApplyVO>(`/applies/${id}`),
  /** 我的即将出行行程 */
  mineUpcoming: () => api.get<ApplyVO[]>('/applies/mine/upcoming'),
  /** 管理员审批 + 分房（原子操作） */
  approve: (data: ApplyApproveRequest) => api.post<ApplyVO>('/applies/approve', data),
  reject: (data: ApplyRejectRequest) => api.post<ApplyVO>('/applies/reject', data),
  cancel: (data: ApplyCancelRequest) => api.post<ApplyVO>('/applies/cancel', data),
  checkIn: (data: ApplyCheckInRequest) => api.post<ApplyVO>('/applies/check-in', data),
  checkOut: (data: ApplyCheckOutRequest) => api.post<ApplyVO>('/applies/check-out', data),
  reassign: (data: ApplyReassignRequest) => api.post<ApplyVO>('/applies/reassign', data),
  statistics: (regionId?: number) => api.get<ApplyStatistics>('/applies/statistics', cleanParams({ regionId })),
}
