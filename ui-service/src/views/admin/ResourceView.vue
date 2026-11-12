<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, ElNotification, type FormInstance, type FormRules } from 'element-plus'
import { buildingApi } from '@/api/building'
import { roomApi } from '@/api/room'
import { useMetaStore } from '@/stores/meta'
import { useRegionStore } from '@/stores/region'
import { toLoadError } from '@/api/http'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AsyncState from '@/components/AsyncState.vue'
import StatusTag from '@/components/StatusTag.vue'
import type {
  BedVO,
  BuildingOption,
  BuildingSaveRequest,
  BuildingVO,
  RoomSaveRequest,
  RoomStatus,
  RoomVO,
} from '@/api/types'

/** 房源管理：公寓 CRUD + 房间 CRUD + 批量开关房 + 床位启用/停用 */

const meta = useMetaStore()
const regionStore = useRegionStore()
const { isMobile } = useBreakpoint()
const activeTab = ref('building')

/* ============================== 公寓管理 ============================== */
const buildingLoading = ref(false)
const buildings = ref<BuildingVO[]>([])
const buildingTotal = ref(0)
/** 错误态（加载失败，带重试 + traceId）与空态（确实没有公寓）必须分开 */
const buildingError = ref('')
const buildingTraceId = ref('')
const buildingQuery = reactive({
  current: 1,
  size: 10,
  regionId: undefined as number | undefined,
  keyword: '',
  status: undefined as number | undefined,
})

/** 是否处于筛选状态 —— 决定空态文案 */
const buildingFiltered = computed(() =>
  Boolean(buildingQuery.regionId !== undefined || buildingQuery.keyword || buildingQuery.status !== undefined),
)

const buildingDialogVisible = ref(false)
const buildingSaving = ref(false)
const buildingFormRef = ref<FormInstance>()
const buildingForm = reactive<BuildingSaveRequest>({
  id: undefined,
  regionId: 0,
  code: '',
  name: '',
  country: '',
  city: '',
  address: '',
  contactName: '',
  contactPhone: '',
  status: 1,
  remark: '',
})

const buildingRules: FormRules = {
  regionId: [{ required: true, message: '请选择所属区域', trigger: 'change' }],
  code: [{ required: true, message: '请输入公寓编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入公寓名称', trigger: 'blur' }],
}

async function loadBuildings(): Promise<void> {
  buildingLoading.value = true
  buildingError.value = ''
  buildingTraceId.value = ''
  try {
    const page = await buildingApi.page({
      current: buildingQuery.current,
      size: buildingQuery.size,
      regionId: buildingQuery.regionId,
      keyword: buildingQuery.keyword || undefined,
      status: buildingQuery.status,
    })
    buildings.value = page.records ?? []
    buildingTotal.value = page.total ?? 0
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    buildingError.value = message
    buildingTraceId.value = traceId
    buildings.value = []
    buildingTotal.value = 0
  } finally {
    buildingLoading.value = false
  }
}

/** 重置公寓筛选条件（空态里的动作入口） */
function resetBuildingQuery(): void {
  buildingQuery.current = 1
  buildingQuery.regionId = undefined
  buildingQuery.keyword = ''
  buildingQuery.status = undefined
  void loadBuildings()
}

function openBuildingCreate(): void {
  Object.assign(buildingForm, {
    id: undefined,
    regionId: buildingQuery.regionId ?? regionStore.regions[0]?.id ?? 0,
    code: '',
    name: '',
    country: '',
    city: '',
    address: '',
    contactName: '',
    contactPhone: '',
    status: 1,
    remark: '',
  })
  buildingDialogVisible.value = true
}

function openBuildingEdit(row: BuildingVO): void {
  Object.assign(buildingForm, {
    id: row.id,
    regionId: row.regionId,
    code: row.code,
    name: row.name,
    country: row.country ?? '',
    city: row.city ?? '',
    address: row.address ?? '',
    contactName: row.contactName ?? '',
    contactPhone: row.contactPhone ?? '',
    status: row.status,
    remark: row.remark ?? '',
  })
  buildingDialogVisible.value = true
}

