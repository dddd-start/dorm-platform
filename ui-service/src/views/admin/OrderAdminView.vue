<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { orderApi } from '@/api/order'
import { useMetaStore } from '@/stores/meta'
import { useRegionStore } from '@/stores/region'
import { toLoadError } from '@/api/http'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AsyncState from '@/components/AsyncState.vue'
import OrderDetailDrawer from '@/components/OrderDetailDrawer.vue'
import type { OrderStatus, OrderVO } from '@/api/types'

/** 订单管理：全部订单查询 + 逾期未退房列表 */

const meta = useMetaStore()
const regionStore = useRegionStore()
const { isMobile } = useBreakpoint()

const activeTab = ref('all')

/* ------------------------------ 全部订单 ------------------------------ */
const loading = ref(false)
const list = ref<OrderVO[]>([])
const total = ref(0)
/** 错误态（带重试 + traceId）与空态分开 */
const loadError = ref('')
const loadTraceId = ref('')

const query = reactive({
  current: 1,
  size: 10,
  status: '' as OrderStatus | '',
  regionId: undefined as number | undefined,
  keyword: '',
  dateRange: [] as string[],
})

/** 是否处于筛选状态 —— 决定空态文案是"没有符合条件"还是"还没有订单" */
const filtered = computed(() =>
  Boolean(query.status || query.regionId || query.keyword || query.dateRange?.length),
)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  loadTraceId.value = ''
  try {
    const page = await orderApi.page({
      current: query.current,
      size: query.size,
      status: query.status || undefined,
      regionId: query.regionId,
      keyword: query.keyword || undefined,
      from: query.dateRange?.[0] || undefined,
      to: query.dateRange?.[1] || undefined,
    })
    list.value = page.records ?? []
    total.value = page.total ?? 0
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    loadError.value = message
    loadTraceId.value = traceId
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  query.current = 1
  void load()
}

function handleReset(): void {
  query.current = 1
  query.status = ''
  query.regionId = undefined
  query.keyword = ''
  query.dateRange = []
  void load()
}

/* ------------------------------ 逾期未退房 ------------------------------ */
const overdueLoading = ref(false)
const overdueList = ref<OrderVO[]>([])
const overdueError = ref('')
const overdueTraceId = ref('')

async function loadOverdue(): Promise<void> {
  overdueLoading.value = true
  overdueError.value = ''
  overdueTraceId.value = ''
  try {
    overdueList.value = await orderApi.overdue(50)
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    overdueError.value = message
    overdueTraceId.value = traceId
    overdueList.value = []
  } finally {
    overdueLoading.value = false
  }
}

/** 逾期天数：今天 - 计划退房日 */
function overdueDays(row: OrderVO): number {
  if (!row.checkOutDate) return 0
  const planned = new Date(`${row.checkOutDate}T00:00:00`).getTime()
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const diff = Math.round((today.getTime() - planned) / 86400000)
  return diff > 0 ? diff : 0
}

/* ------------------------------ 详情 ------------------------------ */
const drawerVisible = ref(false)
const currentOrderId = ref<string | null>(null)
const currentOrder = ref<OrderVO | null>(null)

function openDetail(row: OrderVO): void {
  currentOrder.value = row
  currentOrderId.value = row.id
  drawerVisible.value = true
}

function refreshAll(): void {
  void load()
  void loadOverdue()
}

onMounted(() => {
  void regionStore.loadRegions().catch(() => undefined)
  void load()
  void loadOverdue()
})
</script>

