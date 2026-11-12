import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
// Element Plus 的深色变量表（靠 <html class="dark"> 生效）
import 'element-plus/theme-chalk/dark/css-vars.css'

import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/http'
import './styles/main.css'
// 设计令牌放在最后引入：它以 CSS 变量形式生效，需要能覆盖前面的默认值
import './styles/tokens.css'
import { useTheme } from './composables/useTheme'

// 401 时统一跳转登录页（通过钩子注入，避免 http.ts 与 router 循环依赖）
setUnauthorizedHandler(() => {
  const current = router.currentRoute.value
  if (current.name === 'login') return
  void router.replace({ name: 'login', query: { redirect: current.fullPath } })
})

// 在挂载前应用主题，避免首屏用错主题
useTheme().init()

const app = createApp(App)

// 全量注册 Element Plus 图标，模板中可直接使用 <el-icon><House /></el-icon>
for (const [name, comp] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, comp)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
