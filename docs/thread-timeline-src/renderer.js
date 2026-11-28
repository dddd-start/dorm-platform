// ── 渲染器：把一张图定义画成 SVG ──────────────────────────────────
// 布局：n 列等宽。每步占一行。自消息 = 列内盒；跨列消息 = 列内盒 + 肘形连线
//      （从盒底竖下 → 横向到目标列中心 → 竖上到目标列在该行的位置）。
// 生命线画在列中心，被盒子压在下面，视觉上仍连续。

const LANE_W = 206;      // 列宽
const LANE_GAP = 20;     // 列间距
const PAD_X = 18, PAD_TOP = 64, PAD_BOT = 22;
const BOX_PAD = 9, LINE_H = 16, FONT = 11.5, FONT_SM = 9.5;
const ROW_GAP = 16;

const KIND_COLOR = { vt: '--vt', pt: '--pt', ext: '--ext', caller: '--caller' };
const KIND_BG   = { vt: '--vt-bg', pt: '--pt-bg', ext: '--ext-bg', caller: '--caller-bg' };
const KIND_LINE = { vt: '--vt-line', pt: '--pt-line', ext: '--ext-line', caller: '--caller-line' };

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// SVG 里没有 <b>：直接去掉标签，文字本身已经说明了重点
function stripB(s) {
  return String(s).replace(/<\/?b>/g, '');
}

// 字符宽度估算：CJK 与全角算 1em，ASCII 算 0.55em
function measure(s, fontPx) {
  let w = 0;
  for (const ch of s) {
    w += /[　-鿿＀-￯]/.test(ch) ? fontPx : fontPx * 0.55;
  }
  return w;
}

