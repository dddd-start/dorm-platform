#!/usr/bin/env node
/**
 * 浏览器冒烟：登录 -> 校验关键页面渲染 -> 收集控制台错误。
 *
 * 依赖 Playwright（不写进 package.json，避免为了一个检查给前端加重 devDependency）。
 * 首次使用：
 *   npm i -D playwright && npx playwright install chromium
 * 已有全局安装时可用环境变量指定：
 *   UI_URL=http://127.0.0.1:8080
 *   PLAYWRIGHT_MODULE=/path/to/node_modules/playwright/index.mjs
 */
const UI = process.env.UI_URL || 'http://127.0.0.1:8080'

let playwright
try {
  playwright = await import(process.env.PLAYWRIGHT_MODULE || 'playwright')
} catch {
  console.error('[ui-check] 未找到 Playwright 模块。安装：npm i -D playwright && npx playwright install chromium')
  process.exit(2)
}
const { chromium } = playwright
const browser = await chromium.launch({ headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const errors = []
page.on('console', (msg) => {
  if (msg.type() === 'error') errors.push(msg.text())
})
page.on('pageerror', (err) => errors.push('pageerror: ' + err.message))

let failed = 0
const check = (name, ok, detail = '') => {
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  ' + detail : ''}`)
  if (!ok) failed++
}

try {
  await page.goto(`${UI}/login`, { waitUntil: 'networkidle' })
  check('登录页加载', (await page.title()).includes('海外宿舍'), await page.title())

  await page.getByPlaceholder('请输入用户名').fill('superadmin')
  await page.getByPlaceholder('请输入密码').fill('Dorm@123456')
  await page.getByRole('button', { name: '登 录' }).click().catch(async () => {
    await page.locator('button').first().click()
  })
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 })
  check('登录跳转', !page.url().includes('/login'), page.url())

  for (const [path, marker] of [
    ['/rooms', '房源'],
    ['/admin/dashboard', '运营'],
    ['/agent', '助手'],
    ['/admin/agent-ops', 'Agent'],
  ]) {
    await page.goto(`${UI}${path}`, { waitUntil: 'networkidle' })
    await page.waitForTimeout(800)
    const text = await page.locator('body').innerText()
    check(`页面 ${path}`, text.includes(marker), text.slice(0, 60).replace(/\n/g, ' '))
  }

  check('无控制台错误', errors.length === 0, errors.slice(0, 3).join(' | '))
} finally {
  await browser.close()
}

process.exit(failed === 0 ? 0 : 1)
