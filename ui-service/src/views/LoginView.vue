<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'

/**
 * 登录页。
 * - 「记住账号」把上次登录的用户名写入 localStorage，下次自动回填。
 * - 错误提示直接使用统一响应包装里的 message（由 http 拦截器弹出），
 *   这里仅对表单做本地校验。
 */

const REMEMBER_KEY = 'dorm_last_username'

const auth = useAuthStore()
const meta = useMetaStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
  remember: true,
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
}

onMounted(() => {
  const last = localStorage.getItem(REMEMBER_KEY)
  if (last) {
    form.username = last
    form.remember = true
  }
})

async function handleSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await auth.login({ username: form.username.trim(), password: form.password })
    if (form.remember) localStorage.setItem(REMEMBER_KEY, form.username.trim())
    else localStorage.removeItem(REMEMBER_KEY)

    void meta.load().catch(() => undefined)
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/rooms'
    await router.replace(redirect)
  } catch {
    // 统一错误提示已由 axios 拦截器处理，此处只需保证按钮恢复可用
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="login-brand">
      <h1>海外宿舍管理系统</h1>
      <p>Overseas Dormitory Management Platform</p>
      <ul>
        <li>全球区域 / 公寓 / 房间 / 床位一体化管理</li>
        <li>入住申请、审批分房、订单与行程全流程</li>
        <li>内置 AI 助手，支持流式对话与断点续传</li>
      </ul>
    </div>

    <el-card class="login-card" shadow="always">
      <h2 class="login-title">账号登录</h2>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleSubmit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" size="large" clearable>
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            show-password
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.remember">记住账号</el-checkbox>
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="handleSubmit">
          登 录
        </el-button>
      </el-form>
      <p class="muted" style="margin-top: 14px; text-align: center">
        测试账号：superadmin / sea.admin / zhangwei，初始密码 Dorm@123456
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.login-wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 80px;
  background: linear-gradient(135deg, #1f2d3d 0%, #2c4a6b 55%, #409eff 100%);
  padding: 24px;
  flex-wrap: wrap;
}

.login-brand {
  color: #fff;
  max-width: 460px;
}

.login-brand h1 {
  font-size: 34px;
  margin: 0 0 8px;
  letter-spacing: 2px;
}

.login-brand p {
  opacity: 0.75;
  margin: 0 0 22px;
  letter-spacing: 1px;
}

.login-brand ul {
  padding-left: 20px;
  line-height: 2;
  opacity: 0.9;
}

.login-card {
  width: 380px;
  border-radius: 10px;
}

.login-title {
  margin: 0 0 18px;
  font-size: 20px;
  text-align: center;
}
</style>
