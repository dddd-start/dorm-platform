import { api, cleanParams } from './http'
import type { PageResult, UserCreateRequest, UserQuery, UserVO } from './types'

export const userApi = {
  page: (params: UserQuery) => api.get<PageResult<UserVO>>('/users', cleanParams({ ...params })),
  detail: (id: number) => api.get<UserVO>(`/users/${id}`),
  options: (params?: { keyword?: string; role?: string }) =>
    api.get<UserVO[]>('/users/options', cleanParams({ ...(params ?? {}) })),
  /** 新增 / 编辑（带 id 即编辑；password 留空表示不改密码） */
  save: (data: UserCreateRequest) => api.post<UserVO>('/users', data),
  updateStatus: (id: number, status: 0 | 1) =>
    api.put<void>(`/users/${id}/status`, undefined, { params: { status } }),
  /** 重置密码：password 作为 query 参数传递 */
  resetPassword: (id: number, password: string) =>
    api.put<void>(`/users/${id}/password`, undefined, { params: { password } }),
}
