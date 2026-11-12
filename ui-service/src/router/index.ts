import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import type { UserRole } from '@/api/types'

/**
 * 路由表。
 * - history 模式（后端已配置 SPA 回退，刷新不 404）
 * - meta.roles 为空表示所有登录用户可见；否则按角色过滤菜单与访问
 */
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 允许访问的角色；不填=全部登录用户 */
    roles?: UserRole[]
    /** 是否在左侧菜单展示 */
    menu?: boolean
    icon?: string
  }
}

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录' },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/rooms',
    children: [
      /* ------------------------------ 员工端 ------------------------------ */
      {
        path: 'rooms',
        name: 'room-search',
        component: () => import('@/views/employee/RoomSearchView.vue'),
        meta: { title: '房源查询', menu: true, icon: 'Search' },
      },
      {
        path: 'apply/create',
        name: 'apply-create',
        component: () => import('@/views/employee/ApplyCreateView.vue'),
        meta: { title: '提交入住申请', menu: true, icon: 'DocumentAdd' },
      },
      {
        path: 'apply/mine',
        name: 'my-applies',
        component: () => import('@/views/employee/MyAppliesView.vue'),
        meta: { title: '我的申请', menu: true, icon: 'Tickets' },
      },
      {
        path: 'orders/mine',
        name: 'my-orders',
        component: () => import('@/views/employee/MyOrdersView.vue'),
        meta: { title: '我的订单', menu: true, icon: 'List' },
      },
      {
        path: 'trips',
        name: 'my-trips',
        component: () => import('@/views/employee/MyTripsView.vue'),
        meta: { title: '我的行程', menu: true, icon: 'Location' },
      },
      {
        path: 'agent',
        name: 'agent',
        component: () => import('@/views/employee/AgentView.vue'),
        meta: { title: 'AI 助手', menu: true, icon: 'ChatDotRound' },
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/ProfileView.vue'),
        meta: { title: '个人中心', menu: true, icon: 'User' },
      },

      /* ------------------------------ 管理员端 ------------------------------ */
      {
        path: 'admin/approve',
        name: 'admin-approve',
        component: () => import('@/views/admin/ApproveView.vue'),
        meta: { title: '申请审批', menu: true, icon: 'Checked', roles: ['SUPER_ADMIN', 'REGION_ADMIN'] },
      },
      {
        path: 'admin/resources',
        name: 'admin-resources',
        component: () => import('@/views/admin/ResourceView.vue'),
        meta: { title: '房源管理', menu: true, icon: 'OfficeBuilding', roles: ['SUPER_ADMIN', 'REGION_ADMIN'] },
      },
      {
        path: 'admin/orders',
        name: 'admin-orders',
        component: () => import('@/views/admin/OrderAdminView.vue'),
        meta: { title: '订单管理', menu: true, icon: 'Files', roles: ['SUPER_ADMIN', 'REGION_ADMIN'] },
      },
      {
        path: 'admin/dashboard',
        name: 'admin-dashboard',
        component: () => import('@/views/admin/DashboardView.vue'),
        meta: { title: '统计看板', menu: true, icon: 'DataAnalysis', roles: ['SUPER_ADMIN', 'REGION_ADMIN'] },
      },
      {
        path: 'admin/regions',
        name: 'admin-regions',
        component: () => import('@/views/admin/RegionView.vue'),
        meta: { title: '区域管理', menu: true, icon: 'MapLocation', roles: ['SUPER_ADMIN'] },
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserView.vue'),
        meta: { title: '用户管理', menu: true, icon: 'UserFilled', roles: ['SUPER_ADMIN'] },
      },
      {
        path: 'admin/audits',
        name: 'admin-audits',
        component: () => import('@/views/admin/AuditView.vue'),
        meta: { title: '审计日志', menu: true, icon: 'Document', roles: ['SUPER_ADMIN', 'REGION_ADMIN'] },
      },
      {
        path: 'admin/agent-ops',
        name: 'admin-agent-ops',
        component: () => import('@/views/admin/AgentOpsView.vue'),
        meta: { title: 'Agent 运营看板', menu: true, icon: 'Cpu', roles: ['SUPER_ADMIN', 'REGION_ADMIN'] },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory('/'),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.name === 'login') {
    return true
  }
  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 已登录但用户信息未加载（比如刷新页面）
  if (!auth.loaded) {
    try {
      await auth.fetchMe()
    } catch {
      auth.clear()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  // 角色校验
  const roles = to.meta.roles
  if (roles && roles.length > 0 && auth.role && !roles.includes(auth.role)) {
    return { name: 'room-search' }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = title ? `${title} · 海外宿舍管理系统` : '海外宿舍管理系统'
})

export default router
