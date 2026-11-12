#!/usr/bin/env node
/**
 * 面试展示级截图 + AI 助手流式演示 GIF 采集脚本。
 *
 * 一次跑完做三件事：
 *   1) 通过 /api/** 正常接口补齐演示数据（申请流转 / 订单 / 审计），不直接改库；
 *   2) 用 Playwright 以 1440x900 @2x 截 10+ 张图，每张都做「渲染完成」校验
 *      （无加载骨架、无错误提示、表格有数据、图表已绘制、控制台无 error），
 *      同页最多重试 3 次，取通过校验的那一次；
 *   3) 录制 AI 助手「提问 -> 流式逐字输出 -> 引用/工具出现」的连续截图，
 *      用 python3 + Pillow 合成 docs/screenshots/agent-stream.gif。
 *
 * 用法：
 *   node scripts/capture-screenshots.mjs                 # 全量：造数据 + 截图 + GIF
 *   node scripts/capture-screenshots.mjs --skip-prep     # 跳过造数据（数据已就绪时）
 *   node scripts/capture-screenshots.mjs --no-gif        # 只截图
 *   node scripts/capture-screenshots.mjs --only=02-rooms,07-dashboard
 *   node scripts/capture-screenshots.mjs --list          # 只列出将要采集的页面
 *
 * 依赖（本机已有，无需 npm install）：
 *   - Playwright 模块：优先用项目/全局已安装的 playwright（PLAYWRIGHT_MODULE 可指定路径）
 *   - 浏览器：用 Playwright 默认的浏览器缓存目录（PLAYWRIGHT_BROWSERS_PATH 可覆盖）
 *   - GIF 合成：系统 python3 + Pillow（本机 12.x）；无 Pillow 时不合成 GIF，只留截图
 *
 * 产物：docs/screenshots/*.png、docs/screenshots/agent-stream.gif、docs/screenshots/manifest.md
 * 中间文件：.work/capture/（帧序列、失败样本）
 */
import fs from 'node:fs/promises'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(__dirname, '..')
const OUT = path.join(ROOT, 'docs/screenshots')
const WORK = path.join(ROOT, '.work/capture')
const FRAMES = path.join(WORK, 'frames')

/* ------------------------------ 环境参数 ------------------------------ */
const UI = process.env.UI_URL || 'http://127.0.0.1:8080'
const SWAGGER = process.env.SWAGGER_URL || 'http://127.0.0.1:8081/swagger-ui.html'
const PASSWORD = process.env.DEMO_PASSWORD || 'Dorm@123456'
const ADMIN = process.env.DEMO_ADMIN || 'superadmin'
const VIEWPORT = { width: 1440, height: 900 }
const DSF = 2 // 2 倍图：1440x900 逻辑像素 -> 2880x1800 物理像素

const PW_MODULE = process.env.PLAYWRIGHT_MODULE || 'playwright'

