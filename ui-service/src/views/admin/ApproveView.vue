<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { applyApi } from '@/api/apply'
import { roomApi } from '@/api/room'
import { useMetaStore } from '@/stores/meta'
import { useRegionStore } from '@/stores/region'
import { toLoadError } from '@/api/http'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AsyncState from '@/components/AsyncState.vue'
import ApplyDetailDrawer from '@/components/ApplyDetailDrawer.vue'
import StatusTag from '@/components/StatusTag.vue'
import StatCard from '@/components/StatCard.vue'
import type {
  ApplyStatus,
  ApplyVO,
  RoomAvailabilityRow,
  RoomBedDetail,
} from '@/api/types'

/**
 * 申请审批（管理员）。
 * 审批与分房在后端是一个原子操作：POST /api/applies/approve {applyId,roomId,bedId,remark}。
 * 因此前端流程为：选可订房间（availability 接口按申请的区域+日期过滤）→ 选空闲床位 → 确认。
 */

const meta = useMetaStore()
const regionStore = useRegionStore()
const { isMobile } = useBreakpoint()

/* ------------------------------ 列表 ------------------------------ */
const loading = ref(false)
const list = ref<ApplyVO[]>([])
const total = ref(0)
const stats = ref<Record<string, number>>({})
/** 错误态（加载失败，带重试 + traceId）与空态（确实没有申请）必须分开 */
const loadError = ref('')
const loadTraceId = ref('')
/** 统计卡片失败：与"统计真的是 0"不同，必须能提示出来 */
const statsError = ref('')
const statsTraceId = ref('')

/** 是否处于筛选状态 —— 决定空态文案 */
const filtered = computed(() =>
  Boolean(query.status || query.regionId !== undefined || query.keyword || query.dateRange?.length),
)

const query = reactive({
  current: 1,
  size: 10,
  status: 'PENDING' as ApplyStatus | '',
  regionId: undefined as number | undefined,
  keyword: '',
  dateRange: [] as string[],
})

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
    const { message, traceId } = toLoadError(err)
    loadError.value = message
    loadTraceId.value = traceId
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadStats(): Promise<void> {
  statsError.value = ''
  statsTraceId.value = ''
  try {
    stats.value = await applyApi.statistics(query.regionId)
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    statsError.value = message
    statsTraceId.value = traceId
    stats.value = {}
  }
}

function handleSearch(): void {
  query.current = 1
  void load()
  void loadStats()
}

function handleReset(): void {
  query.current = 1
  query.status = 'PENDING'
  query.regionId = undefined
  query.keyword = ''
  query.dateRange = []
  handleSearch()
}

/** 统计卡片：只展示关心的几个状态 + 总数 */
const statCards = computed(() => {
  const order: { key: string; label: string; bg: string }[] = [
    { key: 'PENDING', label: '待审批', bg: 'linear-gradient(135deg,#e6a23c,#f0b357)' },
    { key: 'APPROVED', label: '已通过', bg: 'linear-gradient(135deg,#409eff,#66b1ff)' },
    { key: 'CHECKED_IN', label: '已入住', bg: 'linear-gradient(135deg,#67c23a,#85ce61)' },
    { key: 'CHECKED_OUT', label: '已退房', bg: 'linear-gradient(135deg,#909399,#b1b3b8)' },
    { key: 'TOTAL', label: '申请总数', bg: 'linear-gradient(135deg,#303133,#5a5e66)' },
  ]
  return order.map((o) => ({ ...o, value: stats.value[o.key] ?? 0 }))
})

/** 判断某行是否可执行某动作：优先用 allowedActions，缺失时按状态兜底 */
function can(row: ApplyVO, action: string): boolean {
  const actions = row.allowedActions ?? []
  if (actions.length > 0) return actions.includes(action)
  if (action === 'APPROVE') return row.status === 'PENDING'
  if (action === 'REJECT') return row.status === 'PENDING'
  if (action === 'CANCEL') return row.status === 'PENDING' || row.status === 'APPROVED'
  return false
}

/* ------------------------------ 审批分房对话框 ------------------------------ */
const approveVisible = ref(false)
const approveLoading = ref(false)
const submitting = ref(false)
const currentApply = ref<ApplyVO | null>(null)

