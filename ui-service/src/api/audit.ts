import { api, cleanParams } from './http'
import type { AuditLog, AuditQuery, PageResult } from './types'

export const auditApi = {
  page: (params: AuditQuery) => api.get<PageResult<AuditLog>>('/audits', cleanParams({ ...params })),
  /** 按 traceId 拉取整条调用链路 */
  trace: (traceId: string) => api.get<AuditLog[]>(`/audits/trace/${traceId}`),
}