const ARGS = process.argv.slice(2)
const hasFlag = (name) => ARGS.includes(`--${name}`)
const argValue = (name, fallback = '') => {
  const hit = ARGS.find((a) => a.startsWith(`--${name}=`))
  return hit ? hit.slice(name.length + 3) : fallback
}
const ONLY = argValue('only')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
// 本地时区时间戳（manifest 里写采集时间，用 UTC 会让人以为是别的时间）
const nowIso = () => {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 本地时区（非 UTC）的 YYYY-MM-DD */
function localDay(offsetDays = 0) {
  const d = new Date()
  d.setDate(d.getDate() + offsetDays)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

const log = {
  step: (msg) => console.log(`\n\x1b[36m▸ ${msg}\x1b[0m`),
  ok: (msg) => console.log(`  \x1b[32m✓\x1b[0m ${msg}`),
  warn: (msg) => console.log(`  \x1b[33m!\x1b[0m ${msg}`),
  bad: (msg) => console.log(`  \x1b[31m✗\x1b[0m ${msg}`),
  info: (msg) => console.log(`  · ${msg}`),
}

/* ==================================================================== */
/*  1. 业务 API（全部走前端同源代理 /api/**，等价于页面上的真实操作）      */
/* ==================================================================== */
const tokens = new Map()

/**
 * 后端 Long 主键是 19 位（如 2120821308741713922），超出 JS Number 安全整数范围。
 * 直接 JSON.parse 会丢精度，把错误的 applyId 发回后端（表现为「入住申请不存在」）。
 * 这里在解析前把「字符串之外的 16 位以上整数」改写成字符串，后端 Jackson 会把数字
 * 字符串正常反序列化成 Long。
 */
function parseJsonKeepBigInt(text) {
  let out = ''
  let inStr = false
  let esc = false
  for (let i = 0; i < text.length;) {
    const c = text[i]
    if (inStr) {
      out += c
      if (esc) esc = false
      else if (c === '\\') esc = true
      else if (c === '"') inStr = false
      i++
      continue
    }
    if (c === '"') {
      inStr = true
      out += c
      i++
      continue
    }
    if (c === '-' || (c >= '0' && c <= '9')) {
      let j = i + 1
      while (j < text.length && /[0-9eE+\-.]/.test(text[j])) j++
      const lit = text.slice(i, j)
      out += /^-?\d{16,}$/.test(lit) ? `"${lit}"` : lit
      i = j
      continue
    }
    out += c
    i++
  }
  return JSON.parse(out)
}

async function api(method, apiPath, token, body) {
  const res = await fetch(UI + apiPath, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const raw = await res.text().catch(() => '')
  let json = null
  try {
    json = raw ? parseJsonKeepBigInt(raw) : null
  } catch {
    json = null
  }
  if (!json) throw new Error(`${method} ${apiPath} 返回非 JSON（HTTP ${res.status}）`)
  if (json.success === false) {
    throw new Error(`${method} ${apiPath} 失败：code=${json.code} ${json.message}`)
  }
  return json.data
}

async function tokenOf(username) {
  if (tokens.has(username)) return tokens.get(username)
  const data = await api('POST', '/api/auth/login', null, { username, password: PASSWORD })
  const token = data?.token
  if (!token) throw new Error(`账号 ${username} 登录未返回 token`)
  tokens.set(username, token)
  return token
}

/* ------------------------------ 演示数据计划 ------------------------------ */
/**
 * 10 条演示申请，覆盖状态机的每个状态，申请人都用种子账号（非真实人名/手机号）。
 * 日期按「今天」相对计算，保证可复跑；幂等键=tripNo（已存在则跳过或推进状态）。
 */
const APPLY_PLAN = [
  { trip: 'TRIP-DEMO-001', employee: 'zhangwei', regionId: 1001, buildingId: 2001, city: '新加坡',
    inDays: 7, outDays: 10, target: 'PENDING', reason: '新加坡客户现场支持，需就近住宿' },
  { trip: 'TRIP-DEMO-002', employee: 'zhangwei', regionId: 1001, buildingId: 2001, city: '新加坡',
    inDays: 0, outDays: 2, target: 'CHECKED_IN', reason: '新加坡数据中心割接支持' },
  { trip: 'TRIP-DEMO-003', employee: 'zhangwei', regionId: 1002, buildingId: 2003, city: '法兰克福',
    inDays: 4, outDays: 6, target: 'APPROVED', reason: '法兰克福展会现场布展' },
  { trip: 'TRIP-DEMO-004', employee: 'zhangwei', regionId: 1003, buildingId: 2006, city: '迪拜',
    // 同一员工相邻申请区间不能重叠（后端校验 3009），与 003 错开
    inDays: 12, outDays: 14, target: 'REJECTED', reason: '迪拜合作伙伴拜访' },
  { trip: 'TRIP-DEMO-005', employee: 'liuyang', regionId: 1002, buildingId: 2004, city: '慕尼黑',
    inDays: 0, outDays: 3, target: 'CHECKED_IN', reason: '慕尼黑研发中心联合调试' },
  { trip: 'TRIP-DEMO-006', employee: 'liuyang', regionId: 1004, buildingId: 2007, city: '东京',
    inDays: 4, outDays: 7, target: 'APPROVED', reason: '东京客户季度复盘会' },
  { trip: 'TRIP-DEMO-007', employee: 'chenjing', regionId: 1003, buildingId: 2005, city: '迪拜',
    inDays: 6, outDays: 9, target: 'PENDING', reason: '中东区域渠道培训支持' },
  { trip: 'TRIP-DEMO-008', employee: 'chenjing', regionId: 1003, buildingId: 2006, city: '迪拜',
    inDays: 0, outDays: 2, target: 'CHECKED_IN', reason: '迪拜机房巡检' },
  { trip: 'TRIP-DEMO-009', employee: 'wangfang', regionId: 1004, buildingId: 2008, city: '大阪',
    // 入住日期不能早于今天，所以「当天入住 + 当天提前退房」造一条已完成订单
    inDays: 0, outDays: 3, target: 'CHECKED_OUT', reason: '大阪供应链现场审计' },
  { trip: 'TRIP-DEMO-010', employee: 'wangfang', regionId: 1002, buildingId: 2004, city: '慕尼黑',
    inDays: 8, outDays: 11, target: 'PENDING', reason: '慕尼黑工业展参展' },
]

/** 找一间在指定日期区间内有空闲床位的房间，返回 {roomId, bedId, roomNo, bedNo} */
async function pickBed(adminToken, { regionId, buildingId, checkInDate, checkOutDate }) {
  const qs = new URLSearchParams({
    regionId: String(regionId),
    checkInDate,
    checkOutDate,
    minBeds: '1',
  })
  const findRooms = async (withBuilding) => {
    const q = new URLSearchParams(qs)
    if (withBuilding && buildingId) q.set('buildingId', String(buildingId))
    return api('GET', `/api/rooms/availability?${q}`, adminToken)
  }
  let rooms = await findRooms(true)
  if (!rooms?.length) rooms = await findRooms(false)
  for (const room of rooms ?? []) {
    if ((room.availableBeds ?? 0) <= 0) continue
    const beds = await api('GET', `/api/rooms/${room.roomId}/beds`, adminToken)
    const idle = (beds ?? []).find((b) => b.status === 'IDLE')
    if (idle) return { roomId: room.roomId, bedId: idle.bedId, roomNo: room.roomNo, bedNo: idle.bedNo }
  }
  throw new Error(`区域 ${regionId} 在 ${checkInDate}~${checkOutDate} 没有可分配床位`)
}

/** 把一条申请推进到目标状态（只做状态机允许的迁移） */
async function advanceApply(apply, plan, adminToken) {
  let cur = apply
  const refetch = async () => api('GET', `/api/applies/${cur.id}`, adminToken)

  if (plan.target === 'REJECTED') {
    if (cur.status === 'PENDING') {
      await api('POST', '/api/applies/reject', adminToken, {
        applyId: cur.id,
        reason: '演示数据：行程调整，改期后重新提交',
      })
      cur = await refetch()
    }
    return cur
  }

  const needsApproved = ['APPROVED', 'CHECKED_IN', 'CHECKED_OUT'].includes(plan.target)
  const needsCheckedIn = ['CHECKED_IN', 'CHECKED_OUT'].includes(plan.target)
  const needsCheckedOut = plan.target === 'CHECKED_OUT'

  if (needsApproved && cur.status === 'PENDING') {
    const bed = await pickBed(adminToken, {
      regionId: plan.regionId,
      buildingId: plan.buildingId,
      checkInDate: plan.in,
      checkOutDate: plan.out,
    })
    await api('POST', '/api/applies/approve', adminToken, {
      applyId: cur.id,
      roomId: bed.roomId,
      bedId: bed.bedId,
      remark: `演示审批：分配 ${bed.roomNo} 房 ${bed.bedNo} 床`,
    })
    cur = await refetch()
  }
  if (needsCheckedIn && cur.status === 'APPROVED') {
    await api('POST', '/api/applies/check-in', adminToken, {
      applyId: cur.id,
      remark: '演示数据：已到店办理入住',
    })
    cur = await refetch()
  }
  if (needsCheckedOut && cur.status === 'CHECKED_IN') {
    await api('POST', '/api/applies/check-out', adminToken, {
      applyId: cur.id,
      earlyCheckOutReason: '演示数据：行程提前结束',
      remark: '演示数据：按时退房',
    })
    cur = await refetch()
  }
  return cur
}

async function prepareDemoData() {
  log.step('准备演示数据（全部走 /api/** 正常接口，不使用直接改库）')
  const adminToken = await tokenOf(ADMIN)

  const existingPage = await api('GET', '/api/applies?current=1&size=100', adminToken)
  const byTripNo = new Map((existingPage?.records ?? []).map((r) => [r.tripNo, r]))
  log.info(`现有申请 ${existingPage?.total ?? 0} 条，其中带 tripNo 的 ${byTripNo.size} 条`)

  let created = 0
  let advanced = 0
  let skipped = 0
  for (const plan of APPLY_PLAN) {
    plan.in = localDay(plan.inDays)
    plan.out = localDay(plan.outDays)
    try {
      let apply = byTripNo.get(plan.trip)
      if (!apply) {
        const empToken = await tokenOf(plan.employee)
        apply = await api('POST', '/api/applies', empToken, {
          regionId: plan.regionId,
          destCountry: plan.city,
          destCity: plan.city,
          buildingId: plan.buildingId,
          preferredRoomType: 'SINGLE',
          checkInDate: plan.in,
          checkOutDate: plan.out,
          tripNo: plan.trip,
          reason: plan.reason,
          idempotencyKey: plan.trip.toLowerCase(),
        })
        created++
        log.info(`新建 ${apply.applyNo} ${plan.employee} ${plan.city} ${plan.in}~${plan.out}`)
      }
      const before = apply.status
      if (apply.status !== plan.target) {
        apply = await advanceApply(apply, plan, adminToken)
        if (apply.status !== before) advanced++
      }
      if (apply.status === plan.target) skipped++
      else log.warn(`${plan.trip} 期望 ${plan.target} 实际 ${apply.status}`)
    } catch (err) {
      log.warn(`${plan.trip} 准备失败：${err.message}`)
    }
  }

  const overview = await api('GET', '/api/stats/overview', adminToken)
  const orders = await api('GET', '/api/orders?current=1&size=100', adminToken)
  log.ok(`演示数据就绪：新建申请 ${created} 条、状态推进 ${advanced} 次、目标已达成 ${skipped} 条`)
  log.info(`看板口径：申请状态 ${JSON.stringify(overview?.applyStatusCount ?? {})}`
    + ` / 订单 ${JSON.stringify(overview?.orderStatusCount ?? {})}`
    + ` / 入住率 ${overview?.occupancyRate ?? 0}%（订单总数 ${orders?.total ?? 0}）`)

  // Agent 运营看板上的用量趋势需要至少几轮真实对话才有内容；这里只提示，不主动烧 token
  try {
    const usage = await api('GET', '/api/agent/admin/usage?days=14', adminToken)
    const turns = (usage?.trend ?? []).reduce((acc, p) => acc + (p.turns ?? 0), 0)
    log.info(`近 14 天 Agent 轮次 ${turns}（本脚本的对话截图/GIF 会再补充几轮）`)
  } catch { /* 忽略 */ }
}

/* ==================================================================== */
/*  2. Playwright：截图与校验                                            */
/* ==================================================================== */
const { chromium } = await import(PW_MODULE)

/** 页面级控制台错误收集器（每次导航清零） */
const consoleErrors = []
const pageErrors = []

function wirePage(page, tag) {
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(`[${tag}] ${msg.text()}`.slice(0, 300))
  })
  page.on('pageerror', (err) => pageErrors.push(`[${tag}] ${err.message}`.slice(0, 300)))
}

/** 等页面真正稳定：网络空闲 + 字体就绪 + 无 el-message 浮层 */
async function settle(page, { networkidle = true } = {}) {
  if (networkidle) await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {})
  await page.evaluate(() => document.fonts?.ready).catch(() => {})
  // 等浮层（登录成功 / 其他操作提示）自动消失，避免入镜
  for (let i = 0; i < 12; i++) {
    const n = await page.locator('.el-message').count()
    if (n === 0) break
    await sleep(300)
  }
  await page.waitForTimeout(450)
}

async function goto(page, pathOrUrl, opts = {}) {
  consoleErrors.length = 0
  pageErrors.length = 0
  const url = pathOrUrl.startsWith('http') ? pathOrUrl : UI + pathOrUrl
  await page.goto(url, { waitUntil: opts.waitUntil || 'networkidle', timeout: opts.timeout || 30000 })
  await settle(page, { networkidle: false })
}

