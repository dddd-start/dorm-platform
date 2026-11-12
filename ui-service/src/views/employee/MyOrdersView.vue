<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { orderApi } from '@/api/order'
import { useMetaStore } from '@/stores/meta'
import { toLoadError } from '@/api/http'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AsyncState from '@/components/AsyncState.vue'
import OrderDetailDrawer from '@/components/OrderDetailDrawer.vue'
import type { OrderStatus, OrderVO } from '@/api/types'

/** 我的订单：列表 + 详情抽屉（抽屉内提供办理入住/退房，退房时会校验提前退房原因） */

const meta = useMetaStore()
const { isMobile } = useBreakpoint()

const loading = ref(false)
const list = ref<OrderVO[]>([])
const total = ref(0)
/** 错误态与"确实没有数据"必须分开：前者渲染 StateError（带重试 + traceId），后者渲染 StateEmpty */
const loadError = ref('')
const loadTraceId = ref('')

/** 是否处于筛选状态 —— 决定空态文案是"没有符合条件"还是"还没有订单" */
const filtered = computed(() => Boolean(query.status || query.keyword))

const query = reactive({
  current: 1,
  size: 10,
  status: '' as OrderStatus | '',
  keyword: '',
})

const drawerVisible = ref(false)
const currentOrderId = ref<string | null>(null)
const currentOrder = ref<OrderVO | null>(null)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  loadTraceId.value = ''
  try {
    const page = await orderApi.mine({
      current: query.current,
      size: query.size,
      status: query.status || undefined,
      keyword: query.keyword || undefined,
    })
    list.value = page.records ?? []
    total.value = page.total ?? 0
  } catch (err) {
    // 不能只清空列表：那会让"接口失败"和"没有订单"看起来一样。
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
  query.keyword = ''
  void load()
}

/** 打开详情抽屉；action 仅用于区分入口，具体操作都在抽屉内完成 */
function openDetail(row: OrderVO): void {
  currentOrder.value = row
  currentOrderId.value = row.id
  drawerVisible.value = true
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page-container">
    <div class="page-card">
      <h3 class="page-title">我的订单</h3>

      <div class="filter-bar">
        <el-select v-model="query.status" placeholder="订单状态" clearable style="width: 150px">
          <el-option
            v-for="item in meta.options('orderStatus')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-input
          v-model="query.keyword"
          placeholder="订单号 / 公寓 / 房间号"
          clearable
          style="width: 220px"
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <div class="scroll-hint">
        <el-icon><DCaret /></el-icon><span>左右滑动查看更多列</span>
      </div>

      <!-- 三态边界：骨架（加载）> 错误（危险色 + 重试 + traceId）> 空（中性灰 + 下一步）> 内容 -->
      <AsyncState
        :loading="loading"
        :error="loadError"
        :trace-id="loadTraceId"
        :empty="list.length === 0"
        skeleton="list"
        :skeleton-rows="5"
        :empty-title="filtered ? '没有符合条件的订单' : '还没有订单数据'"
        :empty-description="
          filtered
            ? '试试放宽状态或关键字条件，或重置后重新查询。'
            : '申请通过并完成分房后，这里会出现订单，可在此办理入住与退房。'
        "
        @retry="load"
      >
        <template #empty-action>
          <el-button v-if="filtered" size="small" @click="handleReset">重置筛选条件</el-button>
        </template>

        <!-- 小屏：宽表转卡片。
             实测 390px 下订单表内部宽度 1320px、可视容器仅 326px，
             末尾 200px 的操作列被挤出可视区，用户根本点不到"办理入住/退房"。 -->
        <div v-if="isMobile" class="m-list" data-test="my-orders-cards">
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
              <span class="m-card__label">公寓</span>
              <span class="m-card__value">{{ row.buildingName ?? '-' }}</span>
              <span class="m-card__label">房间 / 床位</span>
              <span class="m-card__value">{{ row.roomNo ?? '-' }} / {{ row.bedNo ?? '-' }}</span>
              <span class="m-card__label">计划入住</span>
              <span class="m-card__value mono">{{ row.checkInDate ?? '-' }} ~ {{ row.checkOutDate ?? '-' }}</span>
              <span class="m-card__label">实际入住</span>
              <span class="m-card__value mono">{{ row.realCheckInTime ?? '-' }}</span>
              <span class="m-card__label">实际退房</span>
              <span class="m-card__value mono">{{ row.realCheckOutTime ?? '-' }}</span>
              <span class="m-card__label">晚数</span>
              <span class="m-card__value">{{ row.nights ?? '-' }}</span>
              <span class="m-card__label">金额</span>
              <span class="m-card__value">
                <b v-if="row.amount != null" class="amount">{{ row.amount }} {{ row.currency ?? '' }}</b>
                <template v-else>-</template>
              </span>
            </div>
            <div class="m-card__actions">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'RESERVED'" link type="success" @click="openDetail(row)">
                办理入住
              </el-button>
              <el-button v-if="row.status === 'CHECKED_IN'" link type="warning" @click="openDetail(row)">
                办理退房
              </el-button>
            </div>
          </el-card>
        </div>

        <!-- 桌面端：保留表格（跨行比较是表格的不可替代价值） -->
        <el-table v-else :data="list" border stripe>
          <el-table-column prop="orderNo" label="订单号" min-width="160">
            <template #default="{ row }">
              <span class="mono">{{ row.orderNo }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="buildingName" label="公寓" min-width="150">
            <template #default="{ row }">{{ row.buildingName ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="房间 / 床位" width="120">
            <template #default="{ row }">{{ row.roomNo ?? '-' }} / {{ row.bedNo ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="计划入住 / 退房" min-width="190">
            <template #default="{ row }">
              <span class="mono">{{ row.checkInDate ?? '-' }} ~ {{ row.checkOutDate ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="实际入住 / 退房" min-width="200">
            <template #default="{ row }">
              <span class="mono">{{ row.realCheckInTime ?? '-' }}</span>
              <br />
              <span class="mono">{{ row.realCheckOutTime ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="nights" label="晚数" width="70" align="center">
            <template #default="{ row }">{{ row.nights ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="金额" width="130">
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
          <!-- fixed 列在窄屏会吃掉大部分可见宽度，因此仅在桌面固定 -->
          <el-table-column label="操作" width="200" :fixed="isMobile ? undefined : 'right'">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'RESERVED'" link type="success" @click="openDetail(row)">
                办理入住
              </el-button>
              <el-button v-if="row.status === 'CHECKED_IN'" link type="warning" @click="openDetail(row)">
                办理退房
              </el-button>
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
    </div>

    <OrderDetailDrawer
      v-model="drawerVisible"
      :order-id="currentOrderId"
      :order="currentOrder"
      @changed="load"
    />
  </div>
</template>

<style scoped>
/* 金额：原来写死 #f56c6c（浅色下的红），深色主题下落在深底上偏暗；
   改用令牌的危险色文字档，两个主题都由令牌保证可读。 */
.amount {
  color: var(--d-danger-text);
  font-weight: var(--d-font-weight-semibold);
}
</style>