async function submitBuilding(): Promise<void> {
  if (!buildingFormRef.value) return
  const valid = await buildingFormRef.value.validate().catch(() => false)
  if (!valid) return
  buildingSaving.value = true
  try {
    await buildingApi.save({ ...buildingForm })
    ElMessage.success(buildingForm.id ? '公寓已更新' : '公寓已创建')
    buildingDialogVisible.value = false
    await loadBuildings()
  } catch {
    /* 已提示 */
  } finally {
    buildingSaving.value = false
  }
}

async function toggleBuildingStatus(row: BuildingVO): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  const label = next === 1 ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(`确定${label}公寓「${row.name}」吗？`, '提示', {
      type: 'warning',
      confirmButtonText: label,
    })
  } catch {
    return
  }
  await buildingApi.updateStatus(row.id, next as 0 | 1)
  ElMessage.success(`已${label}`)
  await loadBuildings()
}

/* ============================== 房间管理 ============================== */
const roomLoading = ref(false)
const rooms = ref<RoomVO[]>([])
const roomTotal = ref(0)
/** 房间列表的错误态（同样必须与"没有房间"区分） */
const roomError = ref('')
const roomTraceId = ref('')
const roomQuery = reactive({
  current: 1,
  size: 10,
  regionId: undefined as number | undefined,
  buildingId: undefined as number | undefined,
  roomType: '',
  status: '' as RoomStatus | '',
  keyword: '',
})

/** 房间管理里的公寓下拉（按区域联动） */
const roomBuildingOptions = ref<BuildingOption[]>([])
/** 批量操作选中的房间 */
const selectedRooms = ref<RoomVO[]>([])

const roomDialogVisible = ref(false)
const roomSaving = ref(false)
const roomFormRef = ref<FormInstance>()
const roomForm = reactive({
  id: undefined as number | undefined,
  buildingId: undefined as number | undefined,
  roomNo: '',
  floorNo: 1,
  roomType: '' as RoomSaveRequest['roomType'] | '',
  genderLimit: 'ANY' as RoomSaveRequest['genderLimit'],
  areaSqm: undefined as number | undefined,
  pricePerNight: undefined as number | undefined,
  currency: '',
  /** 表单里用数组，提交时 join(',')；编辑时把逗号字符串 split 回数组 */
  facilities: [] as string[],
  remark: '',
})

const facilityOptions = [
  'WIFI',
  'AC',
  'DESK',
  'PRIVATE_BATHROOM',
  'SHARED_BATHROOM',
  'WASHER',
  'KITCHEN',
]

const roomRules: FormRules = {
  buildingId: [{ required: true, message: '请选择所属公寓', trigger: 'change' }],
  roomNo: [{ required: true, message: '请输入房间号', trigger: 'blur' }],
  roomType: [{ required: true, message: '请选择房型', trigger: 'change' }],
  genderLimit: [{ required: true, message: '请选择性别限制', trigger: 'change' }],
}

/** 是否处于筛选状态 —— 决定房间空态文案 */
const roomFiltered = computed(() =>
  Boolean(
    roomQuery.regionId !== undefined ||
      roomQuery.buildingId !== undefined ||
      roomQuery.roomType ||
      roomQuery.status ||
      roomQuery.keyword,
  ),
)

async function loadRooms(): Promise<void> {
  roomLoading.value = true
  roomError.value = ''
  roomTraceId.value = ''
  try {
    const page = await roomApi.page({
      current: roomQuery.current,
      size: roomQuery.size,
      regionId: roomQuery.regionId,
      buildingId: roomQuery.buildingId,
      roomType: (roomQuery.roomType || undefined) as RoomSaveRequest['roomType'] | undefined,
      status: roomQuery.status || undefined,
      keyword: roomQuery.keyword || undefined,
    })
    rooms.value = page.records ?? []
    roomTotal.value = page.total ?? 0
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    roomError.value = message
    roomTraceId.value = traceId
    rooms.value = []
    roomTotal.value = 0
  } finally {
    roomLoading.value = false
  }
}