/* ------------------------------ 断言工具 ------------------------------ */
const checks = {
  noLoading: async (page) => {
    const n = await page.locator('[data-state="loading"]:visible').count()
      + await page.locator('.el-loading-mask:visible').count()
    return n === 0 || `仍有 ${n} 处加载骨架/遮罩`
  },
  noSkeleton: async (page) => {
    const n = await page.locator('.el-skeleton:visible').count()
    return n === 0 || `仍有 ${n} 个骨架屏`
  },
  noErrorState: async (page) => {
    const n = await page.locator('[data-state="error"]:visible').count()
    return n === 0 || `页面处于错误态（${n} 处）`
  },
  noErrorToast: async (page) => {
    const n = await page.locator('.el-message--error:visible, .el-message--warning:visible').count()
    return n === 0 || `出现错误/警告浮层（${n} 个）`
  },
  noAlert: async (page) => {
    const n = await page.locator('.el-alert--error:visible, .el-alert--warning:visible').count()
    return n === 0 || `出现错误/警告提示条（${n} 个）`
  },
  noConsoleError: async () => {
    if (consoleErrors.length) return `控制台 error：${consoleErrors.slice(0, 3).join(' ｜ ')}`
    if (pageErrors.length) return `页面异常：${pageErrors.slice(0, 3).join(' ｜ ')}`
    return true
  },
  text: (needle) => async (page) => {
    const body = await page.locator('body').innerText()
    return body.includes(needle) || `页面缺少关键文本「${needle}」`
  },
  minRows: (sel, n) => async (page) => {
    const c = await page.locator(sel).count()
    return c >= (n ?? 1) || `${sel} 只有 ${c} 行（期望 ≥ ${n ?? 1}）`
  },
  minCount: (sel, n) => async (page) => {
    const c = await page.locator(`${sel}:visible`).count()
    return c >= (n ?? 1) || `${sel} 只有 ${c} 个（期望 ≥ ${n ?? 1}）`
  },
  /** ECharts 图表真的画出来了：canvas 上非背景像素占比 > 1% */
  chartsPainted: (n) => async (page) => {
    const ratios = await page.evaluate(() => {
      const out = []
      for (const cv of document.querySelectorAll('canvas')) {
        if (!cv.width || !cv.height) continue
        const tmp = document.createElement('canvas')
        tmp.width = 160
        tmp.height = 100
        const ctx = tmp.getContext('2d')
        ctx.drawImage(cv, 0, 0, 160, 100)
        const d = ctx.getImageData(0, 0, 160, 100).data
        let painted = 0
        for (let i = 0; i < d.length; i += 4) {
          if (d[i + 3] > 8 && !(d[i] > 246 && d[i + 1] > 246 && d[i + 2] > 246)) painted++
        }
        out.push(painted / (d.length / 4))
      }
      return out
    })
    const good = ratios.filter((r) => r > 0.01).length
    return good >= n || `${good} 张图表已绘制（期望 ≥ ${n}，占比 ${ratios.map((r) => r.toFixed(3)).join(',')}）`
  },
  /** 元素在 1440x900 视口里至少有 minH 像素露出来（截图要"看得见"，不只是 DOM 里有） */
  visibleInViewport: (sel, minH) => async (page) => {
    const box = await page.locator(sel).first().boundingBox().catch(() => null)
    if (!box) return `${sel} 不存在或不可见`
    const vh = page.viewportSize()?.height ?? 900
    const visible = Math.min(vh, box.y + box.height) - Math.max(0, box.y)
    return visible >= (minH ?? 40) || `${sel} 在视口里只露出 ${visible.toFixed(0)}px（期望 ≥ ${minH}px）`
  },
}

/** 折叠侧边栏：宽表在 1440 宽下会横向溢出，折叠后多出 152px 才能看到「状态」列 */
async function collapseSidebar(page) {
  const btn = page.locator('.collapse-btn').first()
  if (!(await btn.count())) return
  const width = await page.locator('.app-aside').first().evaluate((el) => el.getBoundingClientRect().width).catch(() => 0)
  if (width > 100) {
    await btn.click()
    await page.waitForTimeout(600)
  }
}

/**
 * 截一张展示级 PNG：最多重试 3 次，全部校验通过才落盘。
 * 任一次尝试通过即用该次结果；都不过则记录问题、保留最后一次截图作为兜底（_rejected 前缀不落盘）。
 */
async function shot(page, meta) {
  const file = path.join(OUT, meta.file)
  const failures = []
  for (let attempt = 1; attempt <= (meta.attempts ?? 3); attempt++) {
    if (meta.prepare && attempt === 1) {
      try {
        await meta.prepare(page)
      } catch (err) {
        failures.push(`prepare 失败：${err.message}`)
      }
    }
    await settle(page, { networkidle: meta.networkidle !== false })
    if (meta.after) await meta.after(page)

    const problems = []
    for (const chk of meta.checks ?? []) {
      try {
        const r = await chk(page)
        if (r !== true && typeof r === 'string') problems.push(r)
      } catch (err) {
        problems.push(`校验异常：${err.message}`)
      }
    }
    if (problems.length === 0) {
      await page.screenshot({ path: file, animations: 'disabled', caret: 'hide' })
      const { size } = await fs.stat(file)
      log.ok(`${meta.file}  ${meta.title}  ${(size / 1024).toFixed(0)}KB`
        + (attempt > 1 ? `（第 ${attempt} 次尝试通过）` : ''))
      results.push({ file: meta.file, title: meta.title, ok: true, attempt })
      return true
    }
    failures.push(`第 ${attempt} 次：${problems.join('；')}`)
    await sleep(900)
  }
  // 兜底：截一张并标注未通过，便于人工判断
  await page.screenshot({ path: path.join(WORK, `rejected-${meta.file}`), animations: 'disabled' })
  log.bad(`${meta.file} 未通过校验：${failures[failures.length - 1]}`)
  results.push({ file: meta.file, title: meta.title, ok: false, problems: failures })
  return false
}

const results = []

/* ------------------------------ 登录 ------------------------------ */
async function uiLogin(page, username) {
  await goto(page, '/login')
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(PASSWORD)
  await page.getByRole('button', { name: /登\s*录/ }).click()
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 })
  await settle(page)
  log.info(`已登录 ${username}（${page.url()}）`)
}

/* ------------------------------ 小工具：填日期 ------------------------------ */
async function fillDatePicker(page, placeholder, iso) {
  const input = page.getByPlaceholder(placeholder).first()
  await input.click().catch(() => {})
  await input.fill('').catch(() => {})
  // Element Plus 的日期输入支持直接键入 + 回车确定
  await input.pressSequentially(iso, { delay: 15 }).catch(() => {})
  await page.keyboard.press('Enter')
  await page.waitForTimeout(250)
  const value = await input.inputValue().catch(() => '')
  return value === iso
}

/* ==================================================================== */
/*  3. 采集流程                                                          */
/* ==================================================================== */
let browser

async function newContext() {
  const ctx = await browser.newContext({
    viewport: VIEWPORT,
    deviceScaleFactor: DSF,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    permissions: [],
  })
  ctx.setDefaultTimeout(25000)
  return ctx
}

async function captureLogin() {
  log.step('01 登录页')
  const ctx = await newContext()
  const page = await ctx.newPage()
  wirePage(page, 'login')
  await goto(page, '/login')
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'networkidle' })
  await settle(page)
  await shot(page, {
    file: '01-login.png',
    title: '登录页',
    checks: [
      checks.noConsoleError,
      checks.noSkeleton,
      checks.noLoading,
      checks.text('海外宿舍管理系统'),
      checks.text('账号登录'),
      checks.minCount('input', 2),
    ],
  })
  await ctx.close()
}

/** 02 房源查询（员工端）：区域 + 日期过滤，卡片上带房态与可订床位 */
async function captureRooms(page) {
  log.step('02 房源查询（员工端）')
  await goto(page, '/rooms')
  await shot(page, {
    file: '02-rooms.png',
    title: '房源查询',
    prepare: async (pg) => {
      // 选一个有房态差异的区域 + 日期区间，让「可订床位/预计总价」都出现
      await pg.locator('.filter-bar .el-select').first().click()
      await pg.locator('.el-select-dropdown__item', { hasText: '东南亚' }).first().click()
      await pg.waitForTimeout(500)
      await fillDatePicker(pg, '入住日期', localDay(7))
      await fillDatePicker(pg, '退房日期', localDay(10))
      await pg.getByRole('button', { name: /查询/ }).click()
      await pg.waitForTimeout(1200)
      await settle(pg)
    },
    checks: [
      checks.noConsoleError,
      checks.noSkeleton,
      checks.noLoading,
      checks.noErrorToast,
      checks.noErrorState,
      checks.text('房源查询'),
      checks.text('可订床位'),
      checks.text('床位明细'),
      checks.minCount('.room-card', 6),
    ],
  })
}

