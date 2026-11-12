<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { statsApi } from '@/api/stats'
import { useMetaStore } from '@/stores/meta'
import { useRegionStore } from '@/stores/region'
import { toLoadError } from '@/api/http'
import AsyncState from '@/components/AsyncState.vue'
import StatCard from '@/components/StatCard.vue'
import type { StatsOverview } from '@/api/types'

/**
 * 统计看板（ECharts）。
 * 四张图：入住率仪表盘、申请状态分布饼图、近 N 天申请趋势折线、各区域房源分布柱状图。
 */

const meta = useMetaStore()
const regionStore = useRegionStore()

const loading = ref(false)
const regionId = ref<number | undefined>(undefined)
const trendDays = ref(14)
const overview = ref<StatsOverview | null>(null)
/** 看板加载失败必须是错误态（带重试），否则整屏 0 会被误读成"业务上真的没有数据" */
const loadError = ref('')
const loadTraceId = ref('')

/* ------------------------------ 图表实例管理 ------------------------------ */
const occupancyRef = ref<HTMLElement | null>(null)
const applyStatusRef = ref<HTMLElement | null>(null)
const trendRef = ref<HTMLElement | null>(null)
const regionRef = ref<HTMLElement | null>(null)

let occupancyChart: echarts.ECharts | null = null
let applyStatusChart: echarts.ECharts | null = null
let trendChart: echarts.ECharts | null = null
let regionChart: echarts.ECharts | null = null

function initCharts(): void {
  if (occupancyRef.value && !occupancyChart) occupancyChart = echarts.init(occupancyRef.value)
  if (applyStatusRef.value && !applyStatusChart) applyStatusChart = echarts.init(applyStatusRef.value)
  if (trendRef.value && !trendChart) trendChart = echarts.init(trendRef.value)
  if (regionRef.value && !regionChart) regionChart = echarts.init(regionRef.value)
}

function handleResize(): void {
  occupancyChart?.resize()
  applyStatusChart?.resize()
  trendChart?.resize()
  regionChart?.resize()
}

/* ------------------------------ 数据加载 ------------------------------ */
async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  loadTraceId.value = ''
  try {
    overview.value = await statsApi.overview(regionId.value, trendDays.value)
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    loadError.value = message
    loadTraceId.value = traceId
    overview.value = null
  } finally {
    loading.value = false
  }
  // 图表容器由 AsyncState 控制显隐：必须等骨架卸载、容器真正挂载后再 init，
  // 否则 echarts.init(null) 会静默失败（这是"改了加载态导致图表消失"的典型坑）。
  await nextTick()
  if (overview.value) renderCharts()
}

