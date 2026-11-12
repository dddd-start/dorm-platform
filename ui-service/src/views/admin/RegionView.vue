<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { regionApi } from '@/api/region'
import { useRegionStore } from '@/stores/region'
import { toLoadError } from '@/api/http'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AsyncState from '@/components/AsyncState.vue'
import type { RegionSaveRequest, RegionVO } from '@/api/types'

/** 区域管理（仅总管理员）：列表 + 新增/编辑 + 启用停用 */

const regionStore = useRegionStore()
const { isMobile } = useBreakpoint()

const loading = ref(false)
const list = ref<RegionVO[]>([])
/** 错误态：非空即渲染 StateError（与"确实没有数据"视觉可区分），并带重试入口 */
const loadError = ref('')
const loadTraceId = ref('')

const query = reactive({
  status: undefined as number | undefined,
  withStats: true,
})

/** 是否处于筛选状态 —— 决定空态文案是"没有符合条件"还是"还没有数据" */
const filtered = computed(() => query.status !== undefined)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  loadTraceId.value = ''
  try {
    list.value = await regionApi.list({ status: query.status, withStats: query.withStats })
    // 区域变更后刷新下拉缓存，保证其它页面拿到最新数据
    await regionStore.loadRegions(true).catch(() => undefined)
  } catch (err) {
    // 关键：这里**不能**只写 list.value = []，
    // 否则"接口失败"与"确实没数据"会渲染成同一个空态，用户只能靠刷新来猜。
    const { message, traceId } = toLoadError(err)
    loadError.value = message
    loadTraceId.value = traceId
    list.value = []
  } finally {
    loading.value = false
  }
}

function resetQuery(): void {
  query.status = undefined
  query.withStats = true
  void load()
}

/* ------------------------------ 表单 ------------------------------ */
const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const form = reactive<RegionSaveRequest>({
  id: undefined,
  code: '',
  name: '',
  nameEn: '',
  country: '',
  countryCode: '',
  timezone: '',
  currency: '',
  managerId: undefined,
  status: 1,
  remark: '',
})

const rules: FormRules = {
  code: [{ required: true, message: '请输入区域编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入区域名称', trigger: 'blur' }],
}

function openCreate(): void {
  Object.assign(form, {
    id: undefined,
    code: '',
    name: '',
    nameEn: '',
    country: '',
    countryCode: '',
    timezone: '',
    currency: '',
    managerId: undefined,
    status: 1,
    remark: '',
  })
  dialogVisible.value = true
}

