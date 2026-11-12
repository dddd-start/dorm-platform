<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { applyApi } from '@/api/apply'
import { useMetaStore } from '@/stores/meta'
import { toLoadError } from '@/api/http'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AsyncState from '@/components/AsyncState.vue'
import ApplyDetailDrawer from '@/components/ApplyDetailDrawer.vue'
import type { ApplyVO } from '@/api/types'

/** 我的行程：/api/applies/mine/upcoming 返回的即将出行行程，按入住日期升序时间线展示 */

const meta = useMetaStore()
const { isMobile } = useBreakpoint()

const loading = ref(false)
const trips = ref<ApplyVO[]>([])
/** 接口失败必须与"确实没有行程"区分：前者给重试与 traceId，后者给下一步动作 */
const loadError = ref('')
const loadTraceId = ref('')
const drawerVisible = ref(false)
const currentApplyId = ref<string | null>(null)

/** 按入住日期升序，最近的行程排在最上面 */
const sortedTrips = computed(() =>
  [...trips.value].sort((a, b) => (a.checkInDate ?? '').localeCompare(b.checkInDate ?? '')),
)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  loadTraceId.value = ''
  try {
    trips.value = await applyApi.mineUpcoming()
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    loadError.value = message
    loadTraceId.value = traceId
    trips.value = []
  } finally {
    loading.value = false
  }
}

function openDetail(row: ApplyVO): void {
  currentApplyId.value = row.id
  drawerVisible.value = true
}

/** 距今还有几天入住；负数表示今天或已过 */
function daysUntil(date?: string): number | null {
  if (!date) return null
  const target = new Date(`${date}T00:00:00`).getTime()
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.round((target - today.getTime()) / 86400000)
}

function daysText(date?: string): string {
  const d = daysUntil(date)
  if (d === null) return '-'
  if (d === 0) return '今天入住'
  if (d > 0) return `${d} 天后入住`
  return '已开始'
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page-container">
    <div class="page-card">
      <h3 class="page-title">
        <span>我的行程</span>
        <el-button @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </h3>

      <el-alert
        v-if="!loading && sortedTrips.length > 0"
        type="success"
        show-icon
        :closable="false"
        style="margin-bottom: 16px"
        :title="`你有 ${sortedTrips.length} 条即将出行的住宿行程`"
      />

      <!-- 三态边界。注意这里"没有行程"是**真的空**（服务端正常返回空数组），
           而不是"接口失败" —— 后者必须走 error 分支，带重试与 traceId。 -->
      <AsyncState
        :loading="loading"
        :error="loadError"
        :trace-id="loadTraceId"
        :empty="sortedTrips.length === 0"
        skeleton="card"
        :skeleton-rows="2"
        empty-title="暂无即将出行的行程"
        empty-description="申请通过并分配房间后，行程会按入住日期显示在这里。"
        @retry="load"
      >
        <template #empty-action>
          <el-button type="primary" @click="$router.push({ name: 'room-search' })">去查询房源</el-button>
        </template>

        <el-timeline>
          <el-timeline-item
            v-for="trip in sortedTrips"
            :key="trip.id"
            :color="trip.statusColor || 'var(--d-brand)'"
            :timestamp="`${trip.checkInDate ?? ''} ~ ${trip.checkOutDate ?? ''}`"
            placement="top"
            size="large"
          >
            <el-card shadow="hover">
              <div class="trip-head">
                <div>
                  <span class="mono trip-no">{{ trip.applyNo }}</span>
                  <el-tag
                    size="small"
                    effect="light"
                    style="margin-left: 8px"
                    disable-transitions
                    :color="trip.statusColor ? trip.statusColor + '1A' : undefined"
                  >
                    <span :style="trip.statusColor ? { color: trip.statusColor } : undefined">
                      {{ trip.statusLabel ?? meta.label('applyStatus', trip.status) }}
                    </span>
                  </el-tag>
                  <el-tag size="small" type="warning" effect="plain" style="margin-left: 6px">
                    {{ daysText(trip.checkInDate) }}
                  </el-tag>
                </div>
                <el-button link type="primary" @click="openDetail(trip)">查看详情</el-button>
              </div>

              <!-- 窄屏改单列：原来固定 3 列，390px 下每格只剩 ~110px -->
              <el-descriptions :column="isMobile ? 1 : 3" size="small" style="margin-top: 10px">
                <el-descriptions-item label="区域">{{ trip.regionName ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="公寓">{{ trip.buildingName ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="目的城市">
                  {{ trip.destCity ?? trip.destCountry ?? '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="房间 / 床位">
                  <span v-if="trip.roomNo">{{ trip.roomNo }} / {{ trip.bedNo ?? '-' }}</span>
                  <span v-else class="muted">待分配</span>
                </el-descriptions-item>
                <el-descriptions-item label="住宿晚数">{{ trip.nights ?? '-' }} 晚</el-descriptions-item>
                <el-descriptions-item label="出差单号">{{ trip.tripNo ?? '-' }}</el-descriptions-item>
                <el-descriptions-item v-if="trip.tripNo || trip.reason" label="出差事由" :span="isMobile ? 1 : 3">
                  {{ trip.reason ?? '-' }}
                </el-descriptions-item>
              </el-descriptions>
            </el-card>
          </el-timeline-item>
        </el-timeline>
      </AsyncState>
    </div>

    <ApplyDetailDrawer v-model="drawerVisible" :apply-id="currentApplyId" @changed="load" />
  </div>
</template>

<style scoped>
.trip-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.trip-no {
  font-size: 15px;
  font-weight: 700;
}
</style>
