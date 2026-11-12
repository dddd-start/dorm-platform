<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { applyApi } from '@/api/apply'
import { roomApi } from '@/api/room'
import { useMetaStore } from '@/stores/meta'
import { useRegionStore } from '@/stores/region'
import type { BuildingOption, RoomType } from '@/api/types'

/**
 * 提交入住申请。
 * 关键点：
 * 1. 前端实时计算住宿晚数并校验退房日晚于入住日。
 * 2. 防重复提交：submitting 控制按钮禁用 + 同一个 idempotencyKey 在整个提交
 *    生命周期内复用（只有提交成功后才换新 key），从而保证网络重试不会产生重复申请。
 */

const route = useRoute()
const router = useRouter()
const meta = useMetaStore()
const regionStore = useRegionStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
/** 幂等键：组件挂载生成一次，提交成功后重置 */
const idempotencyKey = ref<string>(crypto.randomUUID())

const buildingOptions = ref<BuildingOption[]>([])
/** 参考单价（来自可订查询，仅用于展示预估费用） */
const refPrice = ref<{ price?: number; currency?: string } | null>(null)

const form = reactive({
  regionId: undefined as number | undefined,
  destCountry: '',
  destCity: '',
  buildingId: undefined as number | undefined,
  preferredRoomType: '' as RoomType | '',
  checkInDate: '',
  checkOutDate: '',
  tripNo: '',
  reason: '',
})

const rules: FormRules = {
  regionId: [{ required: true, message: '请选择目的地区域', trigger: 'change' }],
  checkInDate: [{ required: true, message: '请选择入住日期', trigger: 'change' }],
  checkOutDate: [
    { required: true, message: '请选择退房日期', trigger: 'change' },
    {
      validator: (_rule, value: string, callback) => {
        if (!value || !form.checkInDate) return callback()
        if (value <= form.checkInDate) return callback(new Error('退房日期必须晚于入住日期'))
        return callback()
      },
      trigger: 'change',
    },
  ],
}

/** 住宿晚数 */
const nights = computed(() => {
  if (!form.checkInDate || !form.checkOutDate) return 0
  const diff =
    (new Date(form.checkOutDate).getTime() - new Date(form.checkInDate).getTime()) / 86400000
  return diff > 0 ? Math.round(diff) : 0
})

/** 预估总价 = 晚数 × 参考单价 */
const estimatedTotal = computed(() => {
  if (!refPrice.value?.price || nights.value <= 0) return null
  return Number((refPrice.value.price * nights.value).toFixed(2))
})

async function loadBuildings(regionId?: number): Promise<void> {
  buildingOptions.value = []
  if (!regionId) return
  try {
    buildingOptions.value = await regionStore.loadBuildings(regionId)
  } catch {
    buildingOptions.value = []
  }
}

/** 区域变化：联动公寓下拉 + 预填国家 */
async function handleRegionChange(regionId?: number): Promise<void> {
  form.buildingId = undefined
  await loadBuildings(regionId)
  const region = regionStore.regions.find((r) => r.id === regionId)
  if (region) {
    // 区域选项里带 country，自动预填目的地国家（允许用户修改）
    if (!form.destCountry && region.country) form.destCountry = region.country
    // 记录币种，用于价格展示
    refPrice.value = region.currency ? { currency: region.currency } : null
  }
  void refreshRefPrice()
}

/** 公寓变化：带出城市 */
function handleBuildingChange(buildingId?: number): void {
  const b = buildingOptions.value.find((x) => x.id === buildingId)
  if (b?.city && !form.destCity) form.destCity = b.city
  void refreshRefPrice()
}

/** 拉取参考单价：用可订查询的第一条结果作为参考价 */
async function refreshRefPrice(): Promise<void> {
  if (!form.regionId || nights.value <= 0) return
  try {
    const rows = await roomApi.availability({
      regionId: form.regionId,
      buildingId: form.buildingId,
      roomType: (form.preferredRoomType || undefined) as RoomType | undefined,
      checkInDate: form.checkInDate,
      checkOutDate: form.checkOutDate,
      minBeds: 1,
    })
    const first = rows[0]
    refPrice.value = first ? { price: first.pricePerNight, currency: first.currency } : null
  } catch {
    refPrice.value = null
  }
}

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (nights.value <= 0) {
    ElMessage.warning('住宿晚数必须大于 0')
    return
  }

  submitting.value = true
  try {
    const created = await applyApi.create({
      regionId: form.regionId as number,
      destCountry: form.destCountry || undefined,
      destCity: form.destCity || undefined,
      buildingId: form.buildingId,
      preferredRoomType: (form.preferredRoomType || undefined) as RoomType | undefined,
      checkInDate: form.checkInDate,
      checkOutDate: form.checkOutDate,
      tripNo: form.tripNo || undefined,
      reason: form.reason || undefined,
      // 同一个 key 在本次提交内复用，成功后才换新，避免重复申请
      idempotencyKey: idempotencyKey.value,
    })
    ElMessage.success(`申请提交成功，单号 ${created.applyNo}`)
    // 成功后换新幂等键，避免影响下一次申请
    idempotencyKey.value = crypto.randomUUID()
    try {
      await ElMessageBox.confirm('是否前往「我的申请」查看审批进度？', '提交成功', {
        confirmButtonText: '查看我的申请',
        cancelButtonText: '继续填写',
        type: 'success',
      })
      await router.push({ name: 'my-applies' })
    } catch {
      resetForm()
    }
  } catch {
    // 失败时保留 idempotencyKey 不变，用户重试不会生成重复申请
  } finally {
    submitting.value = false
  }
}

