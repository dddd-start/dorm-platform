const fs = require('fs'), vm = require('vm');
const html = fs.readFileSync(process.argv[2], 'utf8');
const blocks = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
const ctx = { console };
vm.createContext(ctx);
const tail = '\n;globalThis.__api = {ENTRIES, ENTRIES_2, ENTRIES_3, ENTRIES_4, LANES};';
vm.runInContext(blocks[0] + tail, ctx);
const api = ctx.__api;
const ALL = [...api.ENTRIES, ...api.ENTRIES_2, ...api.ENTRIES_3, ...api.ENTRIES_4];
const LANES = api.LANES;
const errs = [];
const ids = new Set();
let diagramCount = 0;

for (const e of ALL) {
  if (ids.has(e.id)) errs.push('重复 id: ' + e.id);
  ids.add(e.id);
  if (!e.title || !e.desc) errs.push(e.id + ': 缺 title/desc');
  if (e.kind === 'tree') continue;
  diagramCount++;
  const L = e.lanes || [];
  if (!L.length) { errs.push(e.id + ': 无 lanes'); continue; }
  L.forEach((k, i) => { if (!LANES[k]) errs.push(e.id + ': 未知 lane 键 ' + k + ' (列 ' + i + ')'); });
  if (!e.steps || !e.steps.length) { errs.push(e.id + ': 无 steps'); continue; }
  const nums = e.steps.map(s => s.n);
  const expect = Array.from({ length: e.steps.length }, (_, i) => i + 1);
  if (JSON.stringify(nums) !== JSON.stringify(expect)) {
    errs.push(e.id + ': 步骤号不连续 ' + JSON.stringify(nums));
  }
  for (const s of e.steps) {
    if (s.lane == null || s.lane < 0 || s.lane >= L.length)
      errs.push(e.id + ' 步骤 ' + s.n + ': lane 越界 ' + s.lane + ' (共 ' + L.length + ' 列)');
    if (s.to != null) {
      if (s.to < 0 || s.to >= L.length) errs.push(e.id + ' 步骤 ' + s.n + ': to 越界 ' + s.to);
      if (s.to === s.lane) errs.push(e.id + ' 步骤 ' + s.n + ': to 与 lane 相同');
      if (!s.hop) errs.push(e.id + ' 步骤 ' + s.n + ': 跨列缺 hop');
      if (s.hopTo != null && !nums.includes(s.hopTo)) errs.push(e.id + ' 步骤 ' + s.n + ': hopTo=' + s.hopTo + ' 不存在');
    }
    if (!s.text) errs.push(e.id + ' 步骤 ' + s.n + ': 无 text');
  }
  for (const sw of (e.switches || [])) {
    if (!nums.includes(sw.n)) errs.push(e.id + ': switch 步骤 ' + sw.n + ' 不存在');
    if (!sw.how || !sw.why) errs.push(e.id + ' switch ' + sw.n + ': 缺 how/why');
  }
  for (const r of (e.refs || [])) {
    if (!Array.isArray(r) || r.length !== 2) errs.push(e.id + ': ref 格式错 ' + JSON.stringify(r));
  }
}
const groups = {};
ALL.forEach(e => { groups[e.group] = (groups[e.group] || 0) + 1; });
console.log('总数:', ALL.length, '| 时序图:', diagramCount, '| 树形:', ALL.length - diagramCount);
console.log('分组:', JSON.stringify(groups));
console.log('接口数(不含 overview):', ALL.filter(e => e.group !== 'overview').length);
console.log('---');
if (errs.length) { console.log('问题 ' + errs.length + ' 条:'); errs.slice(0, 50).forEach(x => console.log('  • ' + x)); process.exit(1); }
else console.log('✓ 全部通过');