/** 重置房间筛选条件 */
function resetRoomQuery(): void {
  roomQuery.current = 1
  roomQuery.regionId = undefined
  roomQuery.buildingId = undefined
  roomQuery.roomType = ''
  roomQuery.status = ''
  roomQuery.keyword = ''
  roomBuildingOptions.value = []
  void loadRooms()
}

/** 房间筛选区域变化 → 公寓下拉联动 */
async function handleRoomRegionChange(regionId?: number): Promise<void> {
  roomQuery.buildingId = undefined
  roomBuildingOptions.value = []
  if (regionId) {
    try {
      roomBuildingOptions.value = await buildingApi.options(regionId)
    } catch {
      roomBuildingOptions.value = []
    }
  }
}

function openRoomCreate(): void {
  Object.assign(roomForm, {
    id: undefined,
    buildingId: roomQuery.buildingId,
    roomNo: '',
    floorNo: 1,
    roomType: '',
    genderLimit: 'ANY',
    areaSqm: undefined,
    pricePerNight: undefined,
    currency: regionStore.regions.find((r) => r.id === roomQuery.regionId)?.currency ?? '',
    facilities: [],
    remark: '',
  })
  roomDialogVisible.value = true
}

function openRoomEdit(row: RoomVO): void {
  Object.assign(roomForm, {
    id: row.id,
    buildingId: row.buildingId,
    roomNo: row.roomNo,
    floorNo: row.floorNo ?? 1,
    roomType: row.roomType,
    genderLimit: row.genderLimit,
    areaSqm: row.areaSqm,
    pricePerNight: row.pricePerNight,
    currency: row.currency ?? '',
    // 后端的 facilities 是逗号分隔字符串，这里拆成数组供多选组件使用
    facilities: (row.facilities ?? []).slice(),
    remark: row.remark ?? '',
  })
  roomDialogVisible.value = true
}

async function submitRoom(): Promise<void> {
  if (!roomFormRef.value) return
  const valid = await roomFormRef.value.validate().catch(() => false)
  if (!valid) return
  roomSaving.value = true
  try {
    await roomApi.save({
      id: roomForm.id,
      buildingId: roomForm.buildingId as number,
      roomNo: roomForm.roomNo,
      floorNo: roomForm.floorNo,
      roomType: roomForm.roomType as RoomSaveRequest['roomType'],
      genderLimit: roomForm.genderLimit,
      areaSqm: roomForm.areaSqm,
      pricePerNight: roomForm.pricePerNight,
      currency: roomForm.currency || undefined,
      // 提交时合并为逗号分隔字符串
      facilities: roomForm.facilities.join(','),
      remark: roomForm.remark || undefined,
    })
    ElMessage.success(roomForm.id ? '房间已更新' : '房间已创建')
    roomDialogVisible.value = false
    await loadRooms()
  } catch {
    /* 已提示 */
  } finally {
    roomSaving.value = false
  }
}

/* ------------------------------ 批量开关房 ------------------------------ */
const batchVisible = ref(false)
const batchSaving = ref(false)
const batchForm = reactive({ status: 'CLOSED' as RoomStatus, remark: '' })

function openBatchDialog(): void {
  if (selectedRooms.value.length === 0) {
    ElMessage.warning('请先勾选要操作的房间')
    return
  }
  batchForm.status = 'CLOSED'
  batchForm.remark = ''
  batchVisible.value = true
}

async function submitBatch(): Promise<void> {
  batchSaving.value = true
  try {
    const result = await roomApi.batchStatus({
      roomIds: selectedRooms.value.map((r) => r.id),
      status: batchForm.status,
      remark: batchForm.remark || undefined,
    })
    // 汇总展示成功/失败数量，失败明细用通知列出
    if (result.failCount > 0) {
      const detail = (result.failures ?? [])
        .map((f) => `${f.roomNo ?? f.roomId}：${f.reason ?? '失败'}`)
        .join('\n')
      ElNotification({
        title: `批量操作完成：成功 ${result.successCount} 个，失败 ${result.failCount} 个`,
        message: detail || '部分房间操作失败',
        type: 'warning',
        duration: 8000,
      })
    } else {
      ElMessage.success(`批量操作成功，共 ${result.successCount} 个房间`)
    }
    batchVisible.value = false
    await loadRooms()
  } catch {
    /* 已提示 */
  } finally {
    batchSaving.value = false
  }
}

