<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Moon, Sunny, Fold, Expand } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'
import { useRegionStore } from '@/stores/region'
import { useTheme } from '@/composables/useTheme'
import { routes } from '@/router'
import type { UserRole } from '@/api/types'

/**
 * 主框架：左侧菜单（按角色动态渲染）+ 顶部栏（用户信息 / 区域 / 退出）。
 * 菜单项直接从路由表 meta 生成，保证「路由 + 菜单」单一数据源。
 */

const { toggle: toggleTheme, isDark } = useTheme()

/**
 * 响应式：小屏下侧栏改为**离屏抽屉**。
 *
 * 为什么不在小屏继续用"收起成 64px 图标栏"：手机宽度通常 375~430px，
 * 64px 的图标栏会吃掉 15% 的横向空间，且图标无文字提示难以辨认。
 * 业界做法是小屏把导航整体移到离屏，需要时滑出（并带遮罩）。
 */
const isMobile = ref(false)
const mobileMenuOpen = ref(false)
let media: MediaQueryList | null = null

function syncViewport(e?: MediaQueryListEvent) {
  isMobile.value = e ? e.matches : (media?.matches ?? false)
  if (!isMobile.value) {
    mobileMenuOpen.value = false
  }
}

onMounted(() => {
  media = window.matchMedia('(max-width: 768px)')
  syncViewport()
  media.addEventListener('change', syncViewport)
})

onBeforeUnmount(() => media?.removeEventListener('change', syncViewport))

/** 顶栏那个按钮：桌面切"收起/展开"，移动端切"抽屉开/关"。 */
function toggleSidebar() {
  if (isMobile.value) {
    mobileMenuOpen.value = !mobileMenuOpen.value
  } else {
    collapsed.value = !collapsed.value
  }
}
const auth = useAuthStore()
const meta = useMetaStore()
const regionStore = useRegionStore()
const router = useRouter()
const route = useRoute()

const collapsed = ref(false)

interface MenuItem {
  path: string
  title: string
  icon: string
}

/** 员工端菜单 */
const employeeMenus: MenuItem[] = []
/** 管理员端菜单 */
const adminMenus: MenuItem[] = []

const layoutChildren = routes.find((r) => r.path === '/')?.children ?? []
for (const child of layoutChildren) {
  if (!child.meta?.menu || !child.meta.title) continue
  const item: MenuItem = {
    path: `/${String(child.path)}`,
    title: child.meta.title,
    icon: child.meta.icon ?? 'Menu',
  }
  const roles = child.meta.roles as UserRole[] | undefined
  if (roles && roles.length > 0) adminMenus.push(item)
  else employeeMenus.push(item)
}

/** 当前角色可见的管理端菜单 */
const visibleAdminMenus = computed(() => {
  const layoutChild = layoutChildren.filter((c) => c.meta?.menu)
  return adminMenus.filter((item) => {
    const child = layoutChild.find((c) => `/${String(c.path)}` === item.path)
    const roles = child?.meta?.roles as UserRole[] | undefined
    if (!roles || roles.length === 0) return true
    return auth.role ? roles.includes(auth.role) : false
  })
})

const activeMenu = computed(() => route.path)

const roleTagType = computed(() => {
  if (auth.isSuperAdmin) return 'danger'
  if (auth.isRegionAdmin) return 'warning'
  return 'info'
})

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  await auth.logout()
  void router.push({ name: 'login' })
}

onMounted(async () => {
  // 枚举字典是所有下拉/状态标签的数据源，进入主框架即预热
  void meta.load().catch(() => undefined)
  void regionStore.loadRegions().catch(() => undefined)
})
</script>

