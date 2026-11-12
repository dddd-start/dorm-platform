import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/**
 * 断点感知（响应式布局的地基）
 *
 * 关键设计：**断点数值不在这里硬编码**，而是运行时从设计令牌层读取
 * （`--d-breakpoint-md` / `--d-breakpoint-lg`，定义在 styles/tokens.css）。
 *
 * 为什么值得绕这一圈：令牌层的意义就是"单一事实来源"。真实项目里
 * 最容易出现的漂移是 —— CSS 里用 768px、JS 里写 767px、文档里写 768、
 * 测试里又写 769，四个地方各自维护。这里让 JS 也读令牌，
 * 换断点时只需要改 tokens.css 一处。
 *
 * 令牌缺失（例如 SSR / 单元测试无样式表）时退回令牌的**当前取值**作为兜底，
 * 保证不会因为它读不到而整页失效。
 */

/** 令牌名 → 兜底值（兜底值必须与 tokens.css 保持一致） */
const FALLBACK: Record<string, number> = {
  '--d-breakpoint-sm': 640,
  '--d-breakpoint-md': 768,
  '--d-breakpoint-lg': 1024,
  '--d-breakpoint-xl': 1280,
}

/** 读取某个断点令牌的像素值（读不到时用兜底值） */
export function tokenBreakpoint(name: keyof typeof FALLBACK | string): number {
  if (typeof window === 'undefined') return FALLBACK[name] ?? 768
  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  const parsed = parseFloat(raw)
  if (!Number.isNaN(parsed) && parsed > 0) return parsed
  return FALLBACK[name] ?? 768
}

/**
 * 监听 `(max-width: <断点>)`。
 * 返回 ref 与清理逻辑；组件卸载自动清理，避免重复挂载时监听器泄漏。
 */
function useMaxWidth(token: string): Ref<boolean> {
  const matched = ref(false)
  let mql: MediaQueryList | null = null

  const sync = (e?: MediaQueryListEvent): void => {
    matched.value = e ? e.matches : (mql?.matches ?? false)
  }

  onMounted(() => {
    const px = tokenBreakpoint(token)
    mql = window.matchMedia(`(max-width: ${px}px)`)
    sync()
    mql.addEventListener('change', sync)
  })

  onBeforeUnmount(() => mql?.removeEventListener('change', sync))

  return matched
}

/**
 * 页面级响应式断点。
 *
 * - `isMobile`：≤ --d-breakpoint-md（768px）。宽表转卡片、弹窗全屏化、三栏折叠。
 * - `isCompact`：≤ --d-breakpoint-lg（1024px）。只做"减列"，不做结构变更。
 */
export function useBreakpoint() {
  const isMobile = useMaxWidth('--d-breakpoint-md')
  const isCompact = useMaxWidth('--d-breakpoint-lg')
  return { isMobile, isCompact }
}