function openEdit(row: RegionVO): void {
  Object.assign(form, {
    id: row.id,
    code: row.code,
    name: row.name,
    nameEn: row.nameEn ?? '',
    country: row.country ?? '',
    countryCode: row.countryCode ?? '',
    timezone: row.timezone ?? '',
    currency: row.currency ?? '',
    managerId: row.managerId,
    status: row.status,
    remark: row.remark ?? '',
  })
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await regionApi.save({ ...form })
    ElMessage.success(form.id ? '区域已更新' : '区域已创建')
    dialogVisible.value = false
    await load()
  } catch {
    /* 已提示 */
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: RegionVO): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  const label = next === 1 ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      `${label}区域「${row.name}」后，其下公寓将${next === 1 ? '恢复' : '不再'}参与房源分配，确定继续？`,
      '提示',
      { type: 'warning', confirmButtonText: label },
    )
  } catch {
    return
  }
  await regionApi.updateStatus(row.id, next as 0 | 1)
  ElMessage.success(`已${label}`)
  await load()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page-container">
    <div class="page-card">
      <h3 class="page-title">
        <span>区域管理</span>
        <el-button type="success" @click="openCreate">
          <el-icon><Plus /></el-icon> 新增区域
        </el-button>
      </h3>

      <div class="filter-bar">
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="load">
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>
        <el-checkbox v-model="query.withStats" @change="load">包含统计数据</el-checkbox>
        <el-button type="primary" @click="load">查询</el-button>
      </div>

      <!-- 三态边界：加载（骨架）> 出错（危险色 + 重试 + traceId）> 空（中性灰 + 下一步）
           注意这里刻意把"筛选后为空"和"确实没有数据"分成两句文案 —— 用户能据此判断该改条件还是该新增。 -->
      <AsyncState
        :loading="loading"
        :error="loadError"
        :trace-id="loadTraceId"
        :empty="list.length === 0"
        skeleton="list"
        :skeleton-rows="5"
        :empty-title="filtered ? '没有符合筛选条件的区域' : '还没有区域数据'"
        :empty-description="filtered ? '试试放宽状态筛选，或重置条件后重新查询。' : '点击右上角「新增区域」创建第一个区域。'"
        @retry="load"
      >
        <template #empty-action>
          <el-button v-if="filtered" size="small" @click="resetQuery">重置筛选条件</el-button>
        </template>

        <!-- 移动端：宽表转卡片（表格在 390px 下只能显示 1~2 列，且末尾操作列会被挤出可视区） -->
        <div v-if="isMobile" class="m-list" data-test="region-cards">
          <el-card v-for="row in list" :key="row.id" shadow="never" class="m-card">
            <div class="m-card__head">
              <span class="m-card__title">{{ row.name }}</span>
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="plain">
                {{ row.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </div>
            <div class="m-card__rows">
              <span class="m-card__label">编码</span>
              <span class="m-card__value mono">{{ row.code }}</span>
              <span class="m-card__label">英文名</span>
              <span class="m-card__value">{{ row.nameEn || '-' }}</span>
              <span class="m-card__label">国家</span>
              <span class="m-card__value">
                {{ row.country || '-' }}<template v-if="row.countryCode"> ({{ row.countryCode }})</template>
              </span>
              <span class="m-card__label">时区</span>
              <span class="m-card__value">{{ row.timezone || '-' }}</span>
              <span class="m-card__label">币种</span>
              <span class="m-card__value">{{ row.currency || '-' }}</span>
              <span class="m-card__label">负责人</span>
              <span class="m-card__value">{{ row.managerName || '-' }}</span>
              <span class="m-card__label">房间/床位/空闲</span>
              <span class="m-card__value">
                {{ row.roomCount ?? 0 }} / {{ row.bedCount ?? 0 }} / {{ row.idleBedCount ?? 0 }}
              </span>
              <span class="m-card__label">备注</span>
              <span class="m-card__value">{{ row.remark || '-' }}</span>
            </div>
            <div class="m-card__actions">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link :type="row.status === 1 ? 'danger' : 'success'" @click="toggleStatus(row)">
                {{ row.status === 1 ? '停用' : '启用' }}
              </el-button>
            </div>
          </el-card>
        </div>

        <!-- 桌面端：保留表格（跨行比较是表格不可替代的价值） -->
        <el-table v-else :data="list" border stripe>
          <el-table-column prop="code" label="编码" width="100">
            <template #default="{ row }">
              <span class="mono">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column label="区域名称" min-width="150">
            <template #default="{ row }">
              <div>{{ row.name }}</div>
              <div class="muted">{{ row.nameEn ?? '' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="国家 / 地区" min-width="120">
            <template #default="{ row }">
              {{ row.country ?? '-' }}
              <span v-if="row.countryCode" class="muted">({{ row.countryCode }})</span>
            </template>
          </el-table-column>
          <el-table-column prop="timezone" label="时区" min-width="140">
            <template #default="{ row }">{{ row.timezone ?? '-' }}</template>
          </el-table-column>
          <el-table-column prop="currency" label="币种" width="90">
            <template #default="{ row }">{{ row.currency ?? '-' }}</template>
          </el-table-column>
          <el-table-column prop="managerName" label="负责人" width="110">
            <template #default="{ row }">{{ row.managerName ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="房间 / 床位 / 空闲" width="170" align="center">
            <template #default="{ row }">
              {{ row.roomCount ?? 0 }} / {{ row.bedCount ?? 0 }} /
              <b class="idle-count">{{ row.idleBedCount ?? 0 }}</b>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.remark ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="plain">
                {{ row.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link :type="row.status === 1 ? 'danger' : 'success'" @click="toggleStatus(row)">
                {{ row.status === 1 ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </AsyncState>
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑区域' : '新增区域'" width="640px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="区域编码" prop="code">
          <el-input v-model="form.code" placeholder="例如：SEA" />
        </el-form-item>
        <el-form-item label="区域名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：东南亚" />
        </el-form-item>
        <el-form-item label="英文名称">
          <el-input v-model="form.nameEn" placeholder="例如：Southeast Asia" />
        </el-form-item>
        <el-form-item label="国家 / 地区">
          <el-input v-model="form.country" placeholder="例如：新加坡" />
        </el-form-item>
        <el-form-item label="国家代码">
          <el-input v-model="form.countryCode" placeholder="例如：SG" maxlength="8" />
        </el-form-item>
        <el-form-item label="时区">
          <el-input v-model="form.timezone" placeholder="例如：Asia/Singapore" />
        </el-form-item>
        <el-form-item label="币种">
          <el-input v-model="form.currency" placeholder="例如：SGD" maxlength="8" />
        </el-form-item>
        <el-form-item label="负责人 ID">
          <el-input-number v-model="form.managerId" :min="1" :controls="false" style="width: 200px" />
          <span class="muted" style="margin-left: 8px">区域管理员用户 ID（选填）</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" placeholder="例如：覆盖新加坡、马来西亚、泰国" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 空闲床位数：原来写死 #67c23a（亮绿），深色主题下落在深底上不够；
   改用令牌的语义文字色，两个主题下都由令牌保证可读性。 */
.idle-count {
  color: var(--d-success-text);
}
</style>