/** 03 提交入住申请：把表单填满但不点提交（不产生脏数据） */
async function captureApplyForm(page) {
  /* ---------------------------- 03 提交申请表单 ---------------------------- */
  log.step('03 提交入住申请（填好但不提交）')
  const applyUrl = `/apply/create?regionId=1001&buildingId=2001&roomType=SINGLE`
    + `&checkInDate=${localDay(12)}&checkOutDate=${localDay(15)}&city=${encodeURIComponent('新加坡')}`
  await goto(page, applyUrl)
  await shot(page, {
    file: '03-apply-form.png',
    title: '提交入住申请（表单已填好，未提交）',
    prepare: async (pg) => {
      await pg.getByPlaceholder('例如：TRIP-2026-001（选填）').fill('TRIP-2026-1187')
      await pg.getByPlaceholder('请简要说明出差事由（选填）')
        .fill('赴新加坡参与客户侧数据平台割接，需在纬壹园区就近住宿 3 晚，白天在现场、晚上待命。')
      await pg.locator('body').click({ position: { x: 5, y: 5 } })
      await pg.waitForTimeout(600)
    },
    checks: [
      checks.noConsoleError,
      checks.noSkeleton,
      checks.noLoading,
      checks.noErrorToast,
      checks.noAlert,
      checks.text('提交入住申请'),
      checks.text('预估总价'),
      checks.text('住宿'),
      async (pg) => (await pg.getByPlaceholder('例如：TRIP-2026-001（选填）').inputValue()) === 'TRIP-2026-1187'
        || '出差单号没有填进表单',
      async (pg) => (await pg.getByPlaceholder('请简要说明出差事由（选填）').inputValue()).length > 10
        || '出差事由没有填进表单',
      // 关键：没有提交，页面必须仍停留在表单页
      async (pg) => pg.url().includes('/apply/create') || '表单页被跳走了（说明误提交）',
    ],
  })
}

/** 04 我的申请：同一员工覆盖状态机多个状态 */
async function captureMyApplies(page) {
  /* ---------------------------- 04 我的申请 ---------------------------- */
  log.step('04 我的申请（状态流转）')
  await goto(page, '/apply/mine')
  await shot(page, {
    file: '04-my-applies.png',
    title: '我的申请',
    checks: [
      checks.noConsoleError,
      checks.noSkeleton,
      checks.noLoading,
      checks.noErrorToast,
      checks.noErrorState,
      checks.text('我的申请'),
      checks.minRows('.el-table__body tbody tr', 4),
      checks.text('待审批'),
      checks.text('已入住'),
      checks.text('已退房'),
    ],
  })
}

/* ------------------------------ AI 助手 ------------------------------ */
/**
 * 主问题就是面试官最可能问的那句业务问题；后两个是备选——
 * 只有当第一轮没同时命中「工具调用 + 引用来源」时才换问题重试（每次都是真实模型调用）。
 */
const AGENT_QUESTIONS = [
  '我是上海总部的员工，下个月要去新加坡出差三天，想申请公司宿舍。请告诉我完整的申请流程（含审批步骤与时限），以及东南亚区域现在有哪些公寓和房源可选？请用「小标题 + 编号列表 + 表格」组织答案，方便我快速浏览。',
  '上海员工去新加坡出差，怎么申请宿舍？有哪些部门审批、时限多久？',
  '我下周去法兰克福出差，宿舍怎么申请？有哪些公寓可以选，费用怎么算？',
]

/**
 * 在 AI 助手页发起一轮真实提问，等流式结束，并（可选）全程连续截图给 GIF 用。
 * 返回本轮证据：是否出现工具调用 / 引用来源 / 回答字数 / 帧序列。
 */
async function askAgent(page, question, { captureFrames = false } = {}) {
  await goto(page, '/agent', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1200)
  // 新建会话，保证从空白对话开始（GIF 也一样）
  const newConv = page.getByRole('button', { name: /新建会话/ })
  if (await newConv.count()) {
    await newConv.first().click()
    await page.waitForTimeout(800)
  }
  const box = page.getByPlaceholder('输入问题，Enter 发送，Shift + Enter 换行')
  await box.click()
  await box.fill(question)
  await page.waitForTimeout(300)

  let frames = []
  let stopCapture = false
  let captureTask = null
  if (captureFrames) {
    await fs.rm(FRAMES, { recursive: true, force: true })
    await fs.mkdir(FRAMES, { recursive: true })
    captureTask = (async () => {
      let i = 0
      while (!stopCapture && i < 90) {
        const file = path.join(FRAMES, `f-${String(i).padStart(3, '0')}.png`)
        try {
          await page.screenshot({ path: file })
          frames.push(file)
        } catch { /* 页面忙时忽略这一帧 */ }
        i++
        await sleep(220)
      }
    })()
  }

  await page.getByRole('button', { name: /发\s*送/ }).click()

  // 等流式结束：底部「停止生成」按钮消失，且已经出现工具调用或引用
  const started = Date.now()
  let done = false
  while (Date.now() - started < 150000) {
    const streaming = await page.locator('[data-test="agent-stop"]').count()
    const tool = await page.locator('[data-test="tool-block"]').count()
    const ref = await page.locator('[data-test="ref-block"]').count()
    const failed = await page.locator('[data-test="status-failed"]').count()
    if (failed) break
    if (!streaming && (tool > 0 || ref > 0)) {
      done = true
      break
    }
    await page.waitForTimeout(500)
  }
  // 收尾等 1 秒，让最后一帧内容渲染完
  await page.waitForTimeout(1000)
  stopCapture = true
  if (captureTask) await captureTask

  const hasTool = (await page.locator('[data-test="tool-block"]').count()) > 0
  const hasRef = (await page.locator('[data-test="ref-block"]').count()) > 0
  const answer = await page.locator('[data-test="answer"]').first().innerText().catch(() => '')
  // Markdown 渲染是否干净：如果正文里还残留 "## " / "|---" 这类未渲染的标记，
  // 说明模型这次没有按「标题前空行」输出，出图会显得像裸文本，换问题重试一次。
  const mdClean = !/##\s|\|\s*-{2,}/.test(answer)
  log.info(`提问完成：${done ? '流式结束' : '未确认结束'}`
    + `，工具调用 ${hasTool ? '有' : '无'}，引用 ${hasRef ? '有' : '无'}，`
    + `Markdown ${mdClean ? '渲染正常' : '有残留标记'}，`
    + `答案 ${answer.length} 字，耗时 ${((Date.now() - started) / 1000).toFixed(1)}s，帧 ${frames.length} 张`)
  return { hasTool, hasRef, answer, mdClean, frames, done }
}

/** 刷新后从历史打开刚完成的那条会话：历史里的长回答默认折叠，三块证据更容易同屏 */
async function openLatestConversation(page) {
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForTimeout(1800)
  const item = page.locator('[data-test="conv-item"]').first()
  if (await item.count()) {
    await item.click()
    await page.waitForTimeout(2200)
  }
}

/**
 * 让「工具调用 + 回答 + 引用来源」三块在同一屏里露出最多内容。
 *
 * 为什么不是简单滚到底：回答按 Markdown 渲染后高度会变化，三块加起来经常超过
 * 聊天区可视高度（约 640px）。这里以「引用贴底」为基准，在 ±260px 内搜索一个
 * 使三块都露头、且完整可见的引用 chip / 工具卡片最多的滚动位置。
 */
async function alignBestEvidence(page) {
  await page.evaluate(() => {
    const log = document.querySelector('[data-test="chat-log"]')
    const tool = document.querySelector('[data-test="tool-block"]')
    const answer = document.querySelector('[data-test="answer"]')
    const ref = document.querySelector('[data-test="ref-block"]')
    if (!log) return
    const logRect = log.getBoundingClientRect()
    const top = logRect.top
    const bottom = logRect.bottom
    const vis = (el) => {
      if (!el) return 0
      const r = el.getBoundingClientRect()
      return Math.max(0, Math.min(bottom, r.bottom) - Math.max(top, r.top))
    }
    const cards = [...document.querySelectorAll('[data-test="tool-block"] .tool-card')]
    const chips = [...document.querySelectorAll('[data-test="ref-block"] .ref-chip')]
    const chipsInside = () => chips
      .filter((c) => {
        const r = c.getBoundingClientRect()
        return r.top >= top && r.bottom <= bottom
      }).length
    // 露出一半以上的工具卡片数量：截图要能看出「多个工具协同」
    const cardsInside = () => cards
      .filter((c) => vis(c) >= Math.min(60, c.getBoundingClientRect().height * 0.6)).length
    // 基准位置：引用来源贴住聊天区底部
    if (ref) log.scrollTop += ref.getBoundingClientRect().bottom - bottom + 12
    const base = log.scrollTop
    const logMax = log.scrollHeight
    let best = { score: -1, top: base }
    for (let d = -420; d <= 420; d += 8) {
      const next = Math.max(0, Math.min(logMax, base + d))
      log.scrollTop = next
      const toolVis = vis(tool)
      const ansVis = vis(answer)
      const refVis = vis(ref)
      const chip = chipsInside()
      const card = cardsInside()
      // 硬条件：三块都要露头，且至少 2 张工具卡片、2 个引用 chip 完整可见
      if (toolVis < 150 || ansVis < 200 || refVis < 70 || chip < 2 || card < 2) continue
      // 满足硬条件后：优先更多可见工具卡片，其次更多引用 chip
      const score = card * 150 + chip * 100 + Math.min(toolVis, 240) + Math.min(ansVis, 340) * 0.5
      if (score > best.score) best = { score, top: next }
    }
    log.scrollTop = best.score >= 0 ? best.top : base
  })
  await page.waitForTimeout(500)
}