const rooms = ref<RoomAvailabilityRow[]>([])
const bedsLoading = ref(false)
const beds = ref<RoomBedDetail[]>([])
const selectedRoomId = ref<number | null>(null)
const selectedBedId = ref<number | null>(null)
const approveRemark = ref('')
/** 房间列表的二次过滤（前端过滤，接口已按区域+日期筛过） */
const roomFilter = reactive({ keyword: '', buildingId: undefined as number | undefined, roomType: '' })
/** 可订房间 / 床位加载的错误态 */
const approveError = ref('')
const approveTraceId = ref('')
const bedsError = ref('')
const bedsTraceId = ref('')

const selectedRoom = computed(() => rooms.value.find((r) => r.roomId === selectedRoomId.value) ?? null)

const filteredRooms = computed(() =>
  rooms.value.filter((r) => {
    if (roomFilter.keyword) {
      const kw = roomFilter.keyword.toLowerCase()
      const hit =
        r.roomNo.toLowerCase().includes(kw) ||
        (r.buildingName ?? '').toLowerCase().includes(kw) ||
        (r.city ?? '').toLowerCase().includes(kw)
      if (!hit) return false
    }
    if (roomFilter.buildingId && r.buildingId !== roomFilter.buildingId) return false
    if (roomFilter.roomType && r.roomType !== roomFilter.roomType) return false
    return true
  }),
)

/** 可选公寓下拉（从可订结果里去重生成） */
const roomBuildingOptions = computed(() => {
  const map = new Map<number, string>()
  for (const r of rooms.value) if (!map.has(r.buildingId)) map.set(r.buildingId, r.buildingName ?? String(r.buildingId))
  return [...map.entries()].map(([id, name]) => ({ id, name }))
})

async function openApprove(row: ApplyVO): Promise<void> {
  currentApply.value = row
  selectedRoomId.value = null
  selectedBedId.value = null
  approveRemark.value = ''
  roomFilter.keyword = ''
  roomFilter.buildingId = undefined
  roomFilter.roomType = ''
  beds.value = []
  approveVisible.value = true

  await loadApproveRooms(row)
}

/** 拉取可订房间；失败进入错误态（不能画成"没有可订房间"，否则审批人会误判无房可分配） */
async function loadApproveRooms(row: ApplyVO): Promise<void> {
  approveLoading.value = true
  approveError.value = ''
  approveTraceId.value = ''
  try {
    rooms.value = await roomApi.availability({
      regionId: row.regionId,
      buildingId: row.buildingId,
      roomType: row.preferredRoomType,
      checkInDate: row.checkInDate,
      checkOutDate: row.checkOutDate,
      minBeds: 1,
    })
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    approveError.value = message
    approveTraceId.value = traceId
    rooms.value = []
  } finally {
    approveLoading.value = false
  }
}

/** 重试拉取可订房间 */
async function retryApproveRooms(): Promise<void> {
  if (currentApply.value) await loadApproveRooms(currentApply.value)
}

/** 选中房间 → 拉取该房间床位明细 */
async function handleSelectRoom(row: RoomAvailabilityRow): Promise<void> {
  selectedRoomId.value = row.roomId
  selectedBedId.value = null
  await loadBeds(row.roomId)
}

/** 拉取某房间的床位明细；失败同样进入错误态而不是空态 */
async function loadBeds(roomId: number): Promise<void> {
  bedsLoading.value = true
  bedsError.value = ''
  bedsTraceId.value = ''
  try {
    beds.value = await roomApi.beds(roomId)
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    bedsError.value = message
    bedsTraceId.value = traceId
    beds.value = []
  } finally {
    bedsLoading.value = false
  }
}

/** 重试床位加载 */
async function retryBeds(): Promise<void> {
  if (selectedRoomId.value != null) await loadBeds(selectedRoomId.value)
}

/** 床位是否可选：空闲 且（接口未返回 availableForRange 或为 true） */
function bedSelectable(bed: RoomBedDetail): boolean {
  if (bed.status !== 'IDLE') return false
  if (bed.availableForRange === false) return false
  return true
}

/**
 * 预计总价兜底：后端 `estimatedAmount` 实际不会被序列化
 * （DTO 上是 `estimatedAmount()` 方法而非 getter，Jackson 不识别），
 * 缺失时用「单晚价 × 晚数」在前端计算。
 */
function estimatedTotal(row: RoomAvailabilityRow): number | null {
  if (row.estimatedAmount != null) return row.estimatedAmount
  if (row.pricePerNight == null) return null
  const n = row.nights
  if (!n || n <= 0) return null
  return Number((row.pricePerNight * n).toFixed(2))
}

