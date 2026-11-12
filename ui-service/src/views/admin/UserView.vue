<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { userApi } from '@/api/user'
import { useMetaStore } from '@/stores/meta'
import { useRegionStore } from '@/stores/region'
import { toLoadError } from '@/api/http'
import { useBreakpoint } from '@/composables/useBreakpoint'
import AsyncState from '@/components/AsyncState.vue'
import type { UserCreateRequest, UserRole, UserVO } from '@/api/types'

/** 用户管理（仅总管理员）：列表 / 新增 / 编辑 / 启用停用 / 重置密码 */

const meta = useMetaStore()
const regionStore = useRegionStore()
const { isMobile } = useBreakpoint()

const loading = ref(false)
const list = ref<UserVO[]>([])
const total = ref(0)
/** 错误态（加载失败，带重试 + traceId）与空态（确实没有用户）必须分开 */
const loadError = ref('')
const loadTraceId = ref('')

const query = reactive({
  current: 1,
  size: 10,
  keyword: '',
  role: '' as UserRole | '',
  regionId: undefined as number | undefined,
  status: undefined as number | undefined,
})

/** 是否处于筛选状态 —— 决定空态文案 */
const filtered = computed(() =>
  Boolean(query.keyword || query.role || query.regionId !== undefined || query.status !== undefined),
)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  loadTraceId.value = ''
  try {
    const page = await userApi.page({
      current: query.current,
      size: query.size,
      keyword: query.keyword || undefined,
      role: (query.role || undefined) as UserRole | undefined,
      regionId: query.regionId,
      status: query.status,
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

function handleSearch(): void {
  query.current = 1
  void load()
}

function handleReset(): void {
  query.current = 1
  query.keyword = ''
  query.role = ''
  query.regionId = undefined
  query.status = undefined
  void load()
}

/* ------------------------------ 新增 / 编辑 ------------------------------ */
const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const form = reactive<UserCreateRequest>({
  id: undefined,
  username: '',
  password: '',
  realName: '',
  employeeNo: '',
  role: 'EMPLOYEE',
  regionId: undefined,
  department: '',
  email: '',
  phone: '',
  status: 1,
})

const isEdit = ref(false)

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  password: [
    {
      validator: (_rule, value: string, callback) => {
        // 新增时密码必填；编辑时留空表示不修改
        if (!isEdit.value && (!value || value.length < 6)) {
          return callback(new Error('新增用户时密码必填且至少 6 位'))
        }
        if (value && value.length < 6) return callback(new Error('密码至少 6 位'))
        return callback()
      },
      trigger: 'blur',
    },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  phone: [
    {
      validator: (_rule, value: string, callback) => {
        if (!value) return callback()
        if (!/^[+]?[\d\s-]{6,20}$/.test(value)) return callback(new Error('手机号格式不正确'))
        return callback()
      },
      trigger: 'blur',
    },
  ],
}

function openCreate(): void {
  isEdit.value = false
  Object.assign(form, {
    id: undefined,
    username: '',
    password: '',
    realName: '',
    employeeNo: '',
    role: 'EMPLOYEE',
    regionId: undefined,
    department: '',
    email: '',
    phone: '',
    status: 1,
  })
  dialogVisible.value = true
}

function openEdit(row: UserVO): void {
  isEdit.value = true
  Object.assign(form, {
    id: row.id,
    username: row.username,
    // 编辑时密码留空表示不修改
    password: '',
    realName: row.realName,
    employeeNo: row.employeeNo ?? '',
    role: row.role,
    regionId: row.regionId,
    department: row.department ?? '',
    email: row.email ?? '',
    // 列表接口返回的是脱敏手机号，编辑时不回填，避免把脱敏值写回
    phone: '',
    status: row.status,
  })
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 区域管理员必须归属某个区域
  if (form.role === 'REGION_ADMIN' && !form.regionId) {
    ElMessage.warning('区域管理员必须选择所属区域')
    return
  }
  saving.value = true
  try {
    await userApi.save({
      ...form,
      password: form.password || undefined,
      phone: form.phone || undefined,
    })
    ElMessage.success(isEdit.value ? '用户已更新' : '用户已创建')
    dialogVisible.value = false
    await load()
  } catch {
    /* 已提示 */
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: UserVO): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  const label = next === 1 ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确定${label}账号「${row.username}」吗？`, '提示', {
      type: 'warning',
      confirmButtonText: label,
    })
  } catch {
    return
  }
  await userApi.updateStatus(row.id, next as 0 | 1)
  ElMessage.success(`已${label}`)
  await load()
}

/** 重置密码：后端以 query 参数接收 password */
async function resetPassword(row: UserVO): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      `为用户「${row.username}」设置新密码`,
      '重置密码',
      {
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        inputPlaceholder: '请输入新密码（至少 6 位）',
        inputValidator: (v: string) => (v && v.length >= 6 ? true : '密码至少 6 位'),
      },
    )
    await userApi.resetPassword(row.id, value)
    ElMessage.success('密码已重置')
  } catch {
    /* 已提示 */
  }
}

/** 区域管理员角色才需要区域列 */
function showRegionColumn(): boolean {
  return true
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
        <span>用户管理</span>
        <el-button type="success" @click="openCreate">
          <el-icon><Plus /></el-icon> 新增用户
        </el-button>
      </h3>

      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          placeholder="账号 / 姓名 / 工号"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
        <el-select v-model="query.role" placeholder="角色" clearable style="width: 140px">
          <el-option
            v-for="item in meta.options('userRole')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-select v-model="query.regionId" placeholder="所属区域" clearable style="width: 150px">
          <el-option v-for="r in regionStore.regions" :key="r.id" :label="r.name" :value="r.id" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px">
          <el-option label="正常" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <!-- 三态边界：加载（骨架）> 出错（重试 + traceId）> 空（区分"未筛选"与"筛选无结果"） -->
      <AsyncState
        :loading="loading"
        :error="loadError"
        :trace-id="loadTraceId"
        :empty="list.length === 0"
        skeleton="list"
        :skeleton-rows="5"
        :empty-title="filtered ? '没有符合条件的用户' : '还没有用户数据'"
        :empty-description="
          filtered
            ? '试试放宽账号 / 角色 / 区域 / 状态条件，或重置后重新查询。'
            : '点击右上角「新增用户」创建账号，并按角色分配区域与权限。'
        "
        @retry="load"
      >
        <template #empty-action>
          <el-button v-if="filtered" size="small" @click="handleReset">重置筛选条件</el-button>
        </template>

        <!-- 小屏：宽表转卡片。实测 390px 下用户表内部宽度约 1700px、可视容器仅 326px，
             且 210px 的固定操作列会把"编辑/重置密码/禁用"三个按钮挤到屏幕外 -->
        <div v-if="isMobile" class="m-list" data-test="users-cards">
          <el-card v-for="row in list" :key="row.id" shadow="never" class="m-card">
            <div class="m-card__head">
              <span class="m-card__title">
                {{ row.realName }}
                <span class="muted mono">@{{ row.username }}</span>
              </span>
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="plain">
                {{ row.status === 1 ? '正常' : '停用' }}
              </el-tag>
            </div>
            <div class="m-card__rows">
              <span class="m-card__label">角色</span>
              <span class="m-card__value">
                <el-tag
                  size="small"
                  :type="row.role === 'SUPER_ADMIN' ? 'danger' : row.role === 'REGION_ADMIN' ? 'warning' : 'info'"
                >
                  {{ row.roleLabel ?? meta.label('userRole', row.role) }}
                </el-tag>
              </span>
              <span class="m-card__label">工号</span>
              <span class="m-card__value">{{ row.employeeNo ?? '-' }}</span>
              <span class="m-card__label">所属区域</span>
              <span class="m-card__value">{{ row.regionName ?? '全球' }}</span>
              <span class="m-card__label">部门</span>
              <span class="m-card__value">{{ row.department ?? '-' }}</span>
              <span class="m-card__label">邮箱</span>
              <span class="m-card__value">{{ row.email ?? '-' }}</span>
              <span class="m-card__label">手机号</span>
              <span class="m-card__value mono">{{ row.phoneMask ?? '-' }}</span>
              <span class="m-card__label">最后登录</span>
              <span class="m-card__value">{{ row.lastLoginTime ?? '-' }}</span>
            </div>
            <div class="m-card__actions">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="warning" @click="resetPassword(row)">重置密码</el-button>
              <el-button link :type="row.status === 1 ? 'danger' : 'success'" @click="toggleStatus(row)">
                {{ row.status === 1 ? '禁用' : '启用' }}
              </el-button>
            </div>
          </el-card>
        </div>

        <el-table v-else :data="list" border stripe>
          <el-table-column prop="username" label="账号" width="140">
            <template #default="{ row }">
              <span class="mono">{{ row.username }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="realName" label="姓名" width="110" />
          <el-table-column prop="employeeNo" label="工号" width="110">
            <template #default="{ row }">{{ row.employeeNo ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="角色" width="110">
            <template #default="{ row }">
              <el-tag
                size="small"
                :type="row.role === 'SUPER_ADMIN' ? 'danger' : row.role === 'REGION_ADMIN' ? 'warning' : 'info'"
              >
                {{ row.roleLabel ?? meta.label('userRole', row.role) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="所属区域" width="110">
            <template #default="{ row }">{{ row.regionName ?? '全球' }}</template>
          </el-table-column>
          <el-table-column prop="department" label="部门" min-width="140">
            <template #default="{ row }">{{ row.department ?? '-' }}</template>
          </el-table-column>
          <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.email ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="手机号" width="140">
            <template #default="{ row }">
              <span class="mono">{{ row.phoneMask ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="plain">
                {{ row.status === 1 ? '正常' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="lastLoginTime" label="最后登录" min-width="160">
            <template #default="{ row }">{{ row.lastLoginTime ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="210" :fixed="isMobile ? undefined : 'right'">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="warning" @click="resetPassword(row)">重置密码</el-button>
              <el-button link :type="row.status === 1 ? 'danger' : 'success'" @click="toggleStatus(row)">
                {{ row.status === 1 ? '禁用' : '启用' }}
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

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑用户' : '新增用户'"
      width="620px"
      append-to-body
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" :disabled="isEdit" placeholder="登录账号" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="isEdit ? '留空表示不修改密码' : '至少 6 位'"
          />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" />
        </el-form-item>
        <el-form-item label="工号">
          <el-input v-model="form.employeeNo" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" style="width: 100%">
            <el-option
              v-for="item in meta.options('userRole')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="showRegionColumn()" label="所属区域">
          <el-select v-model="form.regionId" placeholder="总管理员可留空（表示全球）" clearable style="width: 100%">
            <el-option v-for="r in regionStore.regions" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="部门">
          <el-input v-model="form.department" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" :placeholder="isEdit ? '留空表示不修改' : '选填'" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="正常" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