function resetForm(): void {
  formRef.value?.resetFields()
  form.destCountry = ''
  form.destCity = ''
  form.buildingId = undefined
  form.preferredRoomType = ''
  form.tripNo = ''
  form.reason = ''
  refPrice.value = null
}

onMounted(async () => {
  await regionStore.loadRegions().catch(() => undefined)

  // 从房源查询页跳转过来时预填条件（query 值都是字符串，数字字段需要转换）
  const q = route.query
  const num = (v: unknown): number | undefined => {
    const n = Number(v)
    return Number.isFinite(n) && n > 0 ? n : undefined
  }
  form.checkInDate = typeof q.checkInDate === 'string' ? q.checkInDate : ''
  form.checkOutDate = typeof q.checkOutDate === 'string' ? q.checkOutDate : ''
  form.preferredRoomType = (typeof q.roomType === 'string' ? q.roomType : '') as RoomType | ''
  if (typeof q.city === 'string') form.destCity = q.city

  const regionId = num(q.regionId)
  if (regionId) {
    form.regionId = regionId
    await handleRegionChange(regionId)
  }
  const buildingId = num(q.buildingId)
  if (buildingId) {
    form.buildingId = buildingId
    handleBuildingChange(buildingId)
  }
  void refreshRefPrice()
})
</script>

<template>
  <div class="page-container">
    <div class="page-card">
      <h3 class="page-title">提交入住申请</h3>

      <el-steps :active="1" simple style="margin-bottom: 20px">
        <el-step title="填写申请" description="选择区域与日期" />
        <el-step title="管理员审批分房" description="审批通过后分配床位" />
        <el-step title="办理入住" description="到达公寓后入住" />
        <el-step title="办理退房" description="行程结束后退房" />
      </el-steps>

      <el-alert
        v-if="nights > 0"
        type="success"
        show-icon
        :closable="false"
        style="margin-bottom: 18px"
      >
        <template #title>
          住宿 <b>{{ nights }}</b> 晚（{{ form.checkInDate }} ~ {{ form.checkOutDate }}）
          <span v-if="refPrice?.price">
            · 参考单价 <b>{{ refPrice.price }} {{ refPrice.currency ?? '' }}</b>/晚
            · 预估总价
            <b style="color: #f56c6c">{{ estimatedTotal }} {{ refPrice.currency ?? '' }}</b>
          </span>
          <span v-else class="muted">（选择公寓与房型后可显示参考价格）</span>
        </template>
      </el-alert>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
        style="max-width: 680px"
      >
        <el-form-item label="目的地区域" prop="regionId">
          <el-select
            v-model="form.regionId"
            placeholder="请选择区域"
            clearable
            style="width: 100%"
            @change="handleRegionChange"
          >
            <el-option v-for="r in regionStore.regions" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>

        <el-form-item label="目的地国家">
          <el-input v-model="form.destCountry" placeholder="例如：新加坡（可自动带出，允许修改）" />
        </el-form-item>

        <el-form-item label="目的地城市">
          <el-input v-model="form.destCity" placeholder="例如：新加坡" />
        </el-form-item>

        <el-form-item label="期望公寓">
          <el-select
            v-model="form.buildingId"
            placeholder="不限定可留空"
            clearable
            filterable
            :disabled="!form.regionId"
            style="width: 100%"
            @change="handleBuildingChange"
          >
            <el-option v-for="b in buildingOptions" :key="b.id" :label="b.name" :value="b.id" />
          </el-select>
        </el-form-item>

        <el-form-item label="期望房型">
          <el-select v-model="form.preferredRoomType" placeholder="不限定可留空" clearable style="width: 100%">
            <el-option
              v-for="item in meta.options('roomType')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="入住日期" prop="checkInDate">
          <el-date-picker
            v-model="form.checkInDate"
            type="date"
            placeholder="选择入住日期"
            value-format="YYYY-MM-DD"
            style="width: 100%"
            @change="refreshRefPrice"
          />
        </el-form-item>

        <el-form-item label="退房日期" prop="checkOutDate">
          <el-date-picker
            v-model="form.checkOutDate"
            type="date"
            placeholder="选择退房日期"
            value-format="YYYY-MM-DD"
            style="width: 100%"
            @change="refreshRefPrice"
          />
        </el-form-item>

        <el-form-item label="住宿晚数">
          <el-tag :type="nights > 0 ? 'success' : 'info'" size="large">
            {{ nights > 0 ? `${nights} 晚` : '请选择入住与退房日期' }}
          </el-tag>
        </el-form-item>

        <el-form-item label="出差单号">
          <el-input v-model="form.tripNo" placeholder="例如：TRIP-2026-001（选填）" />
        </el-form-item>

        <el-form-item label="出差事由">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请简要说明出差事由（选填）"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" :disabled="submitting" @click="handleSubmit">
            {{ submitting ? '提交中…' : '提交申请' }}
          </el-button>
          <el-button :disabled="submitting" @click="resetForm">重置</el-button>
          <span class="muted" style="margin-left: 10px">
            提交按钮已做防重复处理，重试不会生成重复申请
          </span>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>
