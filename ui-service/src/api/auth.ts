import { api } from './http'
import type { LoginRequest, LoginVO, PasswordUpdateRequest, ProfileUpdateRequest, UserVO } from './types'

export const authApi = {
  login: (data: LoginRequest) => api.post<LoginVO>('/auth/login', data),
  logout: () => api.post<void>('/auth/logout'),
  /** 当前登录人信息，phoneMask 为脱敏值 */
  me: () => api.get<UserVO>('/auth/me'),
  /** 个人信息页用，phoneMask 字段内是明文手机号（仅本人可见） */
  myProfile: () => api.get<UserVO>('/auth/me/profile'),
  updateProfile: (data: ProfileUpdateRequest) => api.put<void>('/auth/profile', data),
  updatePassword: (data: PasswordUpdateRequest) => api.put<void>('/auth/password', data),
}
