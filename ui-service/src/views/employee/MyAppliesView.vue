<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { applyApi } from '@/api/apply'
import { useMetaStore } from '@/stores/meta'
import { useRegionStore } from '@/stores/region'
import { toLoadError } from '@/api/http'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AsyncState from '@/components/AsyncState.vue'
import ApplyDetailDrawer from '@/components/ApplyDetailDrawer.vue'
import type { ApplyStatus, ApplyVO } from '@/api/types'

/** 我的申请：条件筛选 + 列表 + 详情抽屉（含状态时间轴与可执行动作） */

const meta = useMetaStore()
const regionStore = useRegionStore()
const { isMobile } = useBreakpoint()

const loading = ref(false)
const list = ref<ApplyVO[]>([])
const total = ref(0)
/** 错误态（加载失败，带重试 + traceId）与空态（确实没有数据）必须分开渲染 */
const loadError = ref('')
const loadTraceId = ref('')

/** 是否处于筛选状态 —— 决定空态文案是"没有符合条件"还是"还没有申请" */
const filtered = computed(() =>
  Boolean(query.status || query.regionId || query.keyword || query.dateRange?.length),
)

const query = reactive({
  current: 1,
  size: 10,
  status: '' as ApplyStatus | '',
  regionId: undefined as number | undefined,
  keyword: '',
  dateRange: [] as string[],
})

const drawerVisible = ref(false)
const currentApplyId = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  loadTraceId.value = ''
  try {
    const page = await applyApi.page({
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
    // 不能只清空列表：那会让"接口失败"和"没有申请"看起来一样。
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

function openDetail(row: ApplyVO): void {
  currentApplyId.value = row.id
  drawerVisible.value = true
}

onMounted(() => {
  void regionStore.loadRegions().catch(() => undefined)
  void load()
})
</script>

<template>
  <div class="page-container">
    <div class="page-card">
      <h3 class="page-title">
        <span>我的申请</span>
        <el-button type="primary" @click="$router.push({ name: 'apply-create' })">
          <el-icon><DocumentAdd /></el-icon> 新建申请
        </el-button>
      </h3>

      <div class="filter-bar">
        <el-select v-model="query.status" placeholder="申请状态" clearable style="width: 140px">
          <el-option
            v-for="item in meta.options('applyStatus')"
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
          start-placeholder="入住开始"
          end-placeholder="入住结束"
          value-format="YYYY-MM-DD"
          style="width: 260px"
        />
        <el-input
          v-model="query.keyword"
          placeholder="单号 / 出差单号 / 城市"
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
        :empty-title="filtered ? '没有符合条件的申请' : '还没有入住申请'"
        :empty-description="
          filtered
            ? '试试放宽状态 / 区域 / 日期条件，或重置后重新查询。'
            : '点击右上角「新建申请」提交第一条入住申请，审批进度会显示在这里。'
        "
        @retry="load"
      >
        <template #empty-action>
          <el-button v-if="filtered" size="small" @click="handleReset">重置筛选条件</el-button>
        </template>

        <!-- 小屏：宽表转卡片。实测 390px 下申请表内部宽度 1230px、可视容器仅 326px -->
        <div v-if="isMobile" class="m-list" data-test="my-applies-cards">
          <el-card v-for="row in list" :key="row.id" shadow="never" class="m-card">
            <div class="m-card__head">
              <span class="m-card__title mono">{{ row.applyNo }}</span>
              <el-tag size="small" effect="light" disable-transitions :color="row.statusColor ? row.statusColor + '1A' : undefined">
                <span :style="row.statusColor ? { color: row.statusColor } : undefined">
                  {{ row.statusLabel ?? meta.label('applyStatus', row.status) }}
                </span>
              </el-tag>
            </div>
            <div class="m-card__rows">
              <span class="m-card__label">区域</span>
              <span class="m-card__value">{{ row.regionName ?? '-' }}</span>
              <span class="m-card__label">目的地</span>
              <span class="m-card__value">
                {{ [row.destCountry, row.destCity].filter(Boolean).join(' / ') || '-' }}
              </span>
              <span class="m-card__label">入住 / 退房</span>
              <span class="m-card__value mono">{{ row.checkInDate ?? '-' }} ~ {{ row.checkOutDate ?? '-' }}</span>
              <span class="m-card__label">晚数</span>
              <span class="m-card__value">{{ row.nights ?? '-' }}</span>
              <span class="m-card__label">房间 / 床位</span>
              <span class="m-card__value">
                <template v-if="row.roomNo">{{ row.roomNo }} / {{ row.bedNo ?? '-' }}</template>
                <span v-else class="muted">待分配</span>
              </span>
              <span class="m-card__label">审批人</span>
              <span class="m-card__value">{{ row.approverName ?? '-' }}</span>
              <span class="m-card__label">创建时间</span>
              <span class="m-card__value">{{ row.createTime ?? '-' }}</span>
            </div>
            <div class="m-card__actions">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            </div>
          </el-card>
        </div>

        <!-- 桌面端：保留表格（跨行比较是表格的不可替代价值） -->
        <el-table v-else :data="list" border stripe>
          <el-table-column prop="applyNo" label="申请单号" min-width="160">
            <template #default="{ row }">
              <span class="mono">{{ row.applyNo }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="regionName" label="区域" width="100">
            <template #default="{ row }">{{ row.regionName ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="目的地" min-width="130">
            <template #default="{ row }">
              {{ [row.destCountry, row.destCity].filter(Boolean).join(' / ') || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="入住 / 退房" min-width="190">
            <template #default="{ row }">
              <span class="mono">{{ row.checkInDate ?? '-' }} ~ {{ row.checkOutDate ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="nights" label="晚数" width="70" align="center">
            <template #default="{ row }">{{ row.nights ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" effect="light" disable-transitions :color="row.statusColor ? row.statusColor + '1A' : undefined">
                <span :style="row.statusColor ? { color: row.statusColor } : undefined">
                  {{ row.statusLabel ?? meta.label('applyStatus', row.status) }}
                </span>
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="房间 / 床位" width="130">
            <template #default="{ row }">
              <span v-if="row.roomNo">{{ row.roomNo }} / {{ row.bedNo ?? '-' }}</span>
              <span v-else class="muted">待分配</span>
            </template>
          </el-table-column>
          <el-table-column prop="approverName" label="审批人" width="100">
            <template #default="{ row }">{{ row.approverName ?? '-' }}</template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" min-width="160">
            <template #default="{ row }">{{ row.createTime ?? '-' }}</template>
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
    </div>

    <ApplyDetailDrawer v-model="drawerVisible" :apply-id="currentApplyId" @changed="load" />
  </div>
</template>
