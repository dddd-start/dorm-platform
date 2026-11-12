import { ref, watch } from 'vue'

/**
 * 主题状态（浅色 / 深色 / 跟随系统）。
 *
 * ## 为什么必须同时动两样东西
 * 本项目的样式有**两套变量来源**，只切一套就会出现"半深色"：
 *   1. 我们自己的设计令牌 `--d-*`（见 `styles/tokens.css`），由 `data-theme` 属性驱动；
 *   2. Element Plus 的 `--el-*` 变量（它有独立的深色变量表），由 `<html>` 上的 `dark` 类驱动。
 * 因此 `apply()` 必须同时设置 `data-theme` 与 `dark` 类 —— 少一个都会留下浅色残块。
 *
 * ## 为什么持久化
 * 主题是用户偏好，刷新页面后必须保持；否则每次进页面都会闪一下浅色再变深色。
 * 首屏闪烁的根治办法是在 `index.html` 里内联一小段脚本提前设置属性，
 * 但那需要改模板；当前用量级下先用 `localStorage` + 在应用挂载前应用一次。
 */
export type ThemeMode = 'light' | 'dark' | 'auto'

const STORAGE_KEY = 'dorm-theme'

function systemPrefersDark(): boolean {
  return typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-color-scheme: dark)').matches === true
}

function readStored(): ThemeMode {
  const raw = localStorage.getItem(STORAGE_KEY)
  return raw === 'light' || raw === 'dark' || raw === 'auto' ? raw : 'auto'
}

/** 把主题落到 DOM 上（唯一的副作用出口）。 */
function apply(mode: ThemeMode): void {
  const dark = mode === 'dark' || (mode === 'auto' && systemPrefersDark())
  const root = document.documentElement
  // 手动值必须能被样式区分：'auto' 时移除 data-theme，让媒体查询接管
  if (mode === 'auto') {
    root.removeAttribute('data-theme')
  } else {
    root.dataset.theme = mode
  }
  // Element Plus 的深色变量靠这个类生效
  root.classList.toggle('dark', dark)
}

const mode = ref<ThemeMode>(readStored())

watch(mode, (next) => {
  localStorage.setItem(STORAGE_KEY, next)
  apply(next)
})

/** 系统主题变化时，仅在 auto 模式下跟随。 */
if (typeof window !== 'undefined') {
  window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener?.('change', () => {
    if (mode.value === 'auto') {
      apply('auto')
    }
  })
}

export function useTheme() {
  return {
    mode,
    /** 在应用挂载前调用一次，避免首屏用错主题。 */
    init: () => apply(mode.value),
    set: (next: ThemeMode) => {
      mode.value = next
    },
    /** 在浅色/深色之间快速切换（UI 上的那个按钮用它）。 */
    toggle: () => {
      const dark = mode.value === 'dark'
        || (mode.value === 'auto' && systemPrefersDark())
      mode.value = dark ? 'light' : 'dark'
    },
    /** 当前实际是不是深色（供图标切换用）。 */
    isDark: () => mode.value === 'dark'
      || (mode.value === 'auto' && systemPrefersDark()),
  }
}
