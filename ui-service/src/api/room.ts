import { api, cleanParams } from './http'
import type {
  BedBatchStatusRequest,
  PageResult,
  RoomAvailabilityQuery,
  RoomAvailabilityRow,
  RoomBatchStatusRequest,
  RoomBedDetail,
  RoomQuery,
  RoomSaveRequest,
  RoomVO,
} from './types'

export const roomApi = {
  page: (params: RoomQuery) => api.get<PageResult<RoomVO>>('/rooms', cleanParams({ ...params })),
  /** 可订房间查询，返回带 availableBeds / estimatedAmount 的扁平行 */
  availability: (params: RoomAvailabilityQuery) =>
    api.get<RoomAvailabilityRow[]>('/rooms/availability', cleanParams({ ...params })),
  detail: (id: number) => api.get<RoomVO>(`/rooms/${id}`),
  /** 床位占用明细（含占用起止日期） */
  beds: (id: number) => api.get<RoomBedDetail[]>(`/rooms/${id}/beds`),
  save: (data: RoomSaveRequest) => api.post<RoomVO>('/rooms', data),
  batchStatus: (data: RoomBatchStatusRequest) => api.put<import('./types').BatchResult>('/rooms/status', data),
  batchBedStatus: (data: BedBatchStatusRequest) => api.put<void>('/rooms/beds/status', data),
}
