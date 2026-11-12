<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { applyApi } from '@/api/apply'
import { useMetaStore } from '@/stores/meta'
import { toLoadError } from '@/api/http'
import { useBreakpoint } from '@/composables/useBreakpoint'
import ApplyTimeline from './ApplyTimeline.vue'
import AsyncState from './AsyncState.vue'
import StatusTag from './StatusTag.vue'
import type { ApplyVO } from '@/api/types'

/**
 * 申请详情抽屉：基本信息 + 状态时间轴 + 可执行动作。
 * 可执行动作完全由后端下发的 `allowedActions` 驱动，前端不自行推断业务状态机。
 */
const props = defineProps<{
  modelValue: boolean
  applyId?: string | null
  /** 是否展示审批类动作（员工端只保留 CANCEL / CHECK_IN / CHECK_OUT） */
  adminMode?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'changed'): void
}>()

const meta = useMetaStore()
const { isMobile } = useBreakpoint()
const detail = ref<ApplyVO | null>(null)
const loading = ref(false)
const actionLoading = ref(false)
/** 详情加载失败：必须给出重试与 traceId，而不是显示"暂无详情" */
const loadError = ref('')
const loadTraceId = ref('')

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

/** 员工端隐藏审批 / 驳回 / 换房；管理端全部展示 */
const actions = computed<string[]>(() => {
  const all = detail.value?.allowedActions ?? []
  if (props.adminMode) return all
  return all.filter((a) => ['CANCEL', 'CHECK_IN', 'CHECK_OUT'].includes(a))
})

async function load(): Promise<void> {
  if (!props.applyId) {
    detail.value = null
    return
  }
  loading.value = true
  loadError.value = ''
  loadTraceId.value = ''
  try {
    detail.value = await applyApi.detail(props.applyId)
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    loadError.value = message
    loadTraceId.value = traceId
    detail.value = null
  } finally {
    loading.value = false
  }
}

/** 重试加载详情（由错误态的重试按钮触发） */
async function reload(): Promise<void> {
  await load()
}

watch(
  () => [props.modelValue, props.applyId] as const,
  ([open]) => {
    if (open) void load()
  },
)

async function refresh(): Promise<void> {
  await load()
  emit('changed')
}

/** 取消申请（需填原因） */
async function handleCancel(): Promise<void> {
  if (!detail.value) return
  try {
    const { value } = await ElMessageBox.prompt('请填写取消原因', '取消申请', {
      confirmButtonText: '确认取消',
      cancelButtonText: '返回',
      inputPlaceholder: '例如：行程变更',
      inputValidator: (v: string) => (v && v.trim().length > 0 ? true : '取消原因不能为空'),
    })
    actionLoading.value = true
    await applyApi.cancel({ applyId: detail.value.id, reason: value })
    ElMessage.success('已取消申请')
    await refresh()
  } catch {
    // 用户主动关闭弹窗或接口失败（接口错误已由拦截器提示）
  } finally {
    actionLoading.value = false
  }
}

/** 办理入住 */
async function handleCheckIn(): Promise<void> {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(
      `确认办理入住？房间 ${detail.value.roomNo ?? '-'} / 床位 ${detail.value.bedNo ?? '-'}`,
      '办理入住',
      { confirmButtonText: '确认入住', cancelButtonText: '取消', type: 'info' },
    )
  } catch {
    return
  }
  actionLoading.value = true
  try {
    await applyApi.checkIn({ applyId: detail.value.id, remark: '前端办理入住' })
    ElMessage.success('入住成功')
    await refresh()
  } catch {
    /* 已提示 */
  } finally {
    actionLoading.value = false
  }
}

/** 办理退房；早于原定退房日必须填写提前退房原因 */
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
      applyId: detail.value.id,
      earlyCheckOutReason: isEarly ? value : undefined,
      remark: isEarly ? undefined : value || undefined,
    })
    ElMessage.success('退房成功')
    await refresh()
  } catch {
    /* 已提示 */
  } finally {
    actionLoading.value = false
  }
}

