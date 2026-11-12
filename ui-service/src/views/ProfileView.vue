<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { toLoadError } from '@/api/http'
import AsyncState from '@/components/AsyncState.vue'
import type { UserVO } from '@/api/types'

/**
 * 个人中心：资料查看/编辑 + 修改密码。
 * 注意：手机号明文只在 `/api/auth/me/profile` 返回（字段名仍叫 phoneMask），
 * 因此回填编辑表单必须用该接口，而不是 `/api/auth/me`（那里是脱敏值）。
 */

const auth = useAuthStore()

const activeTab = ref('profile')

/* ------------------------------ 个人资料 ------------------------------ */
const info = ref<UserVO | null>(null)
const infoLoading = ref(false)
/** 资料加载失败：必须有独立的错误态（带重试），不能被"空资料"掩盖 */
const infoError = ref('')
const infoTraceId = ref('')
const saving = ref(false)
const infoFormRef = ref<FormInstance>()

const infoForm = reactive({
  realName: '',
  department: '',
  email: '',
  phone: '',
})

const infoRules: FormRules = {
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  phone: [
    {
      validator: (_rule, value: string, callback) => {
        // 允许为空；填写时必须符合国际/国内手机号格式
        if (!value) return callback()
        if (!/^[+]?[\d\s-]{6,20}$/.test(value)) return callback(new Error('手机号格式不正确'))
        return callback()
      },
      trigger: 'blur',
    },
  ],
}

async function loadProfile(): Promise<void> {
  infoLoading.value = true
  infoError.value = ''
  infoTraceId.value = ''
  try {
    const data = await authApi.myProfile()
    info.value = data
    infoForm.realName = data.realName ?? ''
    infoForm.department = data.department ?? ''
    infoForm.email = data.email ?? ''
    // phoneMask 在本人接口里是明文手机号
    infoForm.phone = data.phoneMask ?? ''
  } catch (err) {
    const { message, traceId } = toLoadError(err)
    infoError.value = message
    infoTraceId.value = traceId
    info.value = null
  } finally {
    infoLoading.value = false
  }
}

async function handleSaveInfo(): Promise<void> {
  if (!infoFormRef.value) return
  const valid = await infoFormRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    await auth.updateProfile({
      realName: infoForm.realName.trim(),
      department: infoForm.department?.trim() || undefined,
      email: infoForm.email?.trim() || undefined,
      phone: infoForm.phone?.trim() || undefined,
    })
    ElMessage.success('资料已保存')
    await loadProfile()
  } catch {
    /* 错误提示已由拦截器统一处理 */
  } finally {
    saving.value = false
  }
}

/* ------------------------------ 修改密码 ------------------------------ */
const pwdFormRef = ref<FormInstance>()
const pwdSaving = ref(false)
const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const pwdRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '新密码长度需在 6-64 位之间', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== pwdForm.newPassword) return callback(new Error('两次输入的密码不一致'))
        return callback()
      },
      trigger: 'blur',
    },
  ],
}

async function handleChangePassword(): Promise<void> {
  if (!pwdFormRef.value) return
  const valid = await pwdFormRef.value.validate().catch(() => false)
  if (!valid) return

  pwdSaving.value = true
  try {
    await authApi.updatePassword({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword,
    })
    ElMessage.success('密码修改成功')
    pwdFormRef.value.resetFields()
  } catch {
    /* 已提示 */
  } finally {
    pwdSaving.value = false
  }
}

onMounted(() => {
  void loadProfile()
})
</script>

<template>
  <div class="page-container">
    <div class="page-card">
      <h3 class="page-title">个人中心</h3>
      <el-tabs v-model="activeTab">
        <!-- ------------------------------ 个人资料 ------------------------------ -->
        <el-tab-pane label="个人资料" name="profile">
          <!-- 三态：骨架（加载）/ 错误（重试 + traceId）/ 内容。
               原实现用的是 v-loading 转圈 + 无错误分支，接口挂了会看到一片空白表单。 -->
          <AsyncState
            :loading="infoLoading"
            :error="infoError"
            :trace-id="infoTraceId"
            skeleton="detail"
            @retry="loadProfile"
          >
            <div class="profile-grid">
              <el-descriptions :column="1" border size="small" class="readonly-panel">
                <el-descriptions-item label="用户名">{{ info?.username ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="工号">{{ info?.employeeNo ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="角色">{{ info?.roleLabel ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="所属区域">{{ info?.regionName ?? '全球' }}</el-descriptions-item>
                <el-descriptions-item label="账号状态">
                  <el-tag :type="info?.status === 1 ? 'success' : 'info'" size="small" effect="plain">
                    {{ info?.status === 1 ? '正常' : '停用' }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="最后登录">{{ info?.lastLoginTime ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="创建时间">{{ info?.createTime ?? '-' }}</el-descriptions-item>
              </el-descriptions>

              <el-form
                ref="infoFormRef"
                :model="infoForm"
                :rules="infoRules"
                label-width="90px"
                class="edit-panel"
              >
                <el-form-item label="姓名" prop="realName">
                  <el-input v-model="infoForm.realName" placeholder="请输入姓名" />
                </el-form-item>
                <el-form-item label="部门" prop="department">
                  <el-input v-model="infoForm.department" placeholder="请输入部门" />
                </el-form-item>
                <el-form-item label="邮箱" prop="email">
                  <el-input v-model="infoForm.email" placeholder="请输入邮箱" />
                </el-form-item>
                <el-form-item label="手机号" prop="phone">
                  <el-input v-model="infoForm.phone" placeholder="请输入手机号（明文回填）" />
                  <div class="muted">该字段仅本人可见，列表中他人看到的是脱敏号码。</div>
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :loading="saving" @click="handleSaveInfo">保存资料</el-button>
                  <el-button @click="loadProfile">重置</el-button>
                </el-form-item>
              </el-form>
            </div>
          </AsyncState>
        </el-tab-pane>

        <!-- ------------------------------ 修改密码 ------------------------------ -->
        <el-tab-pane label="修改密码" name="password">
          <el-form
            ref="pwdFormRef"
            :model="pwdForm"
            :rules="pwdRules"
            label-width="90px"
            style="max-width: 460px"
          >
            <el-form-item label="原密码" prop="oldPassword">
              <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入原密码" />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少 6 位" />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input
                v-model="pwdForm.confirmPassword"
                type="password"
                show-password
                placeholder="请再次输入新密码"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="pwdSaving" @click="handleChangePassword">
                确认修改
              </el-button>
            </el-form-item>
          </el-form>
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="修改密码后请使用新密码重新登录，当前会话在过期前仍然有效。"
          />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.profile-grid {
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
}

.readonly-panel {
  /* 窄屏自适应：原来写死 340px，在 320~360px 的小屏上会溢出页面卡片 */
  width: 100%;
  max-width: 340px;
  flex: 0 1 auto;
}

.edit-panel {
  flex: 1 1 380px;
  max-width: 560px;
}
</style>