const canSubmitApprove = computed(
  () => Boolean(currentApply.value) && selectedRoomId.value !== null && selectedBedId.value !== null,
)

async function submitApprove(): Promise<void> {
  if (!currentApply.value || selectedRoomId.value === null || selectedBedId.value === null) return
  submitting.value = true
  try {
    await applyApi.approve({
      applyId: currentApply.value.id,
      roomId: selectedRoomId.value,
      bedId: selectedBedId.value,
      remark: approveRemark.value || undefined,
    })
    ElMessage.success('审批分房成功')
    approveVisible.value = false
    await load()
    await loadStats()
  } catch {
    /* 错误提示已统一处理 */
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 换房 ------------------------------ */
const reassignVisible = ref(false)
const reassignApply = ref<ApplyVO | null>(null)
const reassignBeds = ref<RoomBedDetail[]>([])
const reassignBedId = ref<number | null>(null)
const reassignReason = ref('')
const reassignLoading = ref(false)

async function openReassign(row: ApplyVO): Promise<void> {
  if (!row.roomId) {
    ElMessage.warning('该申请尚未分配房间，请先使用「审批分房」')
    return
  }
  reassignApply.value = row
  reassignBedId.value = null
  reassignReason.value = ''
  reassignVisible.value = true
  reassignLoading.value = true
  try {
    reassignBeds.value = await roomApi.beds(row.roomId)
  } catch {
    reassignBeds.value = []
  } finally {
    reassignLoading.value = false
  }
}

async function submitReassign(): Promise<void> {
  if (!reassignApply.value || reassignBedId.value === null) return
  submitting.value = true
  try {
    await applyApi.reassign({
      applyId: reassignApply.value.id,
      bedId: reassignBedId.value,
      reason: reassignReason.value || undefined,
    })
    ElMessage.success('换房成功')
    reassignVisible.value = false
    await load()
  } catch {
    /* 已提示 */
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 驳回 / 取消 ------------------------------ */
async function handleReject(row: ApplyVO): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(`驳回申请 ${row.applyNo}`, '驳回申请', {
      confirmButtonText: '确认驳回',
      cancelButtonText: '返回',
      inputPlaceholder: '请填写驳回原因（必填）',
      inputValidator: (v: string) => (v && v.trim().length > 0 ? true : '驳回原因不能为空'),
    })
    await applyApi.reject({ applyId: row.id, reason: value })
    ElMessage.success('已驳回')
    await load()
    await loadStats()
  } catch {
    /* 已提示 */
  }
}

async function handleCancel(row: ApplyVO): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(`取消申请 ${row.applyNo}`, '取消申请', {
      confirmButtonText: '确认取消',
      cancelButtonText: '返回',
      inputPlaceholder: '请填写取消原因',
    })
    await applyApi.cancel({ applyId: row.id, reason: value || undefined })
    ElMessage.success('已取消')
    await load()
    await loadStats()
  } catch {
    /* 已提示 */
  }
}

/* ------------------------------ 详情 ------------------------------ */
const drawerVisible = ref(false)
const currentApplyId = ref<string | null>(null)

function openDetail(row: ApplyVO): void {
  currentApplyId.value = row.id
  drawerVisible.value = true
}

onMounted(() => {
  void regionStore.loadRegions().catch(() => undefined)
  void load()
  void loadStats()
})
</script>