/** 驳回 */
async function handleReject(): Promise<void> {
  if (!detail.value) return
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回申请', {
      confirmButtonText: '确认驳回',
      cancelButtonText: '返回',
      inputValidator: (v: string) => (v && v.trim().length > 0 ? true : '驳回原因不能为空'),
    })
    actionLoading.value = true
    await applyApi.reject({ applyId: detail.value.id, reason: value })
    ElMessage.success('已驳回')
    await refresh()
  } catch {
    /* 已提示 */
  } finally {
    actionLoading.value = false
  }
}

defineExpose({ refresh })
</script>

<template>
  <el-drawer v-model="visible" title="申请详情" size="640px">
    <!-- 三态：骨架（加载）/ 错误（可重试 + traceId）/ 空（确实没有详情） -->
    <AsyncState
      :loading="loading"
      :error="loadError"
      :trace-id="loadTraceId"
      :empty="!detail"
      skeleton="detail"
      compact
      empty-title="暂无详情"
      empty-description="该申请没有可展示的明细数据。"
      @retry="reload"
    >
      <template v-if="detail">
        <el-descriptions :column="isMobile ? 1 : 2" border size="small">
          <el-descriptions-item label="申请单号">
            <span class="mono">{{ detail.applyNo }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag enum-key="applyStatus" :value="detail.status" :override-color="detail.statusColor" />
          </el-descriptions-item>
          <el-descriptions-item label="申请人">{{ detail.employeeName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="工号">{{ detail.employeeNo ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="区域">{{ detail.regionName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="目的城市">{{ detail.destCity ?? detail.destCountry ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="入住日期">{{ detail.checkInDate ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="退房日期">{{ detail.checkOutDate ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="住宿晚数">{{ detail.nights ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="期望房型">
            {{ detail.preferredRoomType ? meta.label('roomType', detail.preferredRoomType) : '不限' }}
          </el-descriptions-item>
          <el-descriptions-item label="期望公寓">{{ detail.buildingName ?? '不限' }}</el-descriptions-item>
          <el-descriptions-item label="出差单号">{{ detail.tripNo ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="房间 / 床位">
            <span v-if="detail.roomNo">{{ detail.roomNo }} / {{ detail.bedNo ?? '-' }}</span>
            <span v-else class="muted">待分配</span>
          </el-descriptions-item>
          <el-descriptions-item label="关联订单">{{ detail.orderNo ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="审批人">{{ detail.approverName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="审批时间">{{ detail.approveTime ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="单价">
            <span v-if="detail.pricePerNight != null">{{ detail.pricePerNight }} {{ detail.currency ?? '' }}</span>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="申请来源">{{ detail.applySourceLabel ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="出差事由" :span="isMobile ? 1 : 2">{{ detail.reason ?? '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.approveRemark" label="审批备注" :span="isMobile ? 1 : 2">
            {{ detail.approveRemark }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.rejectReason" label="驳回原因" :span="isMobile ? 1 : 2">
            <span class="reject-text">{{ detail.rejectReason }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="isMobile ? 1 : 2">{{ detail.createTime ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <h4 style="margin: 18px 0 10px">状态时间轴</h4>
        <ApplyTimeline :items="detail.timeline" />

        <div v-if="actions.length > 0" style="margin-top: 18px; display: flex; gap: 10px; flex-wrap: wrap">
          <el-button
            v-if="actions.includes('CHECK_IN')"
            type="success"
            :loading="actionLoading"
            @click="handleCheckIn"
          >
            办理入住
          </el-button>
          <el-button
            v-if="actions.includes('CHECK_OUT')"
            type="warning"
            :loading="actionLoading"
            @click="handleCheckOut"
          >
            办理退房
          </el-button>
          <el-button
            v-if="actions.includes('REJECT') && adminMode"
            type="danger"
            plain
            :loading="actionLoading"
            @click="handleReject"
          >
            驳回
          </el-button>
          <el-button
            v-if="actions.includes('CANCEL')"
            type="danger"
            plain
            :loading="actionLoading"
            @click="handleCancel"
          >
            取消申请
          </el-button>
        </div>
      </template>
    </AsyncState>
  </el-drawer>
</template>

<style scoped>
/* 驳回原因：原来写死 #f56c6c，深色主题下偏暗；改用令牌的危险色文字档 */
.reject-text {
  color: var(--d-danger-text);
}
</style>
