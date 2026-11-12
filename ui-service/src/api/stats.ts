import { api, cleanParams } from './http'
import type { StatsOccupancy, StatsOverview } from './types'

export const statsApi = {
  overview: (regionId?: number, trendDays = 14) =>
    api.get<StatsOverview>('/stats/overview', cleanParams({ regionId, trendDays })),
  occupancy: (regionId?: number) => api.get<StatsOccupancy>('/stats/occupancy', cleanParams({ regionId })),
}