/* ------------------------------ 床位启用/停用 ------------------------------ */
const bedVisible = ref(false)
const bedLoading = ref(false)
const bedSaving = ref(false)
const bedRoom = ref<RoomVO | null>(null)
const bedList = ref<BedVO[]>([])
const selectedBeds = ref<BedVO[]>([])
/** 床位加载失败也不能画成"没有床位" */
const bedError = ref('')
const bedTraceId = ref('')

async function openBedDialog(row: RoomVO): Promise<void> {
  bedRoom.value = row
  selectedBeds.value = []
  bedVisible.value = true
  await loadBeds(row.id)
}

async function loadBeds(roomId: number): Promise<void> {
  bedLoading.value = true
  bedError.value = ''
  bedTraceId.value = ''
  try {
    const detail = await roomApi.detail(roomId)
    bedList.value = detail.beds ?? []
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    bedError.value = message
    bedTraceId.value = traceId
    bedList.value = []
  } finally {
    bedLoading.value = false
  }
}

/** 重试床位加载 */
async function retryBeds(): Promise<void> {
  if (bedRoom.value) await loadBeds(bedRoom.value.id)
}

async function submitBedStatus(status: 'IDLE' | 'DISABLED'): Promise<void> {
  if (selectedBeds.value.length === 0) {
    ElMessage.warning('请先勾选要操作的床位')
    return
  }
  bedSaving.value = true
  try {
    await roomApi.batchBedStatus({ bedIds: selectedBeds.value.map((b) => b.id), status })
    ElMessage.success(status === 'IDLE' ? '床位已启用' : '床位已停用')
    if (bedRoom.value) await openBedDialog(bedRoom.value)
    await loadRooms()
  } catch {
    /* 已提示 */
  } finally {
    bedSaving.value = false
  }
}

const statusOptions = computed(() => meta.options('roomStatus'))

onMounted(async () => {
  await regionStore.loadRegions().catch(() => undefined)
  await loadBuildings()
  await loadRooms()
})
</script>