/* ------------------------------ 渲染 ------------------------------ */
function renderCharts(): void {
  const data = overview.value
  if (!data) return
  initCharts()

  /* 入住率仪表盘 */
  occupancyChart?.setOption({
    series: [
      {
        type: 'gauge',
        startAngle: 200,
        endAngle: -20,
        min: 0,
        max: 100,
        radius: '92%',
        progress: { show: true, width: 16, itemStyle: { color: '#409eff' } },
        axisLine: { lineStyle: { width: 16, color: [[1, '#eef2f7']] } },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: { show: false },
        pointer: { show: false },
        anchor: { show: false },
        title: { show: true, offsetCenter: [0, '32%'], fontSize: 13, color: '#909399' },
        detail: {
          valueAnimation: true,
          offsetCenter: [0, '-5%'],
          fontSize: 30,
          fontWeight: 'bold',
          formatter: '{value}%',
          color: '#409eff',
        },
        data: [{ value: Number(data.occupancyRate ?? 0), name: '整体入住率' }],
      },
    ],
  })

  /* 申请状态分布饼图 —— 文案取自枚举字典 */
  const applyEntries = Object.entries(data.applyStatusCount ?? {})
  applyStatusChart?.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, type: 'scroll' },
    series: [
      {
        type: 'pie',
        radius: ['40%', '66%'],
        center: ['50%', '45%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
        label: { formatter: '{b}\n{c}' },
        data: applyEntries.map(([key, value]) => ({
          name: meta.label('applyStatus', key),
          value,
          itemStyle: { color: meta.color('applyStatus', key) || undefined },
        })),
      },
    ],
  })

  /* 近 N 天申请趋势折线 */
  const trend = data.applyTrend ?? []
  trendChart?.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 30, bottom: 40 },
    xAxis: {
      type: 'category',
      data: trend.map((p) => p.day),
      axisLabel: { rotate: 35, fontSize: 11 },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '申请量',
        type: 'line',
        smooth: true,
        symbolSize: 6,
        areaStyle: { opacity: 0.18 },
        itemStyle: { color: '#409eff' },
        data: trend.map((p) => p.cnt),
      },
    ],
  })

  /* 各区域房源分布柱状图 */
  const dist = data.regionDistribution ?? []
  regionChart?.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { top: 0 },
    grid: { left: 45, right: 20, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: dist.map((d) => d.regionName) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: '总床位',
        type: 'bar',
        stack: 'bed',
        itemStyle: { color: '#dcdfe6' },
        data: dist.map((d) => d.bedCount),
      },
      {
        name: '已占用',
        type: 'bar',
        stack: 'bed',
        itemStyle: { color: '#f56c6c' },
        data: dist.map((d) => d.occupiedBedCount ?? 0),
      },
      {
        name: '空闲床位',
        type: 'bar',
        itemStyle: { color: '#67c23a' },
        data: dist.map((d) => d.idleBedCount ?? 0),
      },
    ],
  })
}

/* ------------------------------ 统计卡片 ------------------------------ */
function rate(value?: number): string {
  return value === undefined || value === null ? '0' : String(value)
}

watch([regionId, trendDays], () => {
  void load()
})

onMounted(async () => {
  await regionStore.loadRegions().catch(() => undefined)
  await load()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  occupancyChart?.dispose()
  applyStatusChart?.dispose()
  trendChart?.dispose()
  regionChart?.dispose()
  occupancyChart = null
  applyStatusChart = null
  trendChart = null
  regionChart = null
})
</script>