<template>
  <div class="page-container">
    <div class="page-card">
      <h3 class="page-title">订单管理</h3>

      <el-tabs v-model="activeTab">
        <!-- ------------------------------ 全部订单 ------------------------------ -->
        <el-tab-pane label="全部订单" name="all">
          <div class="filter-bar">
            <el-select v-model="query.status" placeholder="订单状态" clearable style="width: 140px">
              <el-option
                v-for="item in meta.options('orderStatus')"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-select v-model="query.regionId" placeholder="所属区域" clearable style="width: 150px">
              <el-option v-for="r in regionStore.regions" :key="r.id" :label="r.name" :value="r.id" />
            </el-select>
            <el-date-picker
              v-model="query.dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              value-format="YYYY-MM-DD"
              style="width: 250px"
            />
            <el-input
              v-model="query.keyword"
              placeholder="订单号 / 入住人 / 房间号"
              clearable
              style="width: 210px"
              @keyup.enter="handleSearch"
            />
            <el-button type="primary" @click="handleSearch">查询</el-button>
            <el-button @click="handleReset">重置</el-button>
          </div>

          <div class="scroll-hint">
            <el-icon><DCaret /></el-icon><span>左右滑动查看更多列</span>
          </div>

          <!-- 三态边界：加载（骨架）> 出错（重试 + traceId）> 空（区分"未筛选"与"筛选无结果"） -->
          <AsyncState
            :loading="loading"
            :error="loadError"
            :trace-id="loadTraceId"
            :empty="list.length === 0"
            skeleton="list"
            :skeleton-rows="5"
            :empty-title="filtered ? '没有符合条件的订单' : '暂无订单数据'"
            :empty-description="
              filtered
                ? '试试放宽状态 / 区域 / 日期条件，或重置后重新查询。'
                : '员工提交入住申请并通过分房后，这里会出现订单。'
            "
            @retry="load"
          >
            <template #empty-action>
              <el-button v-if="filtered" size="small" @click="handleReset">重置筛选条件</el-button>
            </template>

            <!-- 小屏：宽表转卡片（实测 390px 下该表内部宽度 1300px、可视容器仅 326px） -->
            <div v-if="isMobile" class="m-list" data-test="admin-orders-cards">
              <el-card v-for="row in list" :key="row.id" shadow="never" class="m-card">
                <div class="m-card__head">
                  <span class="m-card__title mono">{{ row.orderNo }}</span>
                  <el-tag size="small" effect="light" disable-transitions :color="row.statusColor ? row.statusColor + '1A' : undefined">
                    <span :style="row.statusColor ? { color: row.statusColor } : undefined">
                      {{ row.statusLabel ?? meta.label('orderStatus', row.status) }}
                    </span>
                  </el-tag>
                </div>
                <div class="m-card__rows">
                  <span class="m-card__label">入住人</span>
                  <span class="m-card__value">{{ row.employeeName ?? '-' }}</span>
                  <span class="m-card__label">区域</span>
                  <span class="m-card__value">{{ row.regionName ?? '-' }}</span>
                  <span class="m-card__label">公寓</span>
                  <span class="m-card__value">{{ row.buildingName ?? '-' }}</span>
                  <span class="m-card__label">房间 / 床位</span>
                  <span class="m-card__value">{{ row.roomNo ?? '-' }} / {{ row.bedNo ?? '-' }}</span>
                  <span class="m-card__label">计划入住</span>
                  <span class="m-card__value mono">
                    {{ row.checkInDate ?? '-' }} ~ {{ row.checkOutDate ?? '-' }}
                  </span>
                  <span class="m-card__label">实际入住</span>
                  <span class="m-card__value mono">{{ row.realCheckInTime ?? '-' }}</span>
                  <span class="m-card__label">实际退房</span>
                  <span class="m-card__value mono">{{ row.realCheckOutTime ?? '-' }}</span>
                  <span class="m-card__label">金额</span>
                  <span class="m-card__value">
                    <b v-if="row.amount != null" class="amount">{{ row.amount }} {{ row.currency ?? '' }}</b>
                    <template v-else>-</template>
                  </span>
                </div>
                <div class="m-card__actions">
                  <el-button link type="primary" @click="openDetail(row)">详情</el-button>
                </div>
              </el-card>
            </div>

            <el-table v-else :data="list" border stripe>
              <el-table-column prop="orderNo" label="订单号" min-width="155">
                <template #default="{ row }">
                  <span class="mono">{{ row.orderNo }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="employeeName" label="入住人" width="100">
                <template #default="{ row }">{{ row.employeeName ?? '-' }}</template>
              </el-table-column>
              <el-table-column prop="regionName" label="区域" width="100">
                <template #default="{ row }">{{ row.regionName ?? '-' }}</template>
              </el-table-column>
              <el-table-column prop="buildingName" label="公寓" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.buildingName ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="房间 / 床位" width="110">
                <template #default="{ row }">{{ row.roomNo ?? '-' }} / {{ row.bedNo ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="计划入住 / 退房" min-width="185">
                <template #default="{ row }">
                  <span class="mono">{{ row.checkInDate ?? '-' }} ~ {{ row.checkOutDate ?? '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="实际入住 / 退房" min-width="190">
                <template #default="{ row }">
                  <div class="mono">{{ row.realCheckInTime ?? '-' }}</div>
                  <div class="mono">{{ row.realCheckOutTime ?? '-' }}</div>
                </template>
              </el-table-column>
              <el-table-column label="金额" width="120">
                <template #default="{ row }">
                  <span v-if="row.amount != null" class="amount">
                    {{ row.amount }} {{ row.currency ?? '' }}
                  </span>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag size="small" effect="light" disable-transitions :color="row.statusColor ? row.statusColor + '1A' : undefined">
                    <span :style="row.statusColor ? { color: row.statusColor } : undefined">
                      {{ row.statusLabel ?? meta.label('orderStatus', row.status) }}
                    </span>
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90" :fixed="isMobile ? undefined : 'right'">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openDetail(row)">详情</el-button>
                </template>
              </el-table-column>
            </el-table>

            <div v-if="list.length > 0" class="pager">
              <el-pagination
                v-model:current-page="query.current"
                v-model:page-size="query.size"
                :total="total"
                :page-sizes="[10, 20, 50]"
                layout="total, sizes, prev, pager, next, jumper"
                @current-change="load"
                @size-change="handleSearch"
              />
            </div>
          </AsyncState>
        </el-tab-pane>

        <!-- ------------------------------ 逾期未退房 ------------------------------ -->
        <el-tab-pane name="overdue">
          <template #label>
            <span>
              逾期未退房
              <el-badge v-if="overdueList.length > 0" :value="overdueList.length" type="danger" />
            </span>
          </template>

          <el-alert
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom: 12px"
            title="以下订单已超过计划退房日期但尚未办理退房，请及时跟进处理。"
          />

          <div class="scroll-hint">
            <el-icon><DCaret /></el-icon><span>左右滑动查看更多列</span>
          </div>

          <!-- 逾期列表：空态语义与"全部订单"不同 —— 这里为空是好消息，不是"缺数据" -->
          <AsyncState
            :loading="overdueLoading"
            :error="overdueError"
            :trace-id="overdueTraceId"
            :empty="overdueList.length === 0"
            skeleton="list"
            :skeleton-rows="4"
            empty-title="当前没有逾期未退的订单"
            empty-description="所有已入住订单都在计划退房日期内，无需跟进。"
            @retry="loadOverdue"
          >
            <div v-if="isMobile" class="m-list" data-test="overdue-orders-cards">
              <el-card v-for="row in overdueList" :key="row.id" shadow="never" class="m-card">
                <div class="m-card__head">
                  <span class="m-card__title mono">{{ row.orderNo }}</span>
                  <el-tag type="danger" size="small">逾期 {{ overdueDays(row) }} 天</el-tag>
                </div>
                <div class="m-card__rows">
                  <span class="m-card__label">入住人</span>
                  <span class="m-card__value">{{ row.employeeName ?? '-' }}</span>
                  <span class="m-card__label">区域</span>
                  <span class="m-card__value">{{ row.regionName ?? '-' }}</span>
                  <span class="m-card__label">公寓</span>
                  <span class="m-card__value">{{ row.buildingName ?? '-' }}</span>
                  <span class="m-card__label">房间 / 床位</span>
                  <span class="m-card__value">{{ row.roomNo ?? '-' }} / {{ row.bedNo ?? '-' }}</span>
                  <span class="m-card__label">计划退房</span>
                  <span class="m-card__value mono">{{ row.checkOutDate ?? '-' }}</span>
                  <span class="m-card__label">实际入住</span>
                  <span class="m-card__value mono">{{ row.realCheckInTime ?? '-' }}</span>
                </div>
                <div class="m-card__actions">
                  <el-button link type="primary" @click="openDetail(row)">详情</el-button>
                </div>
              </el-card>
            </div>

            <el-table v-else :data="overdueList" border stripe>
              <el-table-column prop="orderNo" label="订单号" min-width="155">
                <template #default="{ row }">
                  <span class="mono">{{ row.orderNo }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="employeeName" label="入住人" width="100">
                <template #default="{ row }">{{ row.employeeName ?? '-' }}</template>
              </el-table-column>
              <el-table-column prop="regionName" label="区域" width="100">
                <template #default="{ row }">{{ row.regionName ?? '-' }}</template>
              </el-table-column>
              <el-table-column prop="buildingName" label="公寓" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.buildingName ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="房间 / 床位" width="110">
                <template #default="{ row }">{{ row.roomNo ?? '-' }} / {{ row.bedNo ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="计划退房" width="120">
                <template #default="{ row }">
                  <span class="mono">{{ row.checkOutDate ?? '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="逾期天数" width="100" align="center">
                <template #default="{ row }">
                  <el-tag type="danger" size="small">{{ overdueDays(row) }} 天</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="实际入住时间" min-width="170">
                <template #default="{ row }">
                  <span class="mono">{{ row.realCheckInTime ?? '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90" :fixed="isMobile ? undefined : 'right'">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openDetail(row)">详情</el-button>
                </template>
              </el-table-column>
            </el-table>

            <div class="pager">
              <el-button @click="loadOverdue">
                <el-icon><Refresh /></el-icon> 刷新
              </el-button>
            </div>
          </AsyncState>
        </el-tab-pane>
      </el-tabs>
    </div>

    <OrderDetailDrawer
      v-model="drawerVisible"
      :order-id="currentOrderId"
      :order="currentOrder"
      @changed="refreshAll"
    />
  </div>
</template>

<style scoped>
/* 金额：原来写死 #f56c6c，深色主题下偏暗；改用令牌的危险色文字档 */
.amount {
  color: var(--d-danger-text);
  font-weight: var(--d-font-weight-semibold);
}
</style>
