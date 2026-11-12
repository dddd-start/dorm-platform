<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { roomApi } from '@/api/room'
import { useMetaStore } from '@/stores/meta'
import { toLoadError } from '@/api/http'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AsyncState from './AsyncState.vue'
import StatusTag from './StatusTag.vue'
import type { RoomBedDetail, RoomVO } from '@/api/types'

/**
 * 房间床位明细对话框。
 * 数据来自 `/api/rooms/{id}/beds`，展示每个床位的状态与占用起止日期；
 * 支持传入 checkInDate/checkOutDate，用后端返回的 availableForRange 标注该区间是否可订。
 */
const props = defineProps<{
  modelValue: boolean
  roomId?: number | null
  room?: RoomVO | null
  checkInDate?: string
  checkOutDate?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'apply', room: RoomVO | null): void
}>()

const meta = useMetaStore()
const { isMobile } = useBreakpoint()
const loading = ref(false)
const beds = ref<RoomBedDetail[]>([])
/** 床位加载失败必须与"该房间没有床位"区分 */
const loadError = ref('')
const loadTraceId = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

async function load(): Promise<void> {
  if (!props.roomId) {
    beds.value = []
    return
  }
  loading.value = true
  loadError.value = ''
  loadTraceId.value = ''
  try {
    beds.value = await roomApi.beds(props.roomId)
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    loadError.value = message
    loadTraceId.value = traceId
    beds.value = []
  } finally {
    loading.value = false
  }
}

/** 重试加载床位（由错误态的重试按钮触发） */
async function reload(): Promise<void> {
  await load()
}

watch(
  () => [props.modelValue, props.roomId] as const,
  ([open]) => {
    if (open) void load()
  },
)

const hasRange = computed(() => Boolean(props.checkInDate && props.checkOutDate))
</script>

<template>
  <el-dialog v-model="visible" title="床位占用明细" width="760px" append-to-body>
    <!-- 窄屏改单列：3 列描述在 390px 下每格只剩 ~110px -->
    <el-descriptions v-if="room" :column="isMobile ? 1 : 3" border size="small" style="margin-bottom: 14px">
      <el-descriptions-item label="房间号">{{ room.roomNo }}</el-descriptions-item>
      <el-descriptions-item label="公寓">{{ room.buildingName ?? '-' }}</el-descriptions-item>
      <el-descriptions-item label="区域">{{ room.regionName ?? '-' }}</el-descriptions-item>
      <el-descriptions-item label="房型">{{ room.roomTypeLabel ?? meta.label('roomType', room.roomType) }}</el-descriptions-item>
      <el-descriptions-item label="性别限制">{{ room.genderLimitLabel ?? meta.label('genderLimit', room.genderLimit) }}</el-descriptions-item>
      <el-descriptions-item label="价格">
        <span v-if="room.pricePerNight != null" class="price-text">
          {{ room.pricePerNight }} {{ room.currency ?? '' }} / 晚
        </span>
        <span v-else>-</span>
      </el-descriptions-item>
      <el-descriptions-item label="设施" :span="isMobile ? 1 : 3">
        <el-tag
          v-for="f in room.facilities ?? []"
          :key="f"
          size="small"
          effect="plain"
          style="margin-right: 4px"
        >
          {{ f }}
        </el-tag>
        <span v-if="!room.facilities?.length" class="muted">-</span>
      </el-descriptions-item>
    </el-descriptions>

    <el-alert
      v-if="hasRange"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
      :title="`查询区间：${checkInDate} 至 ${checkOutDate}（下方「区间可订」列基于该区间计算）`"
    />

    <!-- 三态：骨架（加载）/ 错误（可重试 + traceId）/ 空（该房间确实没有床位） -->
    <AsyncState
      :loading="loading"
      :error="loadError"
      :trace-id="loadTraceId"
      :empty="beds.length === 0"
      skeleton="list"
      :skeleton-rows="4"
      compact
      empty-title="该房间暂无床位数据"
      empty-description="房间创建时应按房型容量生成床位；为空请联系管理员检查房间配置。"
      @retry="reload"
    >
    <el-table :data="beds" border size="small">
      <el-table-column prop="bedNo" label="床位号" width="90" />
      <el-table-column label="当前状态" width="110">
        <template #default="{ row }">
          <StatusTag enum-key="bedStatus" :value="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="区间可订" width="110">
        <template #default="{ row }">
          <el-tag v-if="!hasRange" size="small" type="info" effect="plain">未指定区间</el-tag>
          <el-tag v-else-if="row.availableForRange" size="small" type="success">可订</el-tag>
          <el-tag v-else size="small" type="danger">不可订</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="employeeName" label="入住人" width="110">
        <template #default="{ row }">{{ row.employeeName ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="applyNo" label="申请单号" min-width="150">
        <template #default="{ row }">{{ row.applyNo ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="占用起止日期" min-width="200">
        <template #default="{ row }">
          <span v-if="row.occupiedCheckIn || row.occupiedCheckOut" class="mono">
            {{ row.occupiedCheckIn ?? '?' }} ~ {{ row.occupiedCheckOut ?? '?' }}
          </span>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
    </el-table>
    </AsyncState>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" @click="emit('apply', room ?? null)">申请入住</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 价格：原来写死 #f56c6c，深色主题下偏暗；改用令牌的危险色文字档 */
.price-text {
  color: var(--d-danger-text);
  font-weight: var(--d-font-weight-semibold);
}
</style>
