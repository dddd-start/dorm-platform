<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { roomApi } from '@/api/room'
import { useMetaStore } from '@/stores/meta'
import { useRegionStore } from '@/stores/region'
import { toLoadError } from '@/api/http'
import AsyncState from '@/components/AsyncState.vue'
import RoomBedsDialog from '@/components/RoomBedsDialog.vue'
import type { BuildingOption, RoomAvailabilityRow } from '@/api/types'

/**
 * 房源查询（员工端首屏）。
 * 数据来自 `/api/rooms/availability`，返回的 `facilities` 是逗号分隔字符串
 * （与 RoomVO 的字符串数组不同），展示前需 split。
 */

const meta = useMetaStore()
const regionStore = useRegionStore()
const router = useRouter()

const loading = ref(false)
const rows = ref<RoomAvailabilityRow[]>([])
/** 查询失败必须是错误态（带重试 + traceId）；"没有符合条件的房间"才是空态 */
const loadError = ref('')
const loadTraceId = ref('')
/** 按区域联动的公寓下拉 */
const buildingOptions = ref<BuildingOption[]>([])

/** 默认查询区间：今天 ~ 明天，保证首屏有结果 */
function todayStr(offsetDays = 0): string {
  const d = new Date()
  d.setDate(d.getDate() + offsetDays)
  return d.toISOString().slice(0, 10)
}

const query = reactive({
  regionId: undefined as number | undefined,
  buildingId: undefined as number | undefined,
  city: '',
  roomType: '' as string,
  gender: '' as string,
  checkInDate: todayStr(0),
  checkOutDate: todayStr(1),
  minBeds: 1,
})

/** 结果前端分页 */
const page = reactive({ current: 1, size: 12 })

const nights = computed(() => {
  if (!query.checkInDate || !query.checkOutDate) return 0
  const diff =
    (new Date(query.checkOutDate).getTime() - new Date(query.checkInDate).getTime()) / 86400000
  return diff > 0 ? Math.round(diff) : 0
})

const pagedRows = computed(() => {
  const start = (page.current - 1) * page.size
  return rows.value.slice(start, start + page.size)
})