<template>
  <div class="page-container">
    <div class="page-card">
      <h3 class="page-title">
        <span>统计看板</span>
        <span>
          <el-select v-model="regionId" placeholder="全部区域" clearable style="width: 150px; margin-right: 8px">
            <el-option v-for="r in regionStore.regions" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
          <el-select v-model="trendDays" style="width: 130px; margin-right: 8px">
            <el-option label="近 7 天" :value="7" />
            <el-option label="近 14 天" :value="14" />
            <el-option label="近 30 天" :value="30" />
          </el-select>
          <el-button type="primary" :loading="loading" @click="load">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
        </span>
      </h3>

      <el-row :gutter="14">
        <el-col :xs="12" :sm="8" :md="4" style="margin-bottom: 12px">
          <StatCard label="房间总数" :value="overview?.roomCount ?? 0" icon="OfficeBuilding" background="linear-gradient(135deg,#409eff,#66b1ff)" />
        </el-col>
        <el-col :xs="12" :sm="8" :md="4" style="margin-bottom: 12px">
          <StatCard label="床位总数" :value="overview?.bedCount ?? 0" icon="Grid" background="linear-gradient(135deg,#4f6ef7,#7a90ff)" />
        </el-col>
        <el-col :xs="12" :sm="8" :md="4" style="margin-bottom: 12px">
          <StatCard label="空闲床位" :value="overview?.idleBedCount ?? 0" icon="CircleCheck" background="linear-gradient(135deg,#67c23a,#85ce61)" />
        </el-col>
        <el-col :xs="12" :sm="8" :md="4" style="margin-bottom: 12px">
          <StatCard label="已占用床位" :value="overview?.occupiedBedCount ?? 0" icon="User" background="linear-gradient(135deg,#f56c6c,#f78989)" />
        </el-col>
        <el-col :xs="12" :sm="8" :md="4" style="margin-bottom: 12px">
          <StatCard label="入住率" :value="rate(overview?.occupancyRate)" suffix="%" icon="DataAnalysis" background="linear-gradient(135deg,#e6a23c,#f0b357)" />
        </el-col>
        <el-col :xs="12" :sm="8" :md="4" style="margin-bottom: 12px">
          <StatCard
            label="开放 / 关闭 / 维修"
            :value="`${overview?.openRoomCount ?? 0} / ${overview?.closedRoomCount ?? 0} / ${overview?.maintenanceRoomCount ?? 0}`"
            icon="Tools"
            background="linear-gradient(135deg,#303133,#5a5e66)"
          />
        </el-col>
      </el-row>
    </div>

    <!-- 三态边界：骨架（加载）> 错误（重试 + traceId）> 内容。
         原来只有 v-loading 转圈，接口失败时整屏数字变成 0 却毫无提示 ——
         运营会把它读成"今天真的没有入住"。 -->
    <AsyncState
      :loading="loading"
      :error="loadError"
      :trace-id="loadTraceId"
      skeleton="card"
      :skeleton-rows="4"
      @retry="load"
    >
      <el-row :gutter="14">
        <el-col :xs="24" :md="8" style="margin-bottom: 14px">
          <div class="page-card" style="margin-bottom: 0">
            <div class="chart-title">入住率</div>
            <div ref="occupancyRef" class="chart" style="height: 260px"></div>
          </div>
        </el-col>
        <el-col :xs="24" :md="16" style="margin-bottom: 14px">
          <div class="page-card" style="margin-bottom: 0">
            <div class="chart-title">申请状态分布</div>
            <div ref="applyStatusRef" class="chart" style="height: 260px"></div>
          </div>
        </el-col>
        <el-col :xs="24" :md="12" style="margin-bottom: 14px">
          <div class="page-card" style="margin-bottom: 0">
            <div class="chart-title">近 {{ trendDays }} 天申请趋势</div>
            <div ref="trendRef" class="chart" style="height: 300px"></div>
          </div>
        </el-col>
        <el-col :xs="24" :md="12" style="margin-bottom: 14px">
          <div class="page-card" style="margin-bottom: 0">
            <div class="chart-title">各区域房源分布</div>
            <div ref="regionRef" class="chart" style="height: 300px"></div>
          </div>
        </el-col>
      </el-row>

      <div class="page-card">
        <div class="chart-title">各区域明细</div>
        <!-- 明细表保留表格（跨行比较入住率是它的价值），窄屏加滚动提示 -->
        <div class="scroll-hint">
          <el-icon><DCaret /></el-icon><span>左右滑动查看更多列</span>
        </div>
        <AsyncState
          :empty="(overview?.regionDistribution ?? []).length === 0"
          skeleton="list"
          :skeleton-rows="3"
          compact
          empty-title="暂无区域明细"
          empty-description="所选区域范围内还没有已配置的房间与床位数据。"
        >
          <el-table :data="overview?.regionDistribution ?? []" border size="small">
            <el-table-column prop="regionName" label="区域" width="130" />
            <el-table-column prop="roomCount" label="房间数" width="100" align="center" />
            <el-table-column prop="openRoomCount" label="开放房间" width="100" align="center" />
            <el-table-column prop="closedRoomCount" label="关闭房间" width="100" align="center" />
            <el-table-column prop="maintenanceRoomCount" label="维修房间" width="100" align="center" />
            <el-table-column prop="bedCount" label="床位数" width="100" align="center" />
            <el-table-column prop="idleBedCount" label="空闲床位" width="100" align="center" />
            <el-table-column prop="occupiedBedCount" label="已占用" width="100" align="center" />
            <el-table-column prop="disabledBedCount" label="已停用" width="100" align="center" />
            <el-table-column label="入住率" min-width="140">
              <template #default="{ row }">
                <el-progress
                  :percentage="row.bedCount > 0 ? Number((((row.occupiedBedCount ?? 0) / row.bedCount) * 100).toFixed(1)) : 0"
                  :stroke-width="14"
                  text-inside
                />
              </template>
            </el-table-column>
          </el-table>
        </AsyncState>
      </div>
    </AsyncState>
  </div>
</template>

<style scoped>
.chart-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 8px;
}

.chart {
  width: 100%;
}
</style>