<template>
  <div class="page-container">
    <div class="page-card">
      <h3 class="page-title">房源管理</h3>

      <el-tabs v-model="activeTab">
        <!-- ============================== 公寓管理 ============================== -->
        <el-tab-pane label="公寓管理" name="building">
          <div class="filter-bar">
            <el-select v-model="buildingQuery.regionId" placeholder="区域" clearable style="width: 150px" @change="loadBuildings">
              <el-option v-for="r in regionStore.regions" :key="r.id" :label="r.name" :value="r.id" />
            </el-select>
            <el-input
              v-model="buildingQuery.keyword"
              placeholder="编码 / 名称 / 城市"
              clearable
              style="width: 200px"
              @keyup.enter="loadBuildings"
            />
            <el-select v-model="buildingQuery.status" placeholder="状态" clearable style="width: 120px">
              <el-option label="启用" :value="1" />
              <el-option label="停用" :value="0" />
            </el-select>
            <el-button type="primary" @click="loadBuildings">查询</el-button>
            <el-button type="success" @click="openBuildingCreate">
              <el-icon><Plus /></el-icon> 新增公寓
            </el-button>
          </div>

          <!-- 三态边界：加载（骨架）> 出错（重试 + traceId）> 空（区分"未筛选"与"筛选无结果"） -->
          <AsyncState
            :loading="buildingLoading"
            :error="buildingError"
            :trace-id="buildingTraceId"
            :empty="buildings.length === 0"
            skeleton="list"
            :skeleton-rows="5"
            :empty-title="buildingFiltered ? '没有符合条件的公寓' : '还没有公寓数据'"
            :empty-description="
              buildingFiltered
                ? '试试放宽区域 / 关键字 / 状态条件后重新查询。'
                : '点击上方「新增公寓」录入第一个公寓，之后才能创建房间与床位。'
            "
            @retry="loadBuildings"
          >
            <template #empty-action>
              <el-button v-if="buildingFiltered" size="small" @click="resetBuildingQuery">重置筛选条件</el-button>
            </template>

            <!-- 小屏：宽表转卡片。实测 390px 下公寓表内部宽度 1200px、可视容器仅 326px -->
            <div v-if="isMobile" class="m-list" data-test="buildings-cards">
              <el-card v-for="row in buildings" :key="row.id" shadow="never" class="m-card">
                <div class="m-card__head">
                  <span class="m-card__title">{{ row.name }}</span>
                  <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="plain">
                    {{ row.status === 1 ? '启用' : '停用' }}
                  </el-tag>
                </div>
                <div class="m-card__rows">
                  <span class="m-card__label">编码</span>
                  <span class="m-card__value mono">{{ row.code }}</span>
                  <span class="m-card__label">区域</span>
                  <span class="m-card__value">{{ row.regionName ?? '-' }}</span>
                  <span class="m-card__label">国家 / 城市</span>
                  <span class="m-card__value">
                    {{ [row.country, row.city].filter(Boolean).join(' / ') || '-' }}
                  </span>
                  <span class="m-card__label">地址</span>
                  <span class="m-card__value">{{ row.address ?? '-' }}</span>
                  <span class="m-card__label">联系人</span>
                  <span class="m-card__value">
                    {{ row.contactName ?? '-' }}<template v-if="row.contactPhone"> · {{ row.contactPhone }}</template>
                  </span>
                  <span class="m-card__label">房间 / 床位</span>
                  <span class="m-card__value">
                    {{ row.roomCount ?? 0 }} / {{ row.bedCount ?? 0 }}（空闲 {{ row.idleBedCount ?? 0 }}）
                  </span>
                </div>
                <div class="m-card__actions">
                  <el-button link type="primary" @click="openBuildingEdit(row)">编辑</el-button>
                  <el-button
                    link
                    :type="row.status === 1 ? 'danger' : 'success'"
                    @click="toggleBuildingStatus(row)"
                  >
                    {{ row.status === 1 ? '停用' : '启用' }}
                  </el-button>
                </div>
              </el-card>
            </div>

            <el-table v-else :data="buildings" border stripe>
              <el-table-column prop="code" label="编码" min-width="140">
                <template #default="{ row }">
                  <span class="mono">{{ row.code }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="name" label="公寓名称" min-width="150" show-overflow-tooltip />
              <el-table-column prop="regionName" label="区域" width="100">
                <template #default="{ row }">{{ row.regionName ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="国家 / 城市" min-width="130">
                <template #default="{ row }">
                  {{ [row.country, row.city].filter(Boolean).join(' / ') || '-' }}
                </template>
              </el-table-column>
              <el-table-column prop="address" label="地址" min-width="200" show-overflow-tooltip>
                <template #default="{ row }">{{ row.address ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="联系人" width="120">
                <template #default="{ row }">
                  <div>{{ row.contactName ?? '-' }}</div>
                  <div class="muted">{{ row.contactPhone ?? '' }}</div>
                </template>
              </el-table-column>
              <el-table-column label="房间 / 床位" width="120" align="center">
                <template #default="{ row }">
                  {{ row.roomCount ?? 0 }} / {{ row.bedCount ?? 0 }}
                  <div class="muted">空闲 {{ row.idleBedCount ?? 0 }}</div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="plain">
                    {{ row.status === 1 ? '启用' : '停用' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="150" :fixed="isMobile ? undefined : 'right'">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openBuildingEdit(row)">编辑</el-button>
                  <el-button
                    link
                    :type="row.status === 1 ? 'danger' : 'success'"
                    @click="toggleBuildingStatus(row)"
                  >
                    {{ row.status === 1 ? '停用' : '启用' }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>

            <div v-if="buildings.length > 0" class="pager">
              <el-pagination
                v-model:current-page="buildingQuery.current"
                v-model:page-size="buildingQuery.size"
                :total="buildingTotal"
                :page-sizes="[10, 20, 50]"
                layout="total, sizes, prev, pager, next"
                @current-change="loadBuildings"
                @size-change="loadBuildings"
              />
            </div>
          </AsyncState>
        </el-tab-pane>

        <!-- ============================== 房间管理 ============================== -->
        <el-tab-pane label="房间管理" name="room">
          <div class="filter-bar">
            <el-select
              v-model="roomQuery.regionId"
              placeholder="区域"
              clearable
              style="width: 140px"
              @change="handleRoomRegionChange"
            >
              <el-option v-for="r in regionStore.regions" :key="r.id" :label="r.name" :value="r.id" />
            </el-select>
            <el-select
              v-model="roomQuery.buildingId"
              placeholder="公寓"
              clearable
              filterable
              :disabled="!roomQuery.regionId"
              style="width: 180px"
            >
              <el-option v-for="b in roomBuildingOptions" :key="b.id" :label="b.name" :value="b.id" />
            </el-select>
            <el-select v-model="roomQuery.roomType" placeholder="房型" clearable style="width: 120px">
              <el-option
                v-for="item in meta.options('roomType')"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-select v-model="roomQuery.status" placeholder="房间状态" clearable style="width: 130px">
              <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-input
              v-model="roomQuery.keyword"
              placeholder="房间号 / 公寓"
              clearable
              style="width: 170px"
              @keyup.enter="loadRooms"
            />
            <el-button type="primary" @click="loadRooms">查询</el-button>
            <el-button type="success" @click="openRoomCreate">
              <el-icon><Plus /></el-icon> 新增房间
            </el-button>
            <el-button type="warning" :disabled="selectedRooms.length === 0" @click="openBatchDialog">
              批量开关房（{{ selectedRooms.length }}）
            </el-button>
          </div>

          <!-- 房间表**刻意保留表格**：它带多选（批量开关房依赖 selection），
               转卡片需要重新实现勾选与"全选当前页"语义，收益不足以抵消风险。
               窄屏改用横向滚动 + 提示条，并取消 fixed 操作列。 -->
          <div class="scroll-hint">
            <el-icon><DCaret /></el-icon><span>左右滑动查看更多列（可勾选后批量开关房）</span>
          </div>

          <AsyncState
            :loading="roomLoading"
            :error="roomError"
            :trace-id="roomTraceId"
            :empty="rooms.length === 0"
            skeleton="list"
            :skeleton-rows="5"
            :empty-title="roomFiltered ? '没有符合条件的房间' : '还没有房间数据'"
            :empty-description="
              roomFiltered
                ? '试试放宽区域 / 公寓 / 房型 / 状态条件后重新查询。'
                : '先创建公寓，再点击「新增房间」录入房间与床位。'
            "
            @retry="loadRooms"
          >
            <template #empty-action>
              <el-button v-if="roomFiltered" size="small" @click="resetRoomQuery">重置筛选条件</el-button>
            </template>

            <el-table
              :data="rooms"
              border
              stripe
              @selection-change="(rows: RoomVO[]) => (selectedRooms = rows)"
            >
              <el-table-column type="selection" width="46" />
              <el-table-column prop="roomNo" label="房间号" width="90" />
              <el-table-column prop="buildingName" label="公寓" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.buildingName ?? '-' }}</template>
              </el-table-column>
              <el-table-column prop="regionName" label="区域" width="100">
                <template #default="{ row }">{{ row.regionName ?? '-' }}</template>
              </el-table-column>
              <el-table-column prop="floorNo" label="楼层" width="70" align="center">
                <template #default="{ row }">{{ row.floorNo ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="房型" width="90">
                <template #default="{ row }">{{ row.roomTypeLabel ?? meta.label('roomType', row.roomType) }}</template>
              </el-table-column>
              <el-table-column label="性别" width="80">
                <template #default="{ row }">
                  {{ row.genderLimitLabel ?? meta.label('genderLimit', row.genderLimit) }}
                </template>
              </el-table-column>
              <el-table-column label="床位（空闲/占用/停用）" width="170" align="center">
                <template #default="{ row }">
                  <span class="bed-idle">{{ row.idleBeds ?? 0 }}</span> /
                  <span class="bed-occupied">{{ row.occupiedBeds ?? 0 }}</span> /
                  <span class="muted">{{ row.disabledBeds ?? 0 }}</span>
                  <span class="muted"> （共 {{ row.capacity }}）</span>
                </template>
              </el-table-column>
              <el-table-column label="单价" width="120">
                <template #default="{ row }">
                  <span v-if="row.pricePerNight != null">
                    {{ row.pricePerNight }} {{ row.currency ?? '' }}
                  </span>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <StatusTag enum-key="roomStatus" :value="row.status" />
                </template>
              </el-table-column>
              <el-table-column label="设施" min-width="180">
                <template #default="{ row }">
                  <el-tag
                    v-for="f in row.facilities ?? []"
                    :key="f"
                    size="small"
                    effect="plain"
                    style="margin: 0 3px 3px 0"
                  >
                    {{ f }}
                  </el-tag>
                  <span v-if="!(row.facilities ?? []).length" class="muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="170" :fixed="isMobile ? undefined : 'right'">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openRoomEdit(row)">编辑</el-button>
                  <el-button link type="warning" @click="openBedDialog(row)">床位管理</el-button>
                </template>
              </el-table-column>
            </el-table>

            <div v-if="rooms.length > 0" class="pager">
              <el-pagination
                v-model:current-page="roomQuery.current"
                v-model:page-size="roomQuery.size"
                :total="roomTotal"
                :page-sizes="[10, 20, 50]"
                layout="total, sizes, prev, pager, next"
                @current-change="loadRooms"
                @size-change="loadRooms"
              />
            </div>
          </AsyncState>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- ------------------------------ 公寓表单 ------------------------------ -->
    <el-dialog
      v-model="buildingDialogVisible"
      :title="buildingForm.id ? '编辑公寓' : '新增公寓'"
      width="620px"
      append-to-body
    >
      <el-form ref="buildingFormRef" :model="buildingForm" :rules="buildingRules" label-width="100px">
        <el-form-item label="所属区域" prop="regionId">
          <el-select v-model="buildingForm.regionId" placeholder="请选择区域" style="width: 100%">
            <el-option v-for="r in regionStore.regions" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="公寓编码" prop="code">
          <el-input v-model="buildingForm.code" placeholder="例如：SG-ONE-NORTH" />
        </el-form-item>
        <el-form-item label="公寓名称" prop="name">
          <el-input v-model="buildingForm.name" placeholder="例如：新加坡纬壹公寓" />
        </el-form-item>
        <el-form-item label="国家">
          <el-input v-model="buildingForm.country" />
        </el-form-item>
        <el-form-item label="城市">
          <el-input v-model="buildingForm.city" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="buildingForm.address" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="buildingForm.contactName" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="buildingForm.contactPhone" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="buildingForm.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="buildingForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="buildingDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="buildingSaving" @click="submitBuilding">保存</el-button>
      </template>
    </el-dialog>

    <!-- ------------------------------ 房间表单 ------------------------------ -->
    <el-dialog
      v-model="roomDialogVisible"
      :title="roomForm.id ? '编辑房间' : '新增房间'"
      width="640px"
      append-to-body
    >
      <el-form ref="roomFormRef" :model="roomForm" :rules="roomRules" label-width="100px">
        <el-form-item label="所属公寓" prop="buildingId">
          <el-select
            v-model="roomForm.buildingId"
            placeholder="请选择公寓"
            filterable
            style="width: 100%"
            @change="(v: number) => {
              const b = roomBuildingOptions.find((x) => x.id === v)
              if (b && !roomForm.currency) {
                const rid = roomQuery.regionId ?? b.regionId
                roomForm.currency = regionStore.regions.find((r) => r.id === rid)?.currency ?? ''
              }
            }"
          >
            <el-option v-for="b in roomBuildingOptions" :key="b.id" :label="b.name" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="房间号" prop="roomNo">
          <el-input v-model="roomForm.roomNo" placeholder="例如：301" />
        </el-form-item>
        <el-form-item label="楼层">
          <el-input-number v-model="roomForm.floorNo" :min="1" :max="99" />
        </el-form-item>
        <el-form-item label="房型" prop="roomType">
          <el-select v-model="roomForm.roomType" placeholder="请选择房型" style="width: 100%">
            <el-option
              v-for="item in meta.options('roomType')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="性别限制" prop="genderLimit">
          <el-select v-model="roomForm.genderLimit" style="width: 100%">
            <el-option
              v-for="item in meta.options('genderLimit')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="面积（㎡）">
          <el-input-number v-model="roomForm.areaSqm" :min="1" :max="500" :precision="1" />
        </el-form-item>
        <el-form-item label="单晚价格">
          <el-input-number v-model="roomForm.pricePerNight" :min="0" :precision="2" :step="10" />
        </el-form-item>
        <el-form-item label="币种">
          <el-input v-model="roomForm.currency" placeholder="例如：SGD / EUR" style="width: 200px" />
        </el-form-item>
        <el-form-item label="设施">
          <!-- 后端要求逗号分隔字符串，这里用数组多选，提交时 join -->
          <el-select
            v-model="roomForm.facilities"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入设施"
            style="width: 100%"
          >
            <el-option v-for="f in facilityOptions" :key="f" :label="f" :value="f" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="roomForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roomDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="roomSaving" @click="submitRoom">保存</el-button>
      </template>
    </el-dialog>

    <!-- ------------------------------ 批量开关房 ------------------------------ -->
    <el-dialog v-model="batchVisible" title="批量开关房" width="520px" append-to-body>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 14px"
        :title="`将对已选中的 ${selectedRooms.length} 个房间执行状态变更`"
      />
      <el-form label-width="90px">
        <el-form-item label="目标状态">
          <el-select v-model="batchForm.status" style="width: 100%">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="batchForm.remark" type="textarea" :rows="2" placeholder="例如：季度维修" />
        </el-form-item>
        <el-form-item label="房间明细">
          <div style="max-height: 150px; overflow: auto">
            <el-tag v-for="r in selectedRooms" :key="r.id" size="small" style="margin: 0 4px 4px 0">
              {{ r.roomNo }}
            </el-tag>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSaving" @click="submitBatch">确认执行</el-button>
      </template>
    </el-dialog>

    <!-- ------------------------------ 床位管理 ------------------------------ -->
    <el-dialog v-model="bedVisible" title="床位启用 / 停用" width="620px" append-to-body>
      <template v-if="bedRoom">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
          <template #title>
            房间 <b>{{ bedRoom.roomNo }}</b> · {{ bedRoom.buildingName ?? '-' }} ·
            共 {{ bedList.length }} 个床位
          </template>
        </el-alert>
        <!-- 床位明细同样走三态：加载骨架 / 失败可重试 / 确实没有床位 -->
        <AsyncState
          :loading="bedLoading"
          :error="bedError"
          :trace-id="bedTraceId"
          :empty="bedList.length === 0"
          skeleton="list"
          :skeleton-rows="4"
          compact
          empty-title="该房间还没有床位"
          empty-description="房间创建时会按房型容量自动生成床位；若为空请联系管理员检查房间配置。"
          @retry="retryBeds"
        >
          <el-table
            :data="bedList"
            border
            size="small"
            height="320"
            @selection-change="(rows: BedVO[]) => (selectedBeds = rows)"
          >
            <el-table-column type="selection" width="46" />
            <el-table-column prop="bedNo" label="床位号" width="100" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <StatusTag enum-key="bedStatus" :value="row.status" />
              </template>
            </el-table-column>
            <el-table-column label="当前申请" min-width="180">
              <template #default="{ row }">
                <span class="mono">{{ row.currentApplyId ?? '-' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </AsyncState>
        <div class="muted" style="margin-top: 8px">
          已选中 {{ selectedBeds.length }} 个床位。停用后该床位不再参与分配。
        </div>
      </template>
      <template #footer>
        <el-button @click="bedVisible = false">关闭</el-button>
        <el-button type="success" :loading="bedSaving" @click="submitBedStatus('IDLE')">启用选中</el-button>
        <el-button type="danger" :loading="bedSaving" @click="submitBedStatus('DISABLED')">停用选中</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 床位计数：原来写死 #67c23a / #f56c6c，深色主题下都偏暗；
   改用令牌的语义文字色，两个主题下都由令牌保证可读性。 */
.bed-idle {
  color: var(--d-success-text);
}

.bed-occupied {
  color: var(--d-danger-text);
}
</style>
