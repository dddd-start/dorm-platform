import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/auth'
import { TOKEN_KEY } from '@/api/http'
import type { LoginRequest, ProfileUpdateRequest, UserRole, UserVO } from '@/api/types'

/** 登录态与当前用户信息 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  const user = ref<UserVO | null>(null)
  const loaded = ref(false)

  const isLoggedIn = computed(() => token.value.length > 0)
  const role = computed<UserRole | ''>(() => user.value?.role ?? '')
  const isSuperAdmin = computed(() => role.value === 'SUPER_ADMIN')
  const isRegionAdmin = computed(() => role.value === 'REGION_ADMIN')
  /** 区域管理员与总管理员统称管理员端 */
  const isAdmin = computed(() => role.value === 'SUPER_ADMIN' || role.value === 'REGION_ADMIN')
  const displayName = computed(() => user.value?.realName || user.value?.username || '未登录')

  function setToken(value: string): void {
    token.value = value
    if (value) localStorage.setItem(TOKEN_KEY, value)
    else localStorage.removeItem(TOKEN_KEY)
  }

  async function login(payload: LoginRequest): Promise<UserVO> {
    const data = await authApi.login(payload)
    setToken(data.token)
    // 登录返回体字段与 UserVO 不完全一致，这里落一次 /auth/me 拿到完整用户信息
    const me = await authApi.me()
    user.value = me
    loaded.value = true
    return me
  }

  async function fetchMe(force = false): Promise<UserVO | null> {
    if (!token.value) return null
    if (loaded.value && !force && user.value) return user.value
    const me = await authApi.me()
    user.value = me
    loaded.value = true
    return me
  }

  async function updateProfile(payload: ProfileUpdateRequest): Promise<void> {
    await authApi.updateProfile(payload)
    await fetchMe(true)
  }

  async function logout(): Promise<void> {
    try {
      if (token.value) await authApi.logout()
    } catch {
      // 退出接口失败也要清理本地登录态
    } finally {
      setToken('')
      user.value = null
      loaded.value = false
    }
  }

  /** 本地清理，不调接口（401 场景） */
  function clear(): void {
    setToken('')
    user.value = null
    loaded.value = false
  }

  return {
    token,
    user,
    loaded,
    isLoggedIn,
    role,
    isSuperAdmin,
    isRegionAdmin,
    isAdmin,
    displayName,
    login,
    logout,
    clear,
    fetchMe,
    updateProfile,
  }
})