// 按字符宽度折行：显式 \n 优先，其次空格，最后仅在单 token 超宽时硬切。
function wrap(text, maxPx, fontPx) {
  const isCJK = ch => /[　-鿿＀-￯]/.test(ch);
  const out = [];
  for (const seg of String(text).split('\n')) {
    let line = '';
    let i = 0;
    while (i < seg.length) {
      // 一个 token：单个 CJK 字符，或连续的 ASCII 非空白字符，或一段空白
      let token = '';
      if (isCJK(seg[i])) {
        token = seg[i]; i++;
      } else if (/\s/.test(seg[i])) {
        while (i < seg.length && /\s/.test(seg[i])) { token += seg[i]; i++; }
      } else {
        while (i < seg.length && !isCJK(seg[i]) && !/\s/.test(seg[i])) { token += seg[i]; i++; }
      }
      if (measure(line + token, fontPx) <= maxPx) {
        line += token;
        continue;
      }
      if (line.trim()) { out.push(line.trim()); line = ''; }
      let rest = token.trim() ? token : '';
      // token 自身超宽才断行：优先在 . ( _ : , → 之后断，实在没有才硬切
      while (measure(rest, fontPx) > maxPx) {
        let cut = 1;
        while (cut < rest.length && measure(rest.slice(0, cut + 1), fontPx) <= maxPx) cut++;
        let brk = -1;
        for (let j = cut; j > Math.floor(cut * 0.45); j--) {
          if (/[.(_:,→\-\/]/.test(rest[j - 1])) { brk = j; break; }
        }
        if (brk > 0) cut = brk;
        out.push(rest.slice(0, cut));
        rest = rest.slice(cut);
      }
      line = rest;
    }
    if (line.trim()) out.push(line.trim());
  }
  return out.length ? out : [''];
}

function renderDiagram(def) {
  const lanes = def.lanes.map(l => LANES[l]);
  const n = lanes.length;
  const innerW = LANE_W - BOX_PAD * 2;
  const colX = i => PAD_X + i * (LANE_W + LANE_GAP);
  const centerX = i => colX(i) + LANE_W / 2;

  // ── 预先算每行高度 ──
  // 跨列消息走本行的竖直中点，不需要额外行间距
  const rows = def.steps.map(st => {
    const lines = wrap(stripB(st.text), innerW, FONT);
    const h = lines.length * LINE_H + 14;
    return { st, lines, h };
  });

  // 顶部标签高度固定
  const headH = 46;
  let y = PAD_TOP;
  const placed = rows.map(r => {
    const top = y;
    y += r.h + ROW_GAP;
    return { ...r, top };
  });
  const bodyBottom = y - (rows.length ? ROW_GAP : 0);
  const totalH = bodyBottom + PAD_BOT;
  // 右侧留出「切换说明」标签的半个宽度，避免最后一列的标签被 viewBox 裁掉
  const maxHopPx = Math.max(0, ...def.steps.filter(s => s.hop).map(s => measure(s.hop, FONT_SM) + 10));
  const totalW = PAD_X * 2 + n * LANE_W + (n - 1) * LANE_GAP + Math.ceil(maxHopPx / 2) + 4;

  const out = [];
  out.push(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${totalW} ${totalH}" ` +
    `width="${totalW}" height="${totalH}" role="img" aria-label="${esc(def.alt || def.title)}" ` +
    `font-family="'IBM Plex Sans','PingFang SC','Microsoft YaHei',sans-serif">`);

  // ── 生命线（列中心竖线 + 顶部标签） ──
  for (let i = 0; i < n; i++) {
    const lane = lanes[i];
    const cx = centerX(i);
    const c = `var(${KIND_COLOR[lane.k]})`;
    // 生命线
    out.push(`<line x1="${cx}" y1="${PAD_TOP - 8}" x2="${cx}" y2="${bodyBottom + 8}" ` +
      `stroke="var(${KIND_LINE[lane.k]})" stroke-width="1.5" stroke-dasharray="3 4"/>`);
    // 顶部标签：主名 + 副名
    const labelW = Math.min(LANE_W, measure(lane.name, 11.5) + 18);
    out.push(`<rect x="${cx - labelW / 2}" y="${PAD_TOP - 44}" width="${labelW}" height="21" rx="4" ` +
      `fill="var(${KIND_BG[lane.k]})" stroke="${c}" stroke-width="1"/>`);
    out.push(`<text x="${cx}" y="${PAD_TOP - 30}" text-anchor="middle" font-size="11.5" ` +
      `font-weight="600" fill="${c}" font-family="'IBM Plex Mono',monospace">${esc(lane.name)}</text>`);
    if (lane.sub) {
      out.push(`<text x="${cx}" y="${PAD_TOP - 15}" text-anchor="middle" font-size="8.5" ` +
        `fill="var(--muted)">${esc(lane.sub)}</text>`);
    }
  }

  // ── 跨列消息（先画，压在盒子下面） ──
  // 标准时序图画法：从源盒侧边水平指向目标列，箭头落在本行的竖直中点。
  // 语义只需要「哪一列 → 哪一列」，不依赖目标行，因此不会画歪。
  for (const row of placed) {
    const { st, h, top } = row;
    if (st.to == null) continue;
    const toLane = st.to;
    if (toLane === st.lane || !lanes[toLane]) continue;

    const yMid = top + h / 2;
    const srcLeft = colX(st.lane), srcRight = colX(st.lane) + LANE_W;
    const goingRight = toLane > st.lane;
    const x0 = goingRight ? srcRight : srcLeft;
    const x1 = centerX(toLane);
    const c = `var(${KIND_COLOR[lanes[toLane].k]})`;

    out.push(`<line x1="${x0}" y1="${yMid}" x2="${x1}" y2="${yMid}" ` +
      `stroke="${c}" stroke-width="1.4"/>`);
    const dir = goingRight ? 1 : -1;
    out.push(`<polygon points="${x1},${yMid} ${x1 - dir * 7},${yMid - 4} ${x1 - dir * 7},${yMid + 4}" ` +
      `fill="${c}"/>`);

    if (st.hop) {
      // 标签优先居中在「起点盒边缘 → 目标列」区间；区间放不下就贴着起点盒上沿画，
      // 位置不同而已，不省略——交接方式本身就是图上最重要的信息。
      const gapStart = goingRight ? srcRight : centerX(toLane) + 8;
      const gapEnd = goingRight ? centerX(toLane) - 8 : srcLeft;
      const lw = measure(st.hop, FONT_SM) + 10;
      const fits = gapEnd - gapStart > lw;
      const midX = fits ? (gapStart + gapEnd) / 2 : (goingRight ? srcRight + lw / 2 + 4 : srcLeft - lw / 2 - 4);
      const labelY = fits ? yMid : top + 9;
      out.push(`<rect x="${midX - lw / 2}" y="${labelY - 15}" width="${lw}" height="13" rx="3" ` +
        `fill="var(--surface)" stroke="var(--border)" stroke-width=".8"/>`);
      out.push(`<text x="${midX}" y="${labelY - 5.5}" text-anchor="middle" font-size="${FONT_SM}" ` +
        `fill="var(--muted)" font-family="'IBM Plex Mono',monospace">${esc(st.hop)}</text>`);
    }
  }

  // ── 步骤盒 ──
  for (const row of placed) {
    const { st, lines, h, top } = row;
    const i = st.lane;
    const x = colX(i);
    const lane = lanes[i];
    const c = `var(${KIND_COLOR[lane.k]})`;

    // 盒子
    out.push(`<rect x="${x}" y="${top}" width="${LANE_W}" height="${h}" rx="5" ` +
      `fill="var(${KIND_BG[lane.k]})" stroke="var(${KIND_LINE[lane.k]})" stroke-width="1"/>`);

    // 步骤号（左上角小徽标）
    out.push(`<circle cx="${x + 11}" cy="${top + 11}" r="7.5" fill="var(--surface)" ` +
      `stroke="var(${KIND_LINE[lane.k]})" stroke-width="1"/>`);
    out.push(`<text x="${x + 11}" y="${top + 14.5}" text-anchor="middle" font-size="8.5" ` +
      `font-weight="600" fill="var(--muted)" font-family="'IBM Plex Mono',monospace">${st.n}</text>`);

    // 正文
    lines.forEach((ln, k) => {
      out.push(`<text x="${x + 24}" y="${top + 15 + k * LINE_H}" font-size="${FONT}" ` +
        `fill="var(--fg)">${esc(ln)}</text>`);
    });
  }

  out.push('</svg>');
  return out.join('\n');
}