/** 把 facilities 的逗号分隔字符串解析成标签数组 */
function facilityTags(raw?: string): string[] {
  if (!raw) return []
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

/**
 * 预计总价。
 * 实测后端 `estimatedAmount` 不会被序列化（DTO 上它是 `estimatedAmount()` 方法而非
 * getter，Jackson 不识别），因此这里优先用返回值，缺失时用「单晚价 × 晚数」在前端兜底。
 */
function estimatedTotal(row: RoomAvailabilityRow): number | null {
  if (row.estimatedAmount != null) return row.estimatedAmount
  if (row.pricePerNight == null) return null
  const n = row.nights ?? nights.value
  if (!n || n <= 0) return null
  return Number((row.pricePerNight * n).toFixed(2))
}

function validateRange(): boolean {
  if (!query.checkInDate || !query.checkOutDate) {
    ElMessage.warning('请选择入住与退房日期')
    return false
  }
  if (nights.value <= 0) {
    ElMessage.warning('退房日期必须晚于入住日期')
    return false
  }
  return true
}

async function search(): Promise<void> {
  if (!validateRange()) return
  loading.value = true
  page.current = 1
  loadError.value = ''
  loadTraceId.value = ''
  try {
    rows.value = await roomApi.availability({
      regionId: query.regionId,
      buildingId: query.buildingId,
      city: query.city || undefined,
      roomType: (query.roomType || undefined) as RoomAvailabilityRow['roomType'] | undefined,
      gender: (query.gender || undefined) as RoomAvailabilityRow['genderLimit'] | undefined,
      checkInDate: query.checkInDate,
      checkOutDate: query.checkOutDate,
      minBeds: query.minBeds || 1,
    })
  } catch (err) {
    // 关键："查询失败"不能画成"没有可订房间" —— 后者会让员工以为真的没房而放弃出行安排
    const { message, traceId } = toLoadError(err)
    loadError.value = message
    loadTraceId.value = traceId
    rows.value = []
  } finally {
    loading.value = false
  }
}

function handleReset(): void {
  query.regionId = undefined
  query.buildingId = undefined
  query.city = ''
  query.roomType = ''
  query.gender = ''
  query.checkInDate = todayStr(0)
  query.checkOutDate = todayStr(1)
  query.minBeds = 1
  buildingOptions.value = []
  void search()
}

/** 区域变化：重置公寓并重新加载公寓下拉 */
async function handleRegionChange(regionId?: number): Promise<void> {
  query.buildingId = undefined
  buildingOptions.value = []
  if (regionId) {
    try {
      buildingOptions.value = await regionStore.loadBuildings(regionId)
    } catch {
      buildingOptions.value = []
    }
  }
}

/* ------------------------------ 床位明细弹窗 ------------------------------ */
const bedsVisible = ref(false)
const bedsRoomId = ref<number | null>(null)
const bedsRoom = ref<RoomAvailabilityRow | null>(null)

function openBeds(row: RoomAvailabilityRow): void {
  bedsRoomId.value = row.roomId
  bedsRoom.value = row
  bedsVisible.value = true
}

/* ------------------------------ 跳转申请页 ------------------------------ */
function goApply(row?: RoomAvailabilityRow | null): void {
  bedsVisible.value = false
  void router.push({
    name: 'apply-create',
    query: {
      regionId: row?.regionId != null ? String(row.regionId) : query.regionId ? String(query.regionId) : '',
      buildingId:
        row?.buildingId != null ? String(row.buildingId) : query.buildingId ? String(query.buildingId) : '',
      city: row?.city ?? query.city ?? '',
      roomType: row?.roomType ?? query.roomType ?? '',
      checkInDate: query.checkInDate,
      checkOutDate: query.checkOutDate,
    },
  })
}

onMounted(async () => {
  await regionStore.loadRegions().catch(() => undefined)
  await search()
})
</script>

<template>
  <div class="page-container">
    <div class="page-card">
      <h3 class="page-title">房源查询</h3>

      <div class="filter-bar">
        <el-select
          v-model="query.regionId"
          placeholder="所属区域"
          clearable
          style="width: 150px"
          @change="handleRegionChange"
        >
          <el-option v-for="r in regionStore.regions" :key="r.id" :label="r.name" :value="r.id" />
        </el-select>

        <el-select
          v-model="query.buildingId"
          placeholder="公寓（可选）"
          clearable
          filterable
          :disabled="!query.regionId"
          style="width: 190px"
        >
          <el-option v-for="b in buildingOptions" :key="b.id" :label="b.name" :value="b.id" />
        </el-select>

        <el-input v-model="query.city" placeholder="城市" clearable style="width: 130px" />

        <el-select v-model="query.roomType" placeholder="房型" clearable style="width: 130px">
          <el-option
            v-for="item in meta.options('roomType')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>

        <el-select v-model="query.gender" placeholder="性别要求" clearable style="width: 120px">
          <el-option
            v-for="item in meta.options('genderLimit')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>

        <el-date-picker
          v-model="query.checkInDate"
          type="date"
          placeholder="入住日期"
          value-format="YYYY-MM-DD"
          style="width: 150px"
        />
        <el-date-picker
          v-model="query.checkOutDate"
          type="date"
          placeholder="退房日期"
          value-format="YYYY-MM-DD"
          style="width: 150px"
        />

        <el-input-number v-model="query.minBeds" :min="1" :max="10" style="width: 130px" />

        <el-button type="primary" :loading="loading" @click="search">
          <el-icon><Search /></el-icon> 查询
        </el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <el-alert v-if="nights > 0" type="info" :closable="false" show-icon style="margin-bottom: 14px">
        <template #title>
          查询区间共 <b>{{ nights }}</b> 晚（{{ query.checkInDate }} ~ {{ query.checkOutDate }}），
          共匹配到 <b>{{ rows.length }}</b> 个可订房间
        </template>
      </el-alert>
    </div>

    <!-- 三态边界：加载（卡片骨架，预示结果形状）/ 出错（重试 + traceId）/ 空（给"重置条件"动作） -->
    <AsyncState
      :loading="loading"
      :error="loadError"
      :trace-id="loadTraceId"
      :empty="pagedRows.length === 0"
      skeleton="card"
      :skeleton-rows="3"
      empty-title="没有符合条件的可订房间"
      empty-description="试试放宽区域 / 房型 / 性别限制，或把入住日期往后调整几天。"
      @retry="search"
    >
      <template #empty-action>
        <el-button @click="handleReset">重置条件</el-button>
      </template>

      <el-row :gutter="14">
        <el-col v-for="row in pagedRows" :key="row.roomId" :xs="24" :sm="12" :lg="8" style="margin-bottom: 14px">
          <el-card class="room-card" shadow="hover">
            <div class="room-card-head">
              <div>
                <span class="room-no">{{ row.roomNo }}</span>
                <el-tag size="small" effect="plain" style="margin-left: 6px">
                  {{ row.roomTypeLabel ?? meta.label('roomType', row.roomType) }}
                </el-tag>
              </div>
              <el-tag size="small" :type="row.status === 'OPEN' ? 'success' : 'info'" effect="plain">
                {{ meta.label('roomStatus', row.status) }}
              </el-tag>
            </div>

            <div style="margin-top: 8px">
              <div class="room-meta">
                <el-icon><OfficeBuilding /></el-icon> {{ row.buildingName ?? '-' }}
              </div>
              <div class="room-meta">
                <el-icon><Location /></el-icon> {{ row.city ?? '-' }}
                <span v-if="row.address" class="muted"> · {{ row.address }}</span>
              </div>
              <div class="room-meta">
                <el-icon><User /></el-icon> 性别限制：{{ meta.label('genderLimit', row.genderLimit) }}
                · 容纳 {{ row.capacity }} 人
              </div>
            </div>

            <el-divider style="margin: 10px 0" />

            <div style="display: flex; justify-content: space-between; align-items: baseline">
              <div>
                <span class="price">{{ row.pricePerNight ?? '-' }}</span>
                <span class="muted"> {{ row.currency ?? '' }} / 晚</span>
              </div>
              <div style="text-align: right">
                <div class="muted">可订床位</div>
                <div class="beds-count">
                  {{ row.availableBeds ?? 0 }}
                  <span class="muted" style="font-weight: 400">/ {{ row.totalBeds ?? row.capacity }}</span>
                </div>
              </div>
            </div>

            <div v-if="estimatedTotal(row) != null" class="muted" style="margin-top: 4px">
              预计总价：<b class="total-price">{{ estimatedTotal(row) }} {{ row.currency ?? '' }}</b>
              （{{ row.nights ?? nights }} 晚）
            </div>

            <div style="margin-top: 10px; min-height: 24px">
              <el-tag
                v-for="f in facilityTags(row.facilities)"
                :key="f"
                size="small"
                effect="plain"
                type="info"
                style="margin: 0 4px 4px 0"
              >
                {{ f }}
              </el-tag>
              <span v-if="facilityTags(row.facilities).length === 0" class="muted">暂无设施信息</span>
            </div>

            <div style="margin-top: 10px; display: flex; gap: 8px">
              <el-button size="small" @click="openBeds(row)">床位明细</el-button>
              <el-button
                size="small"
                type="primary"
                :disabled="(row.availableBeds ?? 0) <= 0"
                @click="goApply(row)"
              >
                申请入住
              </el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </AsyncState>

    <div v-if="rows.length > page.size" class="pager" style="padding: 0 16px 16px">
      <el-pagination
        v-model:current-page="page.current"
        v-model:page-size="page.size"
        :total="rows.length"
        :page-sizes="[12, 24, 48]"
        layout="total, sizes, prev, pager, next"
      />
    </div>

    <RoomBedsDialog
      v-model="bedsVisible"
      :room-id="bedsRoomId"
      :check-in-date="query.checkInDate"
      :check-out-date="query.checkOutDate"
      @apply="goApply(bedsRoom)"
    />
  </div>
</template>

<style scoped>
/* 可订床位数：原来写死 #409eff（亮蓝），深色主题下落在深底上偏暗；
   改用令牌的品牌描边色档，两个主题都达标。 */
.beds-count {
  font-weight: var(--d-font-weight-bold);
  font-size: var(--d-font-size-lg);
  color: var(--d-brand);
}

.total-price {
  color: var(--d-danger-text);
}
</style>
