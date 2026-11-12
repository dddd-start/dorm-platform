import { api } from './http'
import type { EnumDictionary } from './types'

export const metaApi = {
  /** 枚举字典：所有下拉与状态标签的数据源，禁止前端硬编码 */
  enums: () => api.get<EnumDictionary>('/meta/enums'),
}