/** 把「工具调用」块贴到聊天区顶部，用于补拍完整工具卡片 */
async function alignEvidenceTop(page) {
  await page.evaluate(() => {
    const log = document.querySelector('[data-test="chat-log"]')
    const tool = document.querySelector('[data-test="tool-block"]')
    if (!log || !tool) return
    log.scrollTop += tool.getBoundingClientRect().top - log.getBoundingClientRect().top - 8
  })
  await page.waitForTimeout(500)
}

async function captureAgent(page) {
  log.step('08 AI 助手对话（流式 + 引用 + 工具调用）')
  let evidence = null
  const maxAttempts = Math.min(2, AGENT_QUESTIONS.length)
  for (let i = 0; i < maxAttempts; i++) {
    const q = AGENT_QUESTIONS[i]
    log.info(`第 ${i + 1} 次提问（同时录制 GIF 帧）：${q}`)
    evidence = await askAgent(page, q, { captureFrames: true })
    // 硬条件：必须同时出现工具调用与引用来源；Markdown 排版只做提示，不作为重试条件
    if (evidence.hasTool && evidence.hasRef) break
    if (i + 1 < maxAttempts) {
      log.warn('本轮证据不完整（需要同时出现工具调用与引用来源），换一个问题重试')
    }
  }
  if (!evidence?.frames?.length) log.warn('没有录到帧，GIF 会被跳过')

  // 08：一屏内同时看到「工具调用卡片 + 回答 + 引用来源」
  await shot(page, {
    file: '08-agent-chat.png',
    title: 'AI 助手对话（工具调用 + 流式回答 + 引用溯源）',
    prepare: async (pg) => {
      await openLatestConversation(pg)
      // 长回答默认会折叠成摘要；若当前是展开态，先点「收起」，
      // 否则「工具 + 回答 + 引用」三块不可能挤进一屏
      const toggle = pg.locator('[data-test="answer-toggle"]').first()
      if (await toggle.count()) {
        const label = await toggle.innerText().catch(() => '')
        if (label.includes('收起')) {
          await toggle.click().catch(() => {})
          await pg.waitForTimeout(600)
        }
      }
      await alignBestEvidence(pg)
    },
    checks: [
      checks.noConsoleError,
      checks.noSkeleton,
      checks.noLoading,
      checks.noErrorState,
      async (pg) => (await pg.locator('[data-test="agent-stop"]').count()) === 0 || '仍在生成中（还有停止按钮）',
      checks.text('RAG 已启用'),
      checks.text('工具调用'),
      checks.text('参考来源'),
      async (pg) => (await pg.getByText(/参考来源/).count()) > 0 || '没有「参考来源」区块',
      // 以聊天滚动区为边界统计可见高度（被容器裁掉的部分不算）
      async (pg) => {
        const m = await pg.evaluate(() => {
          const log = document.querySelector('[data-test="chat-log"]')
          if (!log) return null
          const b = log.getBoundingClientRect()
          const inside = (sel) => {
            const el = document.querySelector(sel)
            if (!el) return 0
            const r = el.getBoundingClientRect()
            return Math.max(0, Math.min(b.bottom, r.bottom) - Math.max(b.top, r.top))
          }
          const cards = [...document.querySelectorAll('[data-test="tool-block"] .tool-card')]
            .filter((c) => {
              const r = c.getBoundingClientRect()
              return Math.min(b.bottom, r.bottom) - Math.max(b.top, r.top) >= 60
            }).length
          const chips = [...document.querySelectorAll('[data-test="ref-block"] .ref-chip')]
            .filter((c) => {
              const r = c.getBoundingClientRect()
              return r.top >= b.top && r.bottom <= b.bottom
            }).length
          return {
            tool: Math.round(inside('[data-test="tool-block"]')),
            answer: Math.round(inside('[data-test="answer"]')),
            ref: Math.round(inside('[data-test="ref-block"]')),
            cards,
            chips,
          }
        })
        if (!m) return '找不到聊天区'
        if (m.tool < 150) return `工具调用区块在聊天区里只露出 ${m.tool}px（期望 ≥ 150）`
        if (m.cards < 2) return `露出大半的工具卡片只有 ${m.cards} 张（期望 ≥ 2）`
        if (m.answer < 200) return `回答正文只露出 ${m.answer}px（期望 ≥ 200）`
        if (m.ref < 70) return `引用来源区块只露出 ${m.ref}px（期望 ≥ 70）`
        if (m.chips < 2) return `完整可见的引用 chip 只有 ${m.chips} 个（期望 ≥ 2）`
        return true
      },
      // 回答必须是 Markdown 渲染过的（标题 / 列表 / 表格任意一种）
      async (pg) => {
        const n = await pg.evaluate(() => document.querySelectorAll(
          '[data-test="answer"] h1, [data-test="answer"] h2, [data-test="answer"] h3,'
          + ' [data-test="answer"] table, [data-test="answer"] ol, [data-test="answer"] ul',
        ).length)
        return n > 0 || '回答没有渲染出 Markdown 结构（标题/列表/表格）'
      },
      async (pg) => {
        const n = (await pg.locator('[data-test="answer"]').first().innerText().catch(() => '')).length
        return n > 200 || `回答正文过短（${n} 字）`
      },
    ],
  })

  // 08b：把工具块滚到顶部，完整展示每个工具的入参与结果（补充证据）
  await shot(page, {
    file: '08b-agent-tools.png',
    title: 'AI 助手对话 - 工具调用明细（入参 / 结果 / 耗时）',
    prepare: async (pg) => {
      await alignEvidenceTop(pg)
      // 展开一两条入参，证明工具真的带业务参数
      const expanders = pg.locator('[data-test="tool-block"] .tool-card-args-head').first()
      if (await expanders.count()) {
        await expanders.click({ trial: false }).catch(() => {})
        await pg.waitForTimeout(400)
      }
    },
    checks: [
      checks.noConsoleError,
      checks.noErrorState,
      async (pg) => {
        const box = await pg.locator('[data-test="tool-block"]').first().boundingBox().catch(() => null)
        if (!box) return '工具调用区块不可见'
        const vh = page.viewportSize()?.height ?? 900
        const visible = Math.min(vh, box.y + box.height) - Math.max(0, box.y)
        const ratio = box.height > 0 ? visible / box.height : 0
        return ratio >= 0.9 || `工具调用区块只有 ${(ratio * 100).toFixed(0)}% 露在视口里`
      },
      async (pg) => (await pg.locator('[data-test="tool-block"] .tool-card').count()) >= 2
        || '工具调用卡片过少',
      checks.text('入参'),
    ],
  })

  // 08c：引用溯源特写——回答尾部 + 全部引用 chip + 耗时/首字/tokens/模型
  await shot(page, {
    file: '08c-agent-refs.png',
    title: 'AI 助手对话 - 引用溯源与调用元信息',
    networkidle: false,
    prepare: async (pg) => {
      await pg.evaluate(() => {
        const log = document.querySelector('[data-test="chat-log"]')
        if (log) log.scrollTop = log.scrollHeight
      })
      await pg.waitForTimeout(700)
    },
    checks: [
      checks.noConsoleError,
      checks.noErrorState,
      checks.noSkeleton,
      checks.text('参考来源'),
      checks.text('耗时'),
      async (pg) => {
        const m = await pg.evaluate(() => {
          const log = document.querySelector('[data-test="chat-log"]')
          if (!log) return null
          const b = log.getBoundingClientRect()
          const ref = document.querySelector('[data-test="ref-block"]')
          if (!ref) return null
          const r = ref.getBoundingClientRect()
          const chips = [...document.querySelectorAll('[data-test="ref-block"] .ref-chip')]
            .filter((c) => {
              const cr = c.getBoundingClientRect()
              return cr.top >= b.top && cr.bottom <= b.bottom
            })
          return {
            chips: chips.length,
            refVis: Math.round(Math.min(b.bottom, r.bottom) - Math.max(b.top, r.top)),
          }
        })
        if (!m) return '找不到引用来源区块'
        if (m.chips < 4) return `完整可见的引用 chip 只有 ${m.chips} 个（期望 ≥ 4）`
        if (m.refVis < 80) return `引用来源区块只露出 ${m.refVis}px（期望 ≥ 80）`
        return true
      },
    ],
  })

  return evidence
}

