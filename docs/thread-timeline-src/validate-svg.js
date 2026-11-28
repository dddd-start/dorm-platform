const fs = require('fs'), vm = require('vm');
const html = fs.readFileSync(process.argv[2], 'utf8');
const blocks = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
const ctx = { console };
vm.createContext(ctx);
vm.runInContext(blocks[0] + '\n;globalThis.__api={ENTRIES,ENTRIES_2,ENTRIES_3,ENTRIES_4,LANES};', ctx);
const api = ctx.__api;
const ALL = [...api.ENTRIES, ...api.ENTRIES_2, ...api.ENTRIES_3, ...api.ENTRIES_4];

// 连同 renderer 一起求值
const rctx = { console };
vm.createContext(rctx);
vm.runInContext(blocks[0] + '\n;globalThis.__api={ENTRIES,ENTRIES_2,ENTRIES_3,ENTRIES_4,LANES,renderDiagram};', rctx);
const R = rctx.__api;

const errs = [];
let svgCount = 0, maxW = 0, maxH = 0;
const sizes = [];

for (const e of ALL) {
  if (e.kind === 'tree') continue;
  let svg;
  try { svg = R.renderDiagram(e); } catch (err) {
    errs.push(e.id + ': 渲染抛异常 ' + err.message); continue;
  }
  svgCount++;
  if (!svg.startsWith('<svg') || !svg.endsWith('</svg>')) errs.push(e.id + ': SVG 不完整');
  // 标签配平
  const open = (svg.match(/<text\b/g) || []).length;
  const close = (svg.match(/<\/text>/g) || []).length;
  if (open !== close) errs.push(e.id + ': <text> 未配平 ' + open + '/' + close);
  // rect 全部应是自闭合 <rect ... />
  const rectAll = (svg.match(/<rect\b/g) || []).length;
  const rectSelf = (svg.match(/<rect\b[^>]*\/>/g) || []).length;
  if (rectAll !== rectSelf) errs.push(e.id + ': rect 未自闭合 ' + rectSelf + '/' + rectAll);
  if (/NaN|undefined|Infinity/.test(svg)) errs.push(e.id + ': SVG 含 NaN/undefined/Infinity');
  if (/&(?!amp;|lt;|gt;|quot;|#)/.test(svg)) errs.push(e.id + ': 存在未转义的 &');
  // 尺寸
  const m = svg.match(/viewBox="0 0 (\d+) (\d+)"/);
  if (!m) { errs.push(e.id + ': 缺 viewBox'); continue; }
  const w = +m[1], h = +m[2];
  sizes.push([e.id, w, h]);
  if (w > maxW) maxW = w;
  if (h > maxH) maxH = h;
  // 所有 x 坐标在画布内
  const xs = [...svg.matchAll(/(?:^|\s)x="(-?[\d.]+)"/g)].map(a => +a[1]);
  const bad = xs.filter(x => x < 0 || x > w);
  if (bad.length) errs.push(e.id + ': x 越界 ' + bad.slice(0,3) + ' / W=' + w);
  // y 坐标
  const ys = [...svg.matchAll(/(?:^|\s)y="(-?[\d.]+)"/g)].map(a => +a[1]);
  const badY = ys.filter(y => y < 0 || y > h);
  if (badY.length) errs.push(e.id + ': y 越界 ' + badY.slice(0,3) + ' / H=' + h);
}

console.log('渲染 SVG:', svgCount, '张');
console.log('最大尺寸: ' + maxW + ' × ' + maxH);
const widest = sizes.sort((a,b)=>b[1]-a[1]).slice(0,4);
console.log('最宽几张:', widest.map(s=>s[0]+'('+s[1]+')').join(', '));
const tallest = [...sizes].sort((a,b)=>b[2]-a[2]).slice(0,4);
console.log('最高几张:', tallest.map(s=>s[0]+'('+s[2]+')').join(', '));
console.log('---');
if (errs.length) { console.log('问题 ' + errs.length + ':'); errs.slice(0,30).forEach(x=>console.log('  • '+x)); process.exit(1); }
else console.log('✓ 全部 SVG 合法');