<template>
  <el-container style="height: 100%">
    <!-- 小屏时这个遮罩负责"点击空白关闭抽屉"，同时把背后内容压暗以突出导航 -->
    <div v-if="isMobile && mobileMenuOpen" class="sidebar-backdrop" @click="mobileMenuOpen = false" />
    <el-aside
      :width="isMobile ? '216px' : (collapsed ? '64px' : '216px')"
      class="app-aside"
      :class="{ 'is-mobile': isMobile, 'is-open': mobileMenuOpen }"
      style="transition: width 0.2s"
    >
      <div class="brand">
        <el-icon :size="22"><HomeFilled /></el-icon>
        <span v-show="!collapsed" class="brand-text">海外宿舍管理</span>
      </div>
      <el-scrollbar>
        <el-menu
          :default-active="activeMenu"
          :collapse="collapsed"
          background-color="#1f2d3d"
          text-color="#bfcbd9"
          active-text-color="#ffffff"
          router
        >
          <el-menu-item v-for="m in employeeMenus" :key="m.path" :index="m.path">
            <el-icon><component :is="m.icon" /></el-icon>
            <template #title>{{ m.title }}</template>
          </el-menu-item>

          <template v-if="visibleAdminMenus.length > 0">
            <div v-show="!collapsed" class="menu-group">管理端</div>
            <el-menu-item v-for="m in visibleAdminMenus" :key="m.path" :index="m.path">
              <el-icon><component :is="m.icon" /></el-icon>
              <template #title>{{ m.title }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="topbar-left">
          <el-icon class="collapse-btn" :size="20" @click="toggleSidebar">
            <Fold v-if="isMobile ? !mobileMenuOpen : !collapsed" />
            <Expand v-else />
          </el-icon>
          <span class="topbar-title">{{ route.meta.title ?? '' }}</span>
        </div>
        <div class="topbar-right">
          <el-tooltip :content="isDark() ? '切换到浅色主题' : '切换到深色主题'" placement="bottom">
            <el-icon class="theme-btn" :size="18" @click="toggleTheme">
              <Moon v-if="!isDark()" />
              <Sunny v-else />
            </el-icon>
          </el-tooltip>
          <el-tag v-if="auth.user?.regionName" type="success" effect="plain" size="small">
            <el-icon><Location /></el-icon>
            {{ auth.user.regionName }}
          </el-tag>
          <el-tag v-else type="info" effect="plain" size="small">全球</el-tag>

          <el-tag :type="roleTagType" effect="dark" size="small">{{ auth.user?.roleLabel }}</el-tag>

          <el-dropdown>
            <span class="user-trigger">
              <el-icon><UserFilled /></el-icon>
              {{ auth.displayName }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/profile')">
                  <el-icon><Setting /></el-icon> 个人中心
                </el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main style="padding: 0; background: var(--dorm-bg)">
        <router-view v-slot="{ Component }">
          <!-- 路由切换过渡：用令牌里的时长/缓动，避免各处自己写 0.3s ease。
               mode="out-in" 保证旧页面先淡出、新页面再淡入，不会出现两页重叠。
               prefers-reduced-motion 由 tokens.css 的全局规则统一降级（动效不能只做加法不做退让）。 -->
          <transition name="page" mode="out-in">
            <keep-alive :include="['RoomSearchView']">
              <component :is="Component" />
            </keep-alive>
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.brand {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  color: #fff;
  background: #17222e;
  font-weight: 700;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
}

.brand-text {
  letter-spacing: 1px;
}

.menu-group {
  color: #8492a6;
  font-size: 12px;
  padding: 14px 18px 6px;
  letter-spacing: 1px;
}

.topbar {
  height: 56px;
  /* 必须用令牌：写死 #fff 会让深色主题下"浅色文字落在白底上"（实测对比度 1.16，几乎不可见） */
  background: var(--d-surface-card);
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.collapse-btn {
  cursor: pointer;
  color: #606266;
}

.topbar-title {
  font-size: 15px;
  font-weight: 600;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  font-size: 14px;
  color: #303133;
  outline: none;
}

:deep(.el-menu) {
  border-right: none;
}

:deep(.el-menu-item.is-active) {
  background: var(--dorm-sidebar-active) !important;
}

:deep(.el-aside) {
  background: var(--dorm-sidebar-bg);
  overflow-x: hidden;
}

/* 路由切换过渡：位移极小（4px）—— 页面级动效的作用是"提示发生了切换"，
   不是"表演"。位移过大在频繁切页时会让人晕。 */
.page-enter-active {
  transition: opacity var(--d-duration-normal) var(--d-ease-decelerate),
    transform var(--d-duration-normal) var(--d-ease-decelerate);
}

.page-leave-active {
  transition: opacity var(--d-duration-fast) var(--d-ease-accelerate),
    transform var(--d-duration-fast) var(--d-ease-accelerate);
}

.page-enter-from {
  opacity: 0;
  transform: translateY(4px);
}

.page-leave-to {
  opacity: 0;
  transform: translateY(-2px);
}

/* ---------------------------------------------------------------- 响应式侧栏 */
/* 小屏：侧栏移出视口，用 transform 滑入 —— 比 display 切换更平滑，也便于配遮罩 */
.app-aside.is-mobile {
  position: fixed;
  top: 0;
  left: 0;
  height: 100vh;
  z-index: var(--d-z-overlay);
  transform: translateX(-100%);
  transition: transform var(--d-duration-normal) var(--d-ease-standard);
  box-shadow: var(--d-shadow-lg);
}

.app-aside.is-mobile.is-open {
  transform: translateX(0);
}

.sidebar-backdrop {
  position: fixed;
  inset: 0;
  z-index: calc(var(--d-z-overlay) - 1);
  background: rgba(15, 23, 42, 0.45);
  animation: backdrop-in var(--d-duration-fast) var(--d-ease-standard);
}

@keyframes backdrop-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
</style>