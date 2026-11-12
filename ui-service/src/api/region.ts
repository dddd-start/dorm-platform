import { api, cleanParams } from './http'
import type { RegionOption, RegionSaveRequest, RegionVO } from './types'

export const regionApi = {
  list: (params?: { status?: number; withStats?: boolean }) =>
    api.get<RegionVO[]>('/regions', cleanParams({ ...params })),
  options: () => api.get<RegionOption[]>('/regions/options'),
  save: (data: RegionSaveRequest) => api.post<RegionVO>('/regions', data),
  updateStatus: (id: number, status: 0 | 1) =>
    api.put<void>(`/regions/${id}/status`, undefined, { params: { status } }),
}
