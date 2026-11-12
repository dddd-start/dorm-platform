import { defineStore } from 'pinia'
import { ref } from 'vue'
import { buildingApi } from '@/api/building'
import { regionApi } from '@/api/region'
import type { BuildingOption, RegionOption } from '@/api/types'

/** 区域 / 公寓下拉数据缓存，多个页面共用，避免重复请求 */
export const useRegionStore = defineStore('region', () => {
  const regions = ref<RegionOption[]>([])
  const buildings = ref<BuildingOption[]>([])
  const regionLoading = ref(false)
  const buildingLoading = ref(false)

  async function loadRegions(force = false): Promise<RegionOption[]> {
    if (regions.value.length > 0 && !force) return regions.value
    regionLoading.value = true
    try {
      regions.value = await regionApi.options()
    } finally {
      regionLoading.value = false
    }
    return regions.value
  }

  /** 按区域加载公寓选项；不传 regionId 时返回全部 */
  async function loadBuildings(regionId?: number, force = false): Promise<BuildingOption[]> {
    if (buildings.value.length > 0 && regionId === undefined && !force) return buildings.value
    buildingLoading.value = true
    try {
      const list = await buildingApi.options(regionId)
      if (regionId === undefined) buildings.value = list
      return list
    } finally {
      buildingLoading.value = false
    }
  }

  function regionName(id?: number): string {
    if (!id) return '-'
    return regions.value.find((r) => r.id === id)?.name ?? String(id)
  }

  return { regions, buildings, regionLoading, buildingLoading, loadRegions, loadBuildings, regionName }
})