async function captureAdmin() {
  const ctx = await newContext()
  const page = await ctx.newPage()
  wirePage(page, 'admin')
  await uiLogin(page, ADMIN)

  /* ---------------------------- 05 申请审批 ---------------------------- */
  log.step('05 申请审批（管理员）')
  await goto(page, '/admin/approve')
  await shot(page, {
    file: '05-admin-approve.png',
    title: '申请审批（全状态申请池）',
    prepare: async (pg) => {
      // 页面默认只看「待审批」；清掉状态过滤并重新查询，才能看出完整状态机
      const statusSelect = pg.locator('.filter-bar .el-select').first()
      await statusSelect.hover()
      await pg.waitForTimeout(300)
      const clear = pg.locator('.filter-bar .el-select .el-select__clear').first()
      if (await clear.count()) {
        await clear.click({ force: true })
        await pg.waitForTimeout(300)
        await pg.getByRole('button', { name: /查\s*询/ }).click()
        await pg.waitForTimeout(1500)
      }
      // 宽表在 1440 下会横向溢出，折叠侧栏多出 152px 才能看到「状态」列
      await collapseSidebar(pg)
    },
    checks: [
      checks.noConsoleError,
      checks.noSkeleton,
      checks.noLoading,
      checks.noErrorToast,
      checks.noErrorState,
      checks.text('申请审批'),
      checks.text('审批分房'),
      checks.minRows('.el-table__body tbody tr', 8),
      checks.text('待审批'),
      checks.text('已入住'),
      checks.text('已退房'),
    ],
  })

  /* ---------------------------- 05b 审批分房弹窗（加分项） ---------------------------- */
  log.step('05b 审批分房弹窗（选房 + 选床位，不提交）')
  const approveBtn = page.getByRole('button', { name: '审批分房' }).first()
  if (await approveBtn.count()) {
    await approveBtn.click()
    await page.waitForTimeout(1500)
    // 选中一间房，让右侧床位面板也亮起来（只看不提交）
    const roomRow = page.locator('.el-dialog .el-table__body tbody tr').first()
    if (await roomRow.count()) {
      await roomRow.click()
      await page.waitForTimeout(1000)
    }
    await shot(page, {
      file: '05b-approve-dialog.png',
      title: '审批分房弹窗（按区域/日期过滤可订房间 + 选床位）',
      networkidle: false,
      checks: [
        checks.noConsoleError,
        checks.noSkeleton,
        async (pg) => (await pg.locator('.el-dialog:visible').count()) > 0 || '弹窗未打开',
        async (pg) => {
          const t = await pg.locator('.el-dialog:visible').first().innerText()
          return (t.includes('选择房间') && t.includes('床位')) || '弹窗内容不完整'
        },
        async (pg) => (await pg.locator('.el-dialog:visible .el-table__body tbody tr').count()) > 0
          || '弹窗内可订房间表格没有数据',
        checks.noErrorToast,
      ],
    })
    await page.keyboard.press('Escape')
    await page.waitForTimeout(500)
  } else {
    log.warn('没有待审批申请，跳过 05b（可先跑一次造数据）')
  }

  /* ---------------------------- 06 订单管理 ---------------------------- */
  log.step('06 订单管理')
  await goto(page, '/admin/orders')
  await shot(page, {
    file: '06-admin-orders.png',
    title: '订单管理',
    prepare: async (pg) => {
      await collapseSidebar(pg)
    },
    checks: [
      checks.noConsoleError,
      checks.noSkeleton,
      checks.noLoading,
      checks.noErrorToast,
      checks.noErrorState,
      checks.text('订单管理'),
      checks.minRows('.el-table__body tbody tr', 4),
      checks.text('已入住'),
      async (pg) => {
        const body = await pg.locator('body').innerText()
        return /SGD|EUR|AED|JPY/.test(body) || '订单金额未展示币种'
      },
    ],
  })

  /* ---------------------------- 07 统计看板 ---------------------------- */
  log.step('07 统计看板（ECharts）')
  await goto(page, '/admin/dashboard')
  await shot(page, {
    file: '07-dashboard.png',
    title: '统计看板',
    networkidle: false,
    after: async (pg) => {
      // 等 echarts 动画走完（默认 1s 左右）+ 再稳一下
      await pg.waitForTimeout(1800)
    },
    checks: [
      checks.noConsoleError,
      checks.noSkeleton,
      checks.noLoading,
      checks.noErrorState,
      checks.text('统计看板'),
      checks.text('入住率'),
      checks.chartsPainted(4),
      checks.minRows('.el-table__body tbody tr', 4),
    ],
  })

  /* ---------------------------- 09 Agent 运营看板 ---------------------------- */
  // 这一页有 6 个标签页，一次只能截一个：工具清单内容最满（20 个只读工具 + 5 个写工具 + 已装配 Agent），
  // 模型健康与 RAG 质量另外补两张，覆盖「会不会出事 / 答得准不准」两个运维问题。
  log.step('09 Agent 运营看板（工具清单）')
  await goto(page, '/admin/agent-ops')
  await shot(page, {
    file: '09-agent-ops.png',
    title: 'Agent 运营看板 - 工具清单（只读/写分离、已装配 Agent）',
    prepare: async (pg) => {
      const tab = pg.getByRole('tab', { name: '工具清单' })
      if (await tab.count()) {
        await tab.first().click()
        await pg.waitForTimeout(1500)
      }
    },
    checks: [
      checks.noConsoleError,
      checks.noSkeleton,
      checks.noLoading,
      checks.noErrorToast,
      checks.noErrorState,
      checks.text('Agent 运营看板'),
      checks.text('只读工具数'),
      checks.text('已装配 Agent'),
      checks.text('工具名'),
      checks.minRows('.el-table__body tbody tr', 10),
    ],
  })

  /* ---------------------------- 09b RAG 质量 ---------------------------- */
  log.step('09b Agent 运营看板 - RAG 质量')
  await shot(page, {
    file: '09b-agent-rag.png',
    title: 'Agent 运营看板 - RAG 质量（无命中率 / 平均最高分 / 点踩率）',
    networkidle: false,
    prepare: async (pg) => {
      const tab = pg.getByRole('tab', { name: 'RAG 质量' })
      if (await tab.count()) {
        await tab.first().click()
        await pg.waitForTimeout(1600)
      }
    },
    checks: [
      checks.noConsoleError,
      checks.noErrorToast,
      checks.noSkeleton,
      checks.text('RAG 质量'),
      checks.text('平均最高分'),
      checks.text('无命中率'),
    ],
  })

  /* ---------------------------- 09c 模型健康 ---------------------------- */
  log.step('09c Agent 运营看板 - 模型健康与熔断')
  await shot(page, {
    file: '09c-agent-models.png',
    title: 'Agent 运营看板 - 模型健康与熔断',
    networkidle: false,
    prepare: async (pg) => {
      const tab = pg.getByRole('tab', { name: '模型健康状态' })
      if (await tab.count()) {
        await tab.first().click()
        await pg.waitForTimeout(1200)
      }
    },
    checks: [
      checks.noConsoleError,
      checks.noErrorToast,
      checks.noSkeleton,
      checks.text('模型健康状态'),
      checks.text('成功率'),
      checks.text('熔断'),
      checks.minRows('.el-table__body tbody tr', 1),
    ],
  })

  /* ---------------------------- 10 审计日志 ---------------------------- */
  log.step('10 审计日志')
  await goto(page, '/admin/audits')
  await shot(page, {
    file: '10-audits.png',
    title: '审计日志',
    prepare: async (pg) => {
      await collapseSidebar(pg)
    },
    checks: [
      checks.noConsoleError,
      checks.noSkeleton,
      checks.noLoading,
      checks.noErrorToast,
      checks.noErrorState,
      checks.text('审计日志'),
      checks.minRows('.el-table__body tbody tr', 8),
      async (pg) => {
        const body = await pg.locator('body').innerText()
        return /AGENT_TURN|APPROVE|LOGIN|CREATE/.test(body) || '审计动作列没有可读内容'
      },
    ],
  })

  await ctx.close()
}

async function captureSwagger() {
  log.step('11 Swagger / OpenAPI 接口文档（加分项）')
  const ctx = await newContext()
  const page = await ctx.newPage()
  wirePage(page, 'swagger')
  await goto(page, SWAGGER, { timeout: 45000 })
  await page.waitForTimeout(2500)
  await shot(page, {
    file: '11-swagger.png',
    title: '业务服务 Swagger UI',
    networkidle: false,
    checks: [
      checks.noSkeleton,
      checks.noLoading,
      checks.minCount('.opblock', 20),
      checks.text('海外宿舍管理系统 API'),
      checks.text('/api/auth/login'),
    ],
  })
  await ctx.close()
}

