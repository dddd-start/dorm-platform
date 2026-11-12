<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { applyApi } from '@/api/apply'
import { orderApi } from '@/api/order'
import { toLoadError } from '@/api/http'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AsyncState from './AsyncState.vue'
import StatusTag from './StatusTag.vue'
import type { OrderVO } from '@/api/types'

/**
 * 订单详情抽屉 + 办理入住 / 办理退房。
 * 退房时若当前日期早于原定退房日，强制填写「提前退房原因」。
 */
const props = defineProps<{
  modelValue: boolean
  orderId?: string | null
  order?: OrderVO | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'changed'): void
}>()

const { isMobile } = useBreakpoint()

const detail = ref<OrderVO | null>(null)
const loading = ref(false)
const actionLoading = ref(false)
/** 详情加载失败：必须给出重试与 traceId，而不是显示"暂无详情" */
const loadError = ref('')
const loadTraceId = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const canCheckIn = computed(() => detail.value?.status === 'RESERVED')
const canCheckOut = computed(() => detail.value?.status === 'CHECKED_IN')

async function load(): Promise<void> {
  if (!props.orderId) {
    detail.value = props.order ?? null
    return
  }
  loading.value = true
  loadError.value = ''
  loadTraceId.value = ''
  try {
    detail.value = await orderApi.detail(props.orderId)
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    loadError.value = message
    loadTraceId.value = traceId
    detail.value = null
  } finally {
    loading.value = false
  }
}

/** 重试加载订单详情（由错误态的重试按钮触发） */
async function reload(): Promise<void> {
  await load()
}

watch(
  () => [props.modelValue, props.orderId] as const,
  ([open]) => {
    if (open) void load()
  },
)

async function handleCheckIn(): Promise<void> {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm('确认办理入住？', '办理入住', {
      confirmButtonText: '确认入住',
      cancelButtonText: '取消',
      type: 'info',
    })
  } catch {
    return
  }
  actionLoading.value = true
  try {
    await applyApi.checkIn({
      applyId: detail.value.applyId ?? '',
      remark: `订单 ${detail.value.orderNo} 办理入住`,
    })
    ElMessage.success('入住成功')
    await load()
    emit('changed')
  } catch {
    /* 已提示 */
  } finally {
    actionLoading.value = false
  }
}

async function handleCheckOut(): Promise<void> {
  if (!detail.value) return
  const today = new Date().toISOString().slice(0, 10)
  const planned = detail.value.checkOutDate ?? ''
  const isEarly = Boolean(planned) && today < planned

  try {
    const { value } = await ElMessageBox.prompt(
      isEarly
        ? `当前日期 ${today} 早于原定退房日 ${planned}，请填写提前退房原因（必填）`
        : '确认办理退房？可填写备注',
      isEarly ? '提前退房' : '办理退房',
      {
        confirmButtonText: '确认退房',
        cancelButtonText: '取消',
        inputPlaceholder: isEarly ? '例如：项目提前结束' : '备注（可不填）',
        inputValidator: (v: string) => {
          if (isEarly && (!v || v.trim().length === 0)) return '提前退房必须填写原因'
          return true
        },
      },
    )
    actionLoading.value = true
    await applyApi.checkOut({
      orderId: detail.value.id,
      earlyCheckOutReason: isEarly ? value : undefined,
      remark: isEarly ? undefined : value || undefined,
    })
    ElMessage.success('退房成功')
    await load()
    emit('changed')
  } catch {
    /* 已提示 */
  } finally {
    actionLoading.value = false
  }
}
</script>

<template>
  <el-drawer v-model="visible" title="订单详情" size="600px">
    <!-- 三态：骨架（加载）/ 错误（可重试 + traceId）/ 空（确实没有详情） -->
    <AsyncState
      :loading="loading"
      :error="loadError"
      :trace-id="loadTraceId"
      :empty="!detail"
      skeleton="detail"
      compact
      empty-title="暂无详情"
      empty-description="该订单没有可展示的明细数据。"
      @retry="reload"
    >
      <template v-if="detail">
        <el-descriptions :column="isMobile ? 1 : 2" border size="small">
          <el-descriptions-item label="订单号">
            <span class="mono">{{ detail.orderNo }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag enum-key="orderStatus" :value="detail.status" :override-color="detail.statusColor" />
          </el-descriptions-item>
          <el-descriptions-item label="关联申请">
            <span class="mono">{{ detail.applyNo ?? '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="入住人">{{ detail.employeeName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="区域">{{ detail.regionName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="公寓">{{ detail.buildingName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="房间 / 床位">
            {{ detail.roomNo ?? '-' }} / {{ detail.bedNo ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="住宿晚数">{{ detail.nights ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="计划入住">{{ detail.checkInDate ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="计划退房">{{ detail.checkOutDate ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="实际入住">{{ detail.realCheckInTime ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="实际退房">{{ detail.realCheckOutTime ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="金额">
            <span class="amount">
              {{ detail.amount ?? '-' }} {{ detail.currency ?? '' }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="来源">{{ detail.source ?? '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.earlyCheckOutReason" label="提前退房原因" :span="isMobile ? 1 : 2">
            {{ detail.earlyCheckOutReason }}
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="isMobile ? 1 : 2">{{ detail.remark ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="isMobile ? 1 : 2">{{ detail.createTime ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <div style="margin-top: 18px; display: flex; gap: 10px">
          <el-button v-if="canCheckIn" type="success" :loading="actionLoading" @click="handleCheckIn">
            办理入住
          </el-button>
          <el-button v-if="canCheckOut" type="warning" :loading="actionLoading" @click="handleCheckOut">
            办理退房
          </el-button>
          <span v-if="!canCheckIn && !canCheckOut" class="muted" style="line-height: 32px">
            当前订单状态无可执行操作
          </span>
        </div>
      </template>
    </AsyncState>
  </el-drawer>
</template>

<style scoped>
/* 金额：原来写死 #f56c6c，深色主题下偏暗；改用令牌的危险色文字档 */
.amount {
  color: var(--d-danger-text);
  font-weight: var(--d-font-weight-semibold);
}
</style>
