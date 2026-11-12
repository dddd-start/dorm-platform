import { api, cleanParams } from './http'
import type { BuildingOption, BuildingQuery, BuildingSaveRequest, BuildingVO, PageResult } from './types'

export const buildingApi = {
  page: (params: BuildingQuery) => api.get<PageResult<BuildingVO>>('/buildings', cleanParams({ ...params })),
  options: (regionId?: number) => api.get<BuildingOption[]>('/buildings/options', cleanParams({ regionId })),
  detail: (id: number) => api.get<BuildingVO>(`/buildings/${id}`),
  save: (data: BuildingSaveRequest) => api.post<BuildingVO>('/buildings', data),
  updateStatus: (id: number, status: 0 | 1) =>
    api.put<void>(`/buildings/${id}/status`, undefined, { params: { status } }),
}