/* ==================================================================== */
/*  4. GIF 合成（连续截图 -> 量化 -> GIF）                                */
/* ==================================================================== */
const GIF_PY = String.raw`
import sys, os, glob
from PIL import Image

frames_dir, out_path = sys.argv[1], sys.argv[2]
files = sorted(glob.glob(os.path.join(frames_dir, 'f-*.png')))
if not files:
    print('NO_FRAMES'); sys.exit(2)

# 目标：8~15 秒、宽度 <=1000、体积 <5MB
total = len(files)
# 均匀抽样，最多 44 帧（再多体积就不好控了）
step = max(1, (total + 43) // 44)
picked = files[::step]
if picked[-1] != files[-1]:
    picked.append(files[-1])
# 帧间隔按"总时长约 11 秒"反算，保证 GIF 落在 8~15 秒区间
delay_per_frame = int(round(11000 / len(picked)))
delay_per_frame = max(240, min(900, delay_per_frame))

def build(width, colors, dest):
    imgs = []
    for f in picked:
        im = Image.open(f).convert('RGB')
        h = max(1, round(im.height * width / im.width))
        im = im.resize((width, h), Image.LANCZOS)
        imgs.append(im.quantize(colors=colors, method=Image.MEDIANCUT, dither=Image.NONE))
    imgs[0].save(dest, save_all=True, append_images=imgs[1:], duration=delay_per_frame,
                 loop=0, optimize=True, disposal=1)
    return os.path.getsize(dest)

best = None
for width in (1000, 900):
    for colors in (128, 96, 64):
        dest = out_path + '.tmp.gif'
        try:
            size = build(width, colors, dest)
        except Exception as e:
            print('BUILD_FAIL', width, colors, e); continue
        print(f'TRY width={width} colors={colors} size={size/1024/1024:.2f}MB frames={len(picked)} delay={delay_per_frame}')
        if best is None or size < best[0]:
            best = (size, dest)
        if size < 4.6 * 1024 * 1024:
            break
    if best and best[0] < 4.6 * 1024 * 1024:
        break
if best is None:
    print('ALL_FAIL'); sys.exit(3)
os.replace(best[1], out_path)
print(f'OK size={best[0]/1024/1024:.2f}MB out={out_path}')
`

let gifMeta = null

async function buildGif(frames) {
  if (!frames?.length) {
    log.warn('没有可用帧，跳过 GIF 合成')
    return false
  }
  log.step(`合成演示 GIF（${frames.length} 帧 -> docs/screenshots/agent-stream.gif）`)
  try {
    const out = execFileSync('python3', ['-c', GIF_PY, FRAMES, path.join(OUT, 'agent-stream.gif')], {
      encoding: 'utf8',
      timeout: 180000,
    })
    console.log(out.trim().split('\n').map((l) => '  ' + l).join('\n'))
    const line = out.split('\n').filter((l) => l.startsWith('TRY') || l.startsWith('OK')).pop() ?? ''
    const m = line.match(/width=(\d+) colors=(\d+) size=([\d.]+)MB frames=(\d+) delay=(\d+)/)
    if (m) {
      gifMeta = {
        width: Number(m[1]),
        colors: Number(m[2]),
        sizeMB: Number(m[3]),
        frames: Number(m[4]),
        seconds: Number(((Number(m[4]) * Number(m[5])) / 1000).toFixed(1)),
      }
    }
    const { size } = await fs.stat(path.join(OUT, 'agent-stream.gif'))
    log.ok(`agent-stream.gif ${(size / 1024 / 1024).toFixed(2)}MB`)
    return true
  } catch (err) {
    log.warn(`GIF 合成失败（缺 python3/Pillow？）：${err.message.split('\n')[0]}`)
    return false
  }
}

const GIF_INFO_PY = String.raw`
import sys, os, json
from PIL import Image
im = Image.open(sys.argv[1])
frames, durs = 0, []
try:
    while True:
        durs.append(im.info.get('duration', 0)); frames += 1
        im.seek(im.tell() + 1)
except EOFError:
    pass
print(json.dumps({
    'width': im.size[0],
    'frames': frames,
    'seconds': round(sum(durs) / 1000, 1),
    'sizeMB': round(os.path.getsize(sys.argv[1]) / 1024 / 1024, 2),
}))
`

/* ==================================================================== */
/*  5. manifest.md                                                       */
/* ==================================================================== */
const MANIFEST_ITEMS = [
  ['01-login.png', '/login（未登录）', '登录页：三服务一键启动后的统一入口，含测试账号提示与品牌信息。'],
  ['02-rooms.png', '/rooms（zhangwei 员工端）', '房源查询：按区域/日期/房型过滤，直接展示房态、可订床位数与预估总价。'],
  ['03-apply-form.png', '/apply/create（已填好、未提交）', '提交入住申请：四步流程条 + 晚数与预估总价实时计算 + 幂等键防重复提交设计。'],
  ['04-my-applies.png', '/apply/mine（zhangwei）', '我的申请：同一员工覆盖待审批/已通过/已入住/已退房/已驳回的完整状态流转。'],
  ['05-admin-approve.png', '/admin/approve（superadmin）', '申请审批：管理员视角的申请池（清掉默认「待审批」过滤，10 行覆盖待审批/已通过/已入住/已退房/已驳回），操作列按状态给出审批分房/驳回/换房动作。'],
  ['05b-approve-dialog.png', '/admin/approve → 审批分房弹窗', '审批分房：按申请区域与日期过滤可订房间，右侧实时显示床位占用，选床后一次事务落库。'],
  ['06-admin-orders.png', '/admin/orders（superadmin）', '订单管理：申请审批入库自动生成订单，含入住人、房间床位、金额币种、实际入住退房与状态。'],
  ['07-dashboard.png', '/admin/dashboard（superadmin）', '统计看板：入住率仪表盘、申请状态分布、近 14 天趋势、各区域房源分布四张 ECharts 实测渲染。'],
  ['08-agent-chat.png', '/agent（zhangwei，真实提问）', 'AI 助手：一屏同时看到「工具调用卡片 + 流式回答 + RAG 引用来源」，回答可点开引用溯源。'],
  ['08b-agent-tools.png', '/agent（同一轮对话，滚到工具调用区）', '工具调用明细：每个工具的名称、业务入参（区域/城市/房型）与返回结果，证明回答来自真实业务接口而不是模型编造。'],
  ['08c-agent-refs.png', '/agent（同一轮对话，滚到回答尾部）', '引用溯源：回答尾部的「参考来源」chip 列表 + 耗时/首字耗时/tokens/模型元信息，说明每个结论都能回到知识库原文。'],
  ['09-agent-ops.png', '/admin/agent-ops → 工具清单（superadmin）', 'Agent 运营看板：20 个只读工具 + 5 个写工具、读写分离、已装配 Agent（APPLY#EMPLOYEE / POLICY#EMPLOYEE / ROOM#EMPLOYEE）与单轮超时，回答「Agent 到底能做什么、有没有危险动作」。'],
  ['09b-agent-rag.png', '/admin/agent-ops → RAG 质量', 'RAG 质量：无命中率、平均最高相似度、点踩率、平均检索耗时与缺口问题榜——回答「知识库到底够不够用」。'],
  ['09c-agent-models.png', '/admin/agent-ops → 模型健康状态', '模型健康：主模型 / 快速 / 两级 fallback 的可用性、成功率、连续失败与熔断剩余，外加限流与助手清单。'],
  ['10-audits.png', '/admin/audits（superadmin）', '审计日志：谁在什么时候对哪条数据做了什么、结果与耗时，含 traceId 可串起整条调用链。'],
  ['11-swagger.png', 'http://127.0.0.1:8081/swagger-ui.html', '接口文档：业务服务的 OpenAPI，说明接口契约与统一响应包装。'],
  ['agent-stream.gif', '/agent 提问后的流式过程（约 11s，1000px 宽，1.3MB）', '动态证明：回答是逐字流式输出（SSE）而非一次性返回，同时可看到工具调用与引用逐步出现。'],
]

