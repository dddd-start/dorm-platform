import { defineStore } from 'pinia'
import { ref } from 'vue'
import { metaApi } from '@/api/meta'
import type { EnumDictionary, EnumItem, EnumKey } from '@/api/types'

/**
 * 枚举字典 store。
 * 所有下拉选项与状态标签都必须来自 `/api/meta/enums`，禁止前端硬编码，
 * 这里做一次性加载 + 内存缓存，并提供 label / color / options 便捷方法。
 */
export const useMetaStore = defineStore('meta', () => {
  const enums = ref<EnumDictionary | null>(null)
  const loading = ref(false)
  const error = ref('')
  let pending: Promise<void> | null = null

  async function load(force = false): Promise<void> {
    if (enums.value && !force) return
    if (pending) return pending
    loading.value = true
    error.value = ''
    pending = (async () => {
      try {
        enums.value = await metaApi.enums()
      } catch (e) {
        error.value = e instanceof Error ? e.message : '枚举字典加载失败'
        throw e
      } finally {
        loading.value = false
        pending = null
      }
    })()
    return pending
  }

  function list(key: EnumKey): EnumItem[] {
    return enums.value?.[key] ?? []
  }

  /** 取标签文案，找不到时回退为原值 */
  function label(key: EnumKey, value?: string | number | null): string {
    if (value === undefined || value === null || value === '') return '-'
    const hit = list(key).find((i) => i.value === String(value))
    return hit?.label ?? String(value)
  }

  /** 取颜色（状态标签用） */
  function color(key: EnumKey, value?: string | number | null): string {
    if (value === undefined || value === null || value === '') return ''
    const hit = list(key).find((i) => i.value === String(value))
    return hit?.color ?? ''
  }

  /** 取选项（下拉用），已剔除重复 value */
  function options(key: EnumKey): EnumItem[] {
    return list(key)
  }

  return { enums, loading, error, load, list, label, color, options }
})