<template>
  <div class="page-container">
    <!-- 统计卡片 -->
    <el-row :gutter="14" style="margin-bottom: 16px">
      <el-col v-for="c in statCards" :key="c.key" :xs="12" :sm="8" :md="4" style="margin-bottom: 12px">
        <StatCard :label="c.label" :value="c.value" :background="c.bg" />
      </el-col>
    </el-row>

    <div class="page-card">
      <h3 class="page-title">申请审批</h3>

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
          style="width: 250px"
        />
        <el-input
          v-model="query.keyword"
          placeholder="单号 / 申请人 / 城市"
          clearable
          style="width: 210px"
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <!-- 审批列表**保留表格**（跨行比较申请状态与日期是表格的价值）；
           窄屏改用横向滚动 + 提示条，并取消 fixed 操作列 ——
           实测 390px 下 290px 的 fixed 操作列占掉可视宽度 326px 的 89%，
           用户除了操作列几乎看不到任何信息。 -->
      <div class="scroll-hint">
        <el-icon><DCaret /></el-icon><span>左右滑动查看更多列</span>
      </div>

      <AsyncState
        :loading="loading"
        :error="loadError"
        :trace-id="loadTraceId"
        :empty="list.length === 0"
        skeleton="list"
        :skeleton-rows="5"
        :empty-title="filtered ? '没有符合条件的申请' : '暂无申请数据'"
        :empty-description="
          filtered
            ? '试试放宽状态 / 区域 / 日期条件，或重置后重新查询。'
            : '员工提交入住申请后，待审批的申请会出现在这里。'
        "
        @retry="load"
      >
        <template #empty-action>
          <el-button v-if="filtered" size="small" @click="handleReset">重置筛选条件</el-button>
        </template>

        <el-table :data="list" border stripe>
        <el-table-column prop="applyNo" label="申请单号" min-width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.applyNo }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="employeeName" label="申请人" width="100">
          <template #default="{ row }">{{ row.employeeName ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="regionName" label="区域" width="100">
          <template #default="{ row }">{{ row.regionName ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="目的地" min-width="120">
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
        <el-table-column label="期望房型 / 公寓" min-width="150">
          <template #default="{ row }">
            <div>{{ row.preferredRoomType ? meta.label('roomType', row.preferredRoomType) : '不限' }}</div>
            <div class="muted">{{ row.buildingName ?? '公寓不限' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusTag enum-key="applyStatus" :value="row.status" :override-color="row.statusColor" />
          </template>
        </el-table-column>
        <el-table-column label="房间 / 床位" width="120">
          <template #default="{ row }">
            <span v-if="row.roomNo">{{ row.roomNo }} / {{ row.bedNo ?? '-' }}</span>
            <span v-else class="muted">待分配</span>
          </template>
        </el-table-column>
        <el-table-column prop="approverName" label="审批人" width="100">
          <template #default="{ row }">{{ row.approverName ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="290" :fixed="isMobile ? undefined : 'right'">
          <template #default="{ row }">
            <el-button v-if="can(row, 'APPROVE')" link type="primary" @click="openApprove(row)">
              审批分房
            </el-button>
            <el-button v-if="can(row, 'REJECT')" link type="danger" @click="handleReject(row)">驳回</el-button>
            <el-button v-if="row.roomId" link type="warning" @click="openReassign(row)">换房</el-button>
            <el-button v-if="can(row, 'CANCEL')" link type="info" @click="handleCancel(row)">取消</el-button>
            <el-button link @click="openDetail(row)">详情</el-button>
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

    <!-- ------------------------------ 审批分房 ------------------------------ -->
    <el-dialog v-model="approveVisible" title="审批分房" width="1100px" top="5vh" append-to-body>
      <template v-if="currentApply">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
          <template #title>
            单号 <b class="mono">{{ currentApply.applyNo }}</b> ·
            {{ currentApply.employeeName ?? '-' }} ·
            {{ currentApply.regionName ?? '-' }} ·
            {{ currentApply.checkInDate }} ~ {{ currentApply.checkOutDate }}
            （{{ currentApply.nights ?? '-' }} 晚）·
            期望房型 {{ currentApply.preferredRoomType ? meta.label('roomType', currentApply.preferredRoomType) : '不限' }}
            · 期望公寓 {{ currentApply.buildingName ?? '不限' }}
          </template>
        </el-alert>

        <!-- 窄屏单列堆叠：1100px 的两栏布局在 390px 下会把右侧床位面板完全挤出屏幕 -->
        <el-row :gutter="14">
          <!-- 左侧：可订房间 -->
          <el-col :xs="24" :md="14">
            <div class="panel-title">① 选择房间（已按申请区域与日期过滤）</div>
            <div class="filter-bar" style="margin-bottom: 8px">
              <el-input v-model="roomFilter.keyword" placeholder="房间号 / 公寓 / 城市" clearable size="small" style="width: 180px" />
              <el-select v-model="roomFilter.buildingId" placeholder="公寓" clearable size="small" style="width: 150px">
                <el-option v-for="b in roomBuildingOptions" :key="b.id" :label="b.name" :value="b.id" />
              </el-select>
              <el-select v-model="roomFilter.roomType" placeholder="房型" clearable size="small" style="width: 120px">
                <el-option
                  v-for="item in meta.options('roomType')"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
              <span class="muted">共 {{ filteredRooms.length }} 间可选</span>
            </div>

            <!-- 房间表同样走三态：加载失败必须和"确实没有可订房间"区分开 -->
            <AsyncState
              :loading="approveLoading"
              :error="approveError"
              :trace-id="approveTraceId"
              :empty="filteredRooms.length === 0"
              skeleton="list"
              :skeleton-rows="5"
              compact
              empty-title="没有符合该申请条件的可订房间"
              empty-description="该区域在申请日期区间内没有可订床位，可先驳回或联系员工调整日期/区域。"
              @retry="retryApproveRooms"
            >
            <el-table
              :data="filteredRooms"
              height="380"
              border
              size="small"
              highlight-current-row
              :current-row-key="selectedRoomId ?? undefined"
              row-key="roomId"
              @current-change="(row: RoomAvailabilityRow | null) => row && handleSelectRoom(row)"
            >
              <el-table-column width="46">
                <template #default="{ row }">
                  <el-radio
                    :model-value="selectedRoomId"
                    :value="row.roomId"
                    @change="handleSelectRoom(row)"
                  >
                    <span />
                  </el-radio>
                </template>
              </el-table-column>
              <el-table-column prop="roomNo" label="房间号" width="80" />
              <el-table-column prop="buildingName" label="公寓" min-width="130" show-overflow-tooltip />
              <el-table-column prop="city" label="城市" width="90" />
              <el-table-column label="房型" width="90">
                <template #default="{ row }">{{ row.roomTypeLabel ?? meta.label('roomType', row.roomType) }}</template>
              </el-table-column>
              <el-table-column label="可订" width="70" align="center">
                <template #default="{ row }">
                  <b class="beds-available">{{ row.availableBeds ?? 0 }}</b>
                  <span class="muted">/{{ row.totalBeds ?? row.capacity }}</span>
                </template>
              </el-table-column>
              <el-table-column label="单价" width="110">
                <template #default="{ row }">
                  <span v-if="row.pricePerNight != null">{{ row.pricePerNight }} {{ row.currency ?? '' }}</span>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="预计总价" width="110">
                <template #default="{ row }">
                  <span v-if="estimatedTotal(row) != null" class="total-price">
                    {{ estimatedTotal(row) }}
                  </span>
                  <span v-else>-</span>
                </template>
              </el-table-column>
            </el-table>
            </AsyncState>
          </el-col>

          <!-- 右侧：床位明细 -->
          <el-col :xs="24" :md="10">
            <div class="panel-title">② 选择床位</div>
            <!-- "还没选房间"是**真的空**（不是错误，也不是加载中），用空态并给出下一步 -->
            <div v-if="!selectedRoom" class="panel-empty">
              <AsyncState
                compact
                empty
                empty-title="请先在左侧选择一个房间"
                empty-description="选中房间后，这里会列出该房间的床位与该日期区间内是否可订。"
              />
            </div>
            <template v-else>
              <el-descriptions :column="isMobile ? 1 : 2" border size="small" style="margin-bottom: 10px">
                <el-descriptions-item label="房间号">{{ selectedRoom.roomNo }}</el-descriptions-item>
                <el-descriptions-item label="公寓">{{ selectedRoom.buildingName ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="房型">
                  {{ selectedRoom.roomTypeLabel ?? meta.label('roomType', selectedRoom.roomType) }}
                </el-descriptions-item>
                <el-descriptions-item label="性别限制">
                  {{ meta.label('genderLimit', selectedRoom.genderLimit) }}
                </el-descriptions-item>
                <el-descriptions-item label="地址" :span="isMobile ? 1 : 2">{{ selectedRoom.address ?? '-' }}</el-descriptions-item>
              </el-descriptions>

              <AsyncState
                :loading="bedsLoading"
                :error="bedsError"
                :trace-id="bedsTraceId"
                :empty="beds.length === 0"
                skeleton="list"
                :skeleton-rows="4"
                compact
                empty-title="该房间暂无床位数据"
                empty-description="房间创建时应按房型容量生成床位；为空请联系管理员检查房间配置。"
                @retry="retryBeds"
              >
                <el-table
                  :data="beds"
                  height="250"
                  border
                  size="small"
                  highlight-current-row
                  @current-change="(row: RoomBedDetail | null) => row && bedSelectable(row) && (selectedBedId = row.bedId)"
                >
                  <el-table-column width="46">
                    <template #default="{ row }">
                      <el-radio
                        v-model="selectedBedId"
                        :value="row.bedId"
                        :disabled="!bedSelectable(row)"
                      >
                        <span />
                      </el-radio>
                    </template>
                  </el-table-column>
                  <el-table-column prop="bedNo" label="床位号" width="80" />
                  <el-table-column label="状态" width="90">
                    <template #default="{ row }">
                      <StatusTag enum-key="bedStatus" :value="row.status" />
                    </template>
                  </el-table-column>
                  <el-table-column label="区间可订" width="90">
                    <template #default="{ row }">
                      <el-tag v-if="row.availableForRange === false" size="small" type="danger">不可订</el-tag>
                      <el-tag v-else size="small" type="success">可订</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="占用信息" min-width="150">
                    <template #default="{ row }">
                      <div v-if="row.employeeName">{{ row.employeeName }}</div>
                      <div v-if="row.occupiedCheckIn" class="muted mono">
                        {{ row.occupiedCheckIn }} ~ {{ row.occupiedCheckOut }}
                      </div>
                      <span v-if="!row.employeeName && !row.occupiedCheckIn" class="muted">-</span>
                    </template>
                  </el-table-column>
                </el-table>
              </AsyncState>
            </template>
          </el-col>
        </el-row>

        <el-form label-width="80px" style="margin-top: 14px">
          <el-form-item label="审批备注">
            <el-input v-model="approveRemark" placeholder="选填" maxlength="200" />
          </el-form-item>
        </el-form>
      </template>

      <template #footer>
        <span class="muted" style="float: left; line-height: 32px">
          <template v-if="selectedRoomId && selectedBedId">
            已选：房间 {{ selectedRoom?.roomNo }} / 床位
            {{ beds.find((b) => b.bedId === selectedBedId)?.bedNo }}
          </template>
          <template v-else>请选择房间与床位后确认</template>
        </span>
        <el-button @click="approveVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!canSubmitApprove" :loading="submitting" @click="submitApprove">
          确认审批并分房
        </el-button>
      </template>
    </el-dialog>

    <!-- ------------------------------ 换房 ------------------------------ -->
    <el-dialog v-model="reassignVisible" title="换房" width="620px" append-to-body>
      <template v-if="reassignApply">
        <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px">
          <template #title>
            申请 <b class="mono">{{ reassignApply.applyNo }}</b> 当前房间
            {{ reassignApply.roomNo ?? '-' }} / 床位 {{ reassignApply.bedNo ?? '-' }}，
            请选择同房间内的其他空闲床位。
          </template>
        </el-alert>
        <AsyncState
          :loading="reassignLoading"
          :empty="reassignBeds.length === 0"
          skeleton="list"
          :skeleton-rows="4"
          compact
          empty-title="该房间暂无可换的床位"
          empty-description="同房间内没有可用的空闲床位，可先释放其他床位或改为驳回后重新分配。"
        >
        <el-table
          :data="reassignBeds"
          border
          size="small"
          height="300"
          @current-change="(row: RoomBedDetail | null) => row && bedSelectable(row) && (reassignBedId = row.bedId)"
        >
          <el-table-column width="46">
            <template #default="{ row }">
              <el-radio v-model="reassignBedId" :value="row.bedId" :disabled="!bedSelectable(row)">
                <span />
              </el-radio>
            </template>
          </el-table-column>
          <el-table-column prop="bedNo" label="床位号" width="90" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <StatusTag enum-key="bedStatus" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column label="占用信息" min-width="180">
            <template #default="{ row }">
              <span v-if="row.employeeName">{{ row.employeeName }}</span>
              <span v-else class="muted">空闲</span>
            </template>
          </el-table-column>
        </el-table>
        </AsyncState>
        <el-form label-width="80px" style="margin-top: 12px">
          <el-form-item label="换房原因">
            <el-input v-model="reassignReason" placeholder="选填" maxlength="200" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="reassignVisible = false">取消</el-button>
        <el-button type="primary" :disabled="reassignBedId === null" :loading="submitting" @click="submitReassign">
          确认换房
        </el-button>
      </template>
    </el-dialog>

    <ApplyDetailDrawer
      v-model="drawerVisible"
      :apply-id="currentApplyId"
      admin-mode
      @changed="() => { load(); loadStats() }"
    />
  </div>
</template>

<style scoped>
.panel-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 8px;
}

.panel-empty {
  border: 1px dashed var(--d-border);
  border-radius: var(--d-radius-md);
  /* 原来是写死的 380px；窄屏单列堆叠后固定高度会把页面拉得很长，改为自适应 */
  min-height: 160px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 可订床位数：原来写死 #409eff，深色主题下偏暗；改用令牌 */
.beds-available {
  color: var(--d-brand);
}

.total-price {
  color: var(--d-danger-text);
}
</style>