async function writeManifest() {
  const rows = []
  for (const [file, page, prove] of MANIFEST_ITEMS) {
    const hit = results.find((r) => r.file === file)
    let state
    if (hit) {
      state = hit.ok ? '✅ 本次采集通过校验' : '⚠️ 本次未通过校验（失败样本见 `.work/capture/rejected-*`）'
    } else {
      const size = await fs.stat(path.join(OUT, file)).then((st) => st.size).catch(() => 0)
      state = size ? `✅ 已采集（${(size / 1024).toFixed(0)}KB）` : '— 缺失'
    }
    rows.push(`| \`${file}\` | ${page} | ${prove} | ${state} |`)
  }
  const list = rows.join('\n')
  const runLine = '所有图片都由同一套校验产出：等待网络空闲 + 无加载骨架 + 无错误提示 + 表格/图表有数据 + 控制台无 error，'
    + '未通过校验的那一次不会落盘（失败样本留在 `.work/capture/rejected-*`）。'
  // GIF 规格优先用本次合成结果；没有再读文件（Pillow）算帧数与总时长
  let spec = gifMeta
  if (!spec) {
    try {
      const out = execFileSync('python3', ['-c', GIF_INFO_PY, path.join(OUT, 'agent-stream.gif')], {
        encoding: 'utf8',
        timeout: 30000,
      })
      spec = JSON.parse(out.trim())
    } catch { /* 没有 Pillow 就跳过 */ }
  }
  const gifLine = spec
    ? `GIF 规格：\`agent-stream.gif\` ${spec.width}px 宽、${spec.frames} 帧、${spec.seconds}s、${spec.sizeMB}MB（循环播放）。`
    : 'GIF 规格：见文件本身。'

  const md = `# 面试展示截图与演示 GIF

> 统一口径：**1440×900 逻辑像素、deviceScaleFactor=2（2880×1800 物理像素）**，浅色主题，中文界面，
> 采集时等待网络空闲、无加载骨架、无 \`el-message\` 报错、控制台无 error；同一页面最多重试 3 次取通过校验的一张。
> 全部由 \`scripts/capture-screenshots.mjs\` 自动采集，可复跑（演示数据走 \`/api/**\` 正常接口，幂等）。

${runLine}
${gifLine}

## 清单

| 文件 | 页面 | 想向面试官证明什么 | 本次采集 |
| --- | --- | --- | --- |
${list}

## 采集口径与取舍

- **AI 助手的三张图（08 / 08b / 08c）取自同一轮真实对话**：提问后等流式结束（DONE），再刷新页面从历史打开该会话。
  历史里较长的回答会折叠成摘要，这样才能在 900px 高度里同时放下「工具调用卡片 + 回答 + 参考来源」；
  \`agent-stream.gif\` 用的是同一轮对话的连续截图，能看到正文逐字出现、工具卡片与引用先后出现。
- **申请审批页**默认只看「待审批」，截图前清掉状态过滤并重新查询，一屏 10 行覆盖 5 种状态；
  宽表在 1440 宽下会折叠左侧导航，否则「状态」列会被固定操作列挡住。
- **演示数据**由脚本通过 \`/api/**\` 造（幂等，按 \`tripNo\` 判重），不直接改库；申请人只用种子账号，不含真实姓名/手机号。
- **统计看板**四张图在 900px 高度内只能完整露出前两张，下方趋势与区域分布属正常滚动区域。

## 怎么复跑

\`\`\`bash
# 前置：三个服务已启动（UI 8080 / 业务 8081 / Agent 8082）
node scripts/capture-screenshots.mjs            # 造演示数据 + 截图 + 合成 GIF
node scripts/capture-screenshots.mjs --no-gif   # 只截图（GIF 耗时且会多打一轮模型）
node scripts/capture-screenshots.mjs --skip-prep --only=07-dashboard
\`\`\`

环境变量（都有默认值，一般不用改）：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| \`UI_URL\` | \`http://127.0.0.1:8080\` | 前端入口（\`/api\`、\`/api/agent\` 由前端同源代理转发） |
| \`SWAGGER_URL\` | \`http://127.0.0.1:8081/swagger-ui.html\` | 业务服务接口文档 |
| \`PLAYWRIGHT_MODULE\` | \`playwright\` | 用已安装的 Playwright 模块（可用路径覆盖） |
| \`PLAYWRIGHT_BROWSERS_PATH\` | Playwright 默认缓存 | 未安装浏览器时先 \`npx playwright install chromium\` |
| \`DEMO_ADMIN\` / \`DEMO_PASSWORD\` | \`superadmin\` / \`Dorm@123456\` | 造数据与管理员页面用 |

## 演示数据是怎么来的

截图里的数据不是手改库：脚本用种子账号（\`zhangwei\` / \`liuyang\` / \`chenjing\` / \`wangfang\`，密码 \`Dorm@123456\`）
调 \`POST /api/applies\` 提交申请，再用 \`superadmin\` 调 \`POST /api/applies/approve\` / \`check-in\` / \`check-out\` / \`reject\`
把 10 条申请推进到状态机的每个状态（待审批 / 已通过 / 已入住 / 已退房 / 已驳回），订单由入住动作自动生成，
审计日志由这些真实写操作自然产生。全程幂等：以 \`tripNo=TRIP-DEMO-xxx\` 判重，重复执行不会产生重复数据。

## 建议 README 用哪几张

主图 4 张（讲清"是什么、谁在用、AI 怎么落地、怎么运维"）：

1. \`07-dashboard.png\` —— 一眼看懂业务规模与数据真实性；
2. \`04-my-applies.png\` + \`05b-approve-dialog.png\` —— 讲清「申请 → 审批分房 → 入住」的核心链路；
3. \`08-agent-chat.png\` —— 讲清 AI 助手不是"套壳聊天"，而是有工具调用与引用溯源的业务 Agent；
4. \`09-agent-ops.png\` + \`09b-agent-rag.png\` —— 讲清 Agent 上线后的可观测性（工具清单与读写分离、模型熔断、RAG 质量）。

次图：\`01-login.png\`（首图氛围）、\`agent-stream.gif\`（流式证据）、\`10-audits.png\`（合规与排障）、
\`11-swagger.png\`（接口契约）。\`02-rooms.png\` / \`03-apply-form.png\` / \`06-admin-orders.png\` 作为功能细节补充。

## 采集环境

- UI：\`http://127.0.0.1:8080\`（Vue3 + Element Plus，浅色主题，history 路由）
- 业务服务：\`http://127.0.0.1:8081\`（Swagger UI 即有效）
- Agent 服务：\`http://127.0.0.1:8082\`（SSE 流式对话，经前端 \`/api/agent\` 代理）
- Playwright：\`npm i -D playwright && npx playwright install chromium\`（或 \`PLAYWRIGHT_MODULE\` 指向已有安装）
- GIF 合成：系统 \`python3\` + Pillow（本机 Playwright 自带 ffmpeg 为裁剪版，不含 gif muxer）

采集时间：${nowIso()}（本地时区）
`
  await fs.writeFile(path.join(OUT, 'manifest.md'), md, 'utf8')
  log.ok('manifest.md 已生成')
}

/* ==================================================================== */
/*  6. main                                                              */
/* ==================================================================== */
async function main() {
  await fs.mkdir(OUT, { recursive: true })
  await fs.mkdir(WORK, { recursive: true })

  if (hasFlag('manifest-only')) {
    await writeManifest()
    return
  }

  if (hasFlag('list')) {
    console.log(MANIFEST_ITEMS.map(([f, p]) => `${f.padEnd(26)} ${p}`).join('\n'))
    return
  }

  // 服务可用性
  const probe = await fetch(`${UI}/login`, { signal: AbortSignal.timeout(5000) })
    .then((r) => r.status).catch(() => 0)
  if (!probe) throw new Error(`UI ${UI} 不可访问，请先启动三个服务`)
  log.info(`UI ${UI} 可访问（HTTP ${probe}）`)

  if (!hasFlag('skip-prep')) await prepareDemoData()
  if (hasFlag('prep-only')) {
    log.ok('仅准备数据（--prep-only），跳过截图')
    return
  }

  browser = await chromium.launch({ headless: true })
  log.info(`浏览器已启动：chromium（1440x900 @2x → ${VIEWPORT.width * DSF}x${VIEWPORT.height * DSF} 物理像素）`)

  let gifFrames = []
  try {
    // --only 既接受短前缀（08 / 09），也接受完整文件名（08-agent-chat.png）
    const run = (prefixes) => !ONLY.length
      || ONLY.some((p) => prefixes.some((x) => p.startsWith(x) || x.startsWith(p)))
    if (run(['01'])) await captureLogin()
    if (run(['02', '03', '04', '08'])) {
      // 员工端页面 + AI 助手共用同一个已登录会话
      const ctx = await newContext()
      const page = await ctx.newPage()
      wirePage(page, 'employee')
      await uiLogin(page, 'zhangwei')
      if (run(['02'])) await captureRooms(page)
      if (run(['03'])) await captureApplyForm(page)
      if (run(['04'])) await captureMyApplies(page)
      if (run(['08'])) {
        const evidence = await captureAgent(page)
        gifFrames = evidence?.frames ?? []
      }
      await ctx.close()
    }
    if (run(['05', '06', '07', '09', '10'])) await captureAdmin()
    if (run(['11'])) await captureSwagger()
    if (!hasFlag('no-gif') && gifFrames.length) await buildGif(gifFrames)
  } finally {
    await browser.close()
  }

  await writeManifest()

  const ok = results.filter((r) => r.ok).length
  log.step(`完成：${ok}/${results.length} 张通过校验`)
  for (const r of results) {
    if (!r.ok) log.bad(`${r.file}：${(r.problems ?? []).slice(-1)[0]}`)
  }
  console.log(`\n产物目录：${OUT}`)
  process.exit(ok === results.length ? 0 : 2)
}

await main()
