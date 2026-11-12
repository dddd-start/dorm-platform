#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 docs/diagrams/*.png —— Agent 流式链路（SSE / 续传 / LLM 交互）示意图。

为什么是"脚本 + PNG"而不是手画：

* 图与代码是同一份知识。链路改了（例如队列容量、超时、事件名），
  改这里一个数字就能重出图，不会出现"文档里的图画的是上一版设计"。
* 只用 Pillow（本机已装）与系统自带的 Noto Sans CJK，不需要浏览器 /
  Mermaid / Graphviz —— 本机这三样都没有。

用法：

    python3 scripts/gen-agent-diagrams.py

输出（全部 2 倍像素密度，缩放后仍清晰）：

    docs/diagrams/01-topology.png
    docs/diagrams/02-sequence-admission.png
    docs/diagrams/03-sequence-generation.png
    docs/diagrams/04-sequence-resume.png
    docs/diagrams/05-sse-channel-backpressure.png
    docs/diagrams/06-state-machines.png
    docs/diagrams/07-llm-interaction.png
"""

import math
import os

from PIL import Image, ImageDraw, ImageFont

# --------------------------------------------------------------------------- 基础

S = 2  # 2x 渲染：逻辑坐标 1400 → 实际 2800 像素

FONT_REG = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
FONT_BOLD = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
FONT_INDEX = 2  # Noto Sans CJK SC

BG = "#ffffff"
PANEL = "#f8fafc"
TEXT = "#0f172a"
MUTED = "#475569"
DIM = "#64748b"
BORDER = "#cbd5e1"
GRID = "#e2e8f0"

BLUE = "#1d4ed8"
BLUE_BG = "#eff6ff"
TEAL = "#0f766e"
TEAL_BG = "#f0fdfa"
VIOLET = "#6d28d9"
VIOLET_BG = "#f5f3ff"
AMBER = "#b45309"
AMBER_BG = "#fffbeb"
ROSE = "#be123c"
ROSE_BG = "#fff1f2"
GREEN = "#15803d"
GREEN_BG = "#f0fdf4"
SLATE_BG = "#f1f5f9"

_FONT_CACHE = {}


def hex_rgb(value):
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def font(size, bold=False):
    key = (round(size, 2), bold)
    if key not in _FONT_CACHE:
        _FONT_CACHE[key] = ImageFont.truetype(
            FONT_BOLD if bold else FONT_REG, int(round(size * S)), index=FONT_INDEX
        )
    return _FONT_CACHE[key]


_NO_LINE_START = "，。；：、！？）》」』%…!,.;:)]}"


def _reflow_punct(lines):
    """排版修正：避免中文标点出现在行首（把标点拽回上一行行尾）。"""
    for i in range(1, len(lines)):
        while lines[i] and lines[i][0] in _NO_LINE_START and lines[i - 1]:
            lines[i - 1] += lines[i][0]
            lines[i] = lines[i][1:]
    return [line for line in lines if line != ""] or [""]


def _tokens(text):
    """把文本切成"中文单字 + 西文单词"的 token，便于换行时不切断英文。"""
    out, buf = [], ""
    for ch in text:
        if ord(ch) < 128 and not ch.isspace():
            buf += ch
        else:
            if buf:
                out.append(buf)
                buf = ""
            out.append(ch)
    if buf:
        out.append(buf)
    return out


class Canvas:
    def __init__(self, w, h, bg=BG):
        self.w, self.h = w, h
        self.img = Image.new("RGB", (int(w * S), int(h * S)), bg)
        self.d = ImageDraw.Draw(self.img, "RGBA")

    # ---- 文本 ----
    def text(self, x, y, s, size=13, color=TEXT, bold=False, anchor="lm"):
        self.d.text((x * S, y * S), s, font=font(size, bold), fill=color, anchor=anchor)

    def text_w(self, s, size=13, bold=False):
        return self.d.textlength(s, font=font(size, bold)) / S

    def wrap(self, text, maxw, size=12.5, bold=False):
        f = font(size, bold)
        limit = maxw * S
        lines, cur = [], ""
        for tok in _tokens(text):
            if tok == "\n":
                lines.append(cur)
                cur = ""
                continue
            trial = cur + tok
            if not cur or self.d.textlength(trial, font=f) <= limit:
                cur = trial
            else:
                lines.append(cur.rstrip())
                cur = tok.lstrip()
        if cur.strip():
            lines.append(cur.rstrip())
        return _reflow_punct(lines) if lines else [""]

    def text_block(self, x, y, lines, size=12.5, color=TEXT, bold=False, lh=None):
        lh = lh or size * 1.55
        for i, line in enumerate(lines):
            self.text(x, y + i * lh, line, size=size, color=color, bold=bold)
        return y + len(lines) * lh

    def vtext(self, cx, cy, s, size=12, color=MUTED, bold=True):
        """竖排（自下而上）文字，用于泳道左侧的分阶段标签。"""
        f = font(size, bold)
        tw = int(self.d.textlength(s, font=f)) + 8
        th = int(size * 1.5 * S)
        tmp = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
        ImageDraw.Draw(tmp).text((4, th // 2), s, font=f, fill=color, anchor="lm")
        tmp = tmp.rotate(90, expand=True)
        self.img.paste(tmp, (int(cx * S - tmp.width / 2), int(cy * S - tmp.height / 2)), tmp)

    # ---- 形状 ----
    def rect(self, x, y, w, h, fill=None, stroke=None, r=10, width=1.6, dash=False):
        xy = [x * S, y * S, (x + w) * S, (y + h) * S]
        if dash:
            if fill:
                self.d.rounded_rectangle(xy, radius=r * S, fill=fill)
            self._dash_rect(x, y, w, h, stroke or BORDER, width, r)
        else:
            self.d.rounded_rectangle(
                xy, radius=r * S, fill=fill, outline=stroke, width=int(width * S)
            )

    def _dash_rect(self, x, y, w, h, color, width, r):
        self.dline(x + r, y, x + w - r, y, color, width)
        self.dline(x + r, y + h, x + w - r, y + h, color, width)
        self.dline(x, y + r, x, y + h - r, color, width)
        self.dline(x + w, y + r, x + w, y + h - r, color, width)

    def line(self, x1, y1, x2, y2, color=BORDER, width=1.6):
        self.d.line([x1 * S, y1 * S, x2 * S, y2 * S], fill=color, width=int(width * S))

    def dline(self, x1, y1, x2, y2, color=BORDER, width=1.6, dash=6, gap=5):
        length = math.hypot(x2 - x1, y2 - y1)
        if length == 0:
            return
        ux, uy = (x2 - x1) / length, (y2 - y1) / length
        pos = 0.0
        while pos < length:
            end = min(pos + dash, length)
            self.d.line(
                [
                    (x1 + ux * pos) * S,
                    (y1 + uy * pos) * S,
                    (x1 + ux * end) * S,
                    (y1 + uy * end) * S,
                ],
                fill=color,
                width=int(width * S),
            )
            pos = end + gap

    def head(self, x, y, angle, color, size=9):
        a1, a2 = angle + math.pi * 0.86, angle - math.pi * 0.86
        pts = [
            (x * S, y * S),
            ((x + size * math.cos(a1)) * S, (y + size * math.sin(a1)) * S),
            ((x + size * math.cos(a2)) * S, (y + size * math.sin(a2)) * S),
        ]
        self.d.polygon(pts, fill=color)

    def arrow(self, x1, y1, x2, y2, color=BLUE, width=1.8, size=9, dash=False):
        angle = math.atan2(y2 - y1, x2 - x1)
        bx, by = x2 - size * 0.8 * math.cos(angle), y2 - size * 0.8 * math.sin(angle)
        if dash:
            self.dline(x1, y1, bx, by, color, width)
        else:
            self.line(x1, y1, bx, by, color, width)
        self.head(x2, y2, angle, color, size)

    def label(self, cx, cy, s, size=12, color=TEXT, bg=BG, bold=False, pad_x=6, pad_y=3, r=5):
        w = self.text_w(s, size, bold)
        h = size * 1.25
        self.d.rounded_rectangle(
            [(cx - w / 2 - pad_x) * S, (cy - h / 2 - pad_y) * S,
             (cx + w / 2 + pad_x) * S, (cy + h / 2 + pad_y) * S],
            radius=r * S, fill=bg,
        )
        self.text(cx, cy, s, size=size, color=color, bold=bold, anchor="mm")

    def pill(self, x, y, s, size=12, fg=TEXT, bg=SLATE_BG, stroke=None, bold=False,
             pad_x=10, h=24, mono=False):
        w = self.text_w(s, size, bold) + pad_x * 2
        self.rect(x, y, w, h, fill=bg, stroke=stroke, r=h / 2, width=1.2)
        self.text(x + w / 2, y + h / 2 + 0.5, s, size=size, color=fg, bold=bold, anchor="mm")
        return w

    def tag(self, x, y, s, fg, bg, size=11, bold=True, h=20):
        w = self.text_w(s, size, bold) + 14
        self.rect(x, y, w, h, fill=bg, stroke=None, r=5)
        self.text(x + w / 2, y + h / 2 + 0.5, s, size=size, color=fg, bold=bold, anchor="mm")
        return w

    def card(self, x, y, w, h, title, lines, accent=BLUE, fill="#ffffff",
             title_size=14, body_size=11.8, title_bold=True, lh=None):
        self.rect(x, y, w, h, fill=fill, stroke=BORDER, r=12, width=1.5)
        self.rect(x, y, 5, h, fill=accent, stroke=None, r=2)
        self.text(x + 18, y + 22, title, size=title_size, color=TEXT, bold=title_bold)
        lh = lh or body_size * 1.55
        yy = y + 44
        for line in lines:
            self.text(x + 18, yy, line, size=body_size, color=MUTED)
            yy += lh
        return yy

    def save(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.img.save(path, "PNG", optimize=True)
        print("  -> %s  %dx%d" % (path, self.img.width, self.img.height))


def header(c, title, subtitle):
    c.text(40, 34, title, size=21, color=TEXT, bold=True, anchor="lt")
    c.text(40, 66, subtitle, size=12.5, color=DIM, anchor="lt")
    c.line(40, 90, c.w - 40, 90, GRID, 1.2)


# --------------------------------------------------------------------------- 时序图

class Seq:
    """极简时序图：阶段带 + 参与者 + 消息 / 自调用 / 备注。"""

    def __init__(self, width, title, subtitle, actors, left=86, right=44, header_top=112):
        self.width = width
        self.title = title
        self.subtitle = subtitle
        self.actors = actors  # [(name, role)]
        self.rows = []
        self.left, self.right, self.header_top = left, right, header_top
        n = len(actors)
        self.colw = (width - left - right) / n
        self.xs = [left + self.colw * (i + 0.5) for i in range(n)]
        self._measure = ImageDraw.Draw(Image.new("RGB", (10, 10)))

    # -- 度量 --
    def _mw(self, s, size, bold=False):
        return self._measure.textlength(s, font=font(size, bold)) / S

    def band(self, text, color=BLUE, note=None):
        self.rows.append({"t": "band", "text": text, "color": color, "note": note})

    def msg(self, a, b, text, style="call", color=None, detail=None):
        self.rows.append({"t": "msg", "a": a, "b": b, "text": text,
                          "style": style, "color": color, "detail": detail})

    def own(self, a, text, detail=None):
        self.rows.append({"t": "self", "a": a, "text": text, "detail": detail})

    def note(self, text, a=0, b=None):
        self.rows.append({"t": "note", "a": a, "b": len(self.actors) - 1 if b is None else b,
                          "text": text})

    def gap(self, h=14):
        self.rows.append({"t": "gap", "h": h})

    # -- 布局 --
    def _layout(self):
        y = self.header_top + 82
        idx = 0
        bands = []
        while idx < len(self.rows):
            row = self.rows[idx]
            if row["t"] == "band":
                bands.append({"row": idx, "y": y, "color": row["color"], "text": row["text"]})
                y += 48
                idx += 1
                continue
            if row["t"] == "gap":
                row["y"], row["h"] = y, row["h"]
                y += row["h"]
            elif row["t"] == "msg":
                x1, x2 = self.xs[row["a"]], self.xs[row["b"]]
                maxw = max(abs(x2 - x1) - 26, 210)
                lines = self._wrap(row["text"], maxw, 12.2)
                h = 12 + len(lines) * 16.5 + 26
                row["y"], row["h"] = y, h
                row["lines"] = lines
                y += h
            elif row["t"] == "self":
                x1 = self.xs[row["a"]]
                maxw = self.colw - 96 if self.colw > 200 else 190
                lines = self._wrap(row["text"], max(maxw, 150), 12.2)
                h = 12 + len(lines) * 16.5 + 24
                row["y"], row["h"] = y, h
                row["lines"] = lines
                y += h
            elif row["t"] == "note":
                span = abs(self.xs[row["b"]] - self.xs[row["a"]]) + self.colw * 0.7
                lines = self._wrap(row["text"], max(span - 40, 220), 12.2)
                h = 14 + len(lines) * 16.5 + 16
                row["y"], row["h"] = y, h
                row["lines"] = lines
                y += h
            idx += 1
        for i, band in enumerate(bands):
            band["end"] = bands[i + 1]["y"] if i + 1 < len(bands) else y + 18
        return y + 26, bands

    def _wrap(self, text, maxw, size):
        f = font(size)
        limit = maxw * S
        lines, cur = [], ""
        for tok in _tokens(text):
            trial = cur + tok
            if not cur or self._measure.textlength(trial, font=f) <= limit:
                cur = trial
            else:
                lines.append(cur.rstrip())
                cur = tok.lstrip()
        if cur.strip():
            lines.append(cur.rstrip())
        return _reflow_punct(lines) if lines else [""]

    # -- 渲染 --
    def render(self, path):
        height, bands = self._layout()
        c = Canvas(self.width, height)
        header(c, self.title, self.subtitle)

        # 阶段带：淡淡的底色 + 左侧竖排阶段名（居中于整条带）
        for band in bands:
            y0, y1 = band["y"] - 10, band["end"]
            r, g, b = hex_rgb(band["color"])
            c.rect(24, y0, self.width - 48, y1 - y0, fill=(r, g, b, 14), r=10)
            c.rect(24, y0, 4, y1 - y0, fill=band["color"], r=2)
            c.vtext(46, (y0 + y1) / 2, band["text"], size=11.5, color=band["color"])

        # 参与者
        top = self.header_top
        for i, (name, role) in enumerate(self.actors):
            x = self.xs[i] - self.colw / 2 + 7
            w = self.colw - 14
            c.rect(x, top, w, 62, fill="#ffffff", stroke=BORDER, r=11, width=1.6)
            c.text(x + w / 2, top + 23, name, size=13.2, color=TEXT, bold=True, anchor="mm")
            c.text(x + w / 2, top + 45, role, size=10.4, color=DIM, anchor="mm")

        # 生命线
        for x in self.xs:
            c.dline(x, top + 62, x, height - 26, "#cbd5e1", 1.3, dash=5, gap=5)

        # 行
        for row in self.rows:
            if row["t"] == "band" or row["t"] == "gap":
                continue
            if row["t"] == "msg":
                self._draw_msg(c, row)
            elif row["t"] == "self":
                self._draw_self(c, row)
            elif row["t"] == "note":
                self._draw_note(c, row)

        c.save(path)
        return c

    def _draw_msg(self, c, row):
        x1, x2 = self.xs[row["a"]], self.xs[row["b"]]
        y = row["y"] + row["h"] - 16
        style = row["style"]
        color = row["color"] or {"call": BLUE, "return": "#7c8798",
                                 "async": TEAL, "error": ROSE, "internal": VIOLET}.get(style, BLUE)
        dash = style == "return"
        direction = 1 if x2 > x1 else -1
        c.arrow(x1, y, x2, y, color, width=1.7, size=8.5, dash=dash)
        # 标签整体放在线的上方：多行标签绝不盖住箭头，否则图会「只有字没有线」
        tw = max([c.text_w(l, 12.2) for l in row["lines"]])
        lh = 16.5
        th = len(row["lines"]) * lh
        box_h = th + 6
        box_cy = y - 9 - box_h / 2
        cx = (x1 + x2) / 2
        c.rect(cx - tw / 2 - 7, box_cy - box_h / 2, tw + 14, box_h, fill=BG, r=5)
        for i, line in enumerate(row["lines"]):
            c.text(cx, box_cy - th / 2 + lh / 2 + i * lh, line,
                   size=12.2, color=color if style in ("async", "error") else TEXT,
                   anchor="mm")

    def _draw_self(self, c, row):
        x = self.xs[row["a"]]
        y = row["y"] + 12
        c.line(x, y, x + 46, y, "#94a3b8", 1.5)
        c.line(x + 46, y, x + 46, y + 24, "#94a3b8", 1.5)
        c.arrow(x + 46, y + 24, x, y + 24, "#94a3b8", 1.5, 8, dash=False)
        for i, line in enumerate(row["lines"]):
            c.text(x + 58, y + 4 + i * 16.5, line, size=12.2, color=MUTED)

    def _draw_note(self, c, row):
        x1 = self.xs[row["a"]] - self.colw * 0.32
        x2 = self.xs[row["b"]] + self.colw * 0.32
        y = row["y"] + 2
        h = row["h"] - 6
        c.rect(x1, y, x2 - x1, h, fill=AMBER_BG, stroke="#fcd34d", r=8, width=1.2)
        c.text(x1 + 12, y + 12, "注", size=11, color=AMBER, bold=True)
        for i, line in enumerate(row["lines"]):
            c.text(x1 + 34, y + 12 + i * 16.5, line, size=12, color="#7c2d12")


# --------------------------------------------------------------------------- 图 1：全景

def diagram_topology(out):
    c = Canvas(1600, 1060)
    header(c, "图 1 · 全景：三个服务、两条数据边界、一个共享 Redis",
           "浏览器只与 ui-service 说话；agent-service 不连业务库；模型在进程之外。数字是后面几张时序图的编号。")

    # 浏览器
    c.card(120, 120, 300, 110, "浏览器", [
        "Vue 3 · useAgentStream",
        "fetch + ReadableStream 解析 SSE",
        "断线后带 lastEventId 自动续传",
    ], accent=BLUE)

    # ui-service（网关条）
    c.card(120, 300, 1060, 100, "ui-service  :8080  静态托管 + 反向代理", [
        "/api/agent/** → :8082（SSE 逐块 pipe、不缓冲）    /api/** → :8081    /internal/** → 404",
        "带 hash 的 assets 长缓存；其余走 SPA history 回退。生产等价 Nginx：proxy_buffering off / 180s",
    ], accent=TEAL)
    c.text(120 + 12, 300 - 12, "①/②/③ 的唯一入口：它不做业务，只转发", size=10.6, color=DIM)

    # agent-service
    c.rect(200, 470, 560, 380, fill=PANEL, stroke=BORDER, r=14, width=1.8)
    c.text(222, 500, "agent-service  :8082", size=15.5, color=TEXT, bold=True)
    c.text(420, 500, "无状态 · 可多副本 · 无需粘性会话", size=10.8, color=DIM)
    inner = [
        ("AgentController · JwtAuthInterceptor", "验签 + 会话白名单 / epoch + 角色"),
        ("AgentTurnService", "准入 → 起流 → 生成 → 收口 → 续传"),
        ("SseChannel × N", "每连接一个 512 队列 + 一条虚拟线程写者"),
        ("AgentRegistry · AiServices", "5 类专家 × 2 角色 + 提示词 + 记忆"),
        ("GuardedToolProvider · RagService", "工具守卫（去重/熔断/审计）+ 知识库检索"),
    ]
    yy = 518
    for t, sub in inner:
        c.rect(222, yy, 516, 56, fill="#ffffff", stroke="#dbe3ef", r=9, width=1.2)
        c.text(238, yy + 20, t, size=12.2, color=TEXT, bold=True)
        c.text(238, yy + 39, sub, size=10.6, color=DIM)
        yy += 66

    # dorm-service
    c.card(820, 560, 360, 290, "dorm-service  :8081", [
        "业务的唯一写入方",
        "规则 / 状态机 / 数据权限 / 审计",
        "对外 /api/**，对内 /internal/agent/**",
        "JWT 重新解析 + 会话校验 + 内部令牌",
        "Outbox、分布式锁、单号都在这里",
    ], accent=VIOLET)

    # provider
    c.card(1220, 450, 340, 150, "LLM Provider（外部）", [
        "OpenAI 兼容 /chat/completions (stream)",
        "主模型 → 快模型 → 降级链",
        "reasoning_content 一并回传",
    ], accent=AMBER)

    # Redis
    c.card(200, 900, 300, 110, "Redis（共享）", [
        "Stream 历史 + Pub/Sub 实时",
        "订阅者 / 并发 / 取消 / 流状态",
        "令牌桶 · 会话白名单 · epoch",
    ], accent=ROSE, body_size=10.6)

    # agent_db
    c.card(520, 900, 300, 110, "MySQL · agent_db", [
        "会话 / 消息 / 归档 / 工具审计",
        "用量 / 记忆 / RAG 文档·切片",
    ], accent=TEAL, body_size=10.6)

    # dorm_db
    c.card(840, 900, 340, 110, "MySQL · dorm_db", [
        "区域 / 用户 / 公寓 / 房间 / 床位",
        "申请 / 订单 / 审计 / Outbox",
    ], accent=VIOLET, body_size=10.6)

    # ---- 连线 ----
    # ① 浏览器 → ui
    c.arrow(420, 175, 512, 175, BLUE, 2.1)
    c.line(512, 175, 512, 298, BLUE, 2.1)
    c.arrow(512, 298, 512, 306, BLUE, 2.1)
    c.label(465, 156, "① HTTPS · SSE", 11.4, BLUE, BG)

    # ② ui → agent
    c.arrow(480, 400, 480, 466, TEAL, 2.0)
    c.label(480, 432, "② /api/agent", 11.2, TEAL, BG)

    # ⑤ agent → provider（先画，避免被 ③ 的标签压住）
    c.arrow(760, 520, 1216, 520, AMBER, 2.0)
    c.label(890, 500, "⑤ 模型流式调用（OpenAI 兼容）", 11.2, AMBER, BG)

    # ③ ui → dorm（跨过 ⑤，颜色可区分）
    c.line(1100, 400, 1100, 556, TEAL, 2.0)
    c.arrow(1100, 556, 1100, 562, TEAL, 2.0)
    c.label(1100, 452, "③ /api/**（业务）", 11.2, TEAL, BG)

    # ④ agent → dorm（工具）
    c.arrow(760, 700, 816, 700, VIOLET, 2.0)
    c.label(788, 680, "④", 12, VIOLET, BG, bold=True)

    # ⑥ agent ↔ redis
    c.arrow(300, 850, 300, 896, ROSE, 2.0)
    c.label(348, 872, "⑥ 事件先写 Redis，再投递", 10.8, ROSE, BG)

    # ⑦ agent ↔ agent_db
    c.arrow(670, 850, 670, 896, TEAL, 2.0)
    c.label(670, 872, "⑦", 12, TEAL, BG, bold=True)

    # ⑧ dorm ↔ dorm_db
    c.arrow(1000, 850, 1000, 896, VIOLET, 2.0)
    c.label(1000, 872, "⑧", 12, VIOLET, BG, bold=True)

    # 图例
    c.text(120, 1024,
           "读图要点：① 浏览器只认 ui-service；②③ UI 只做静态与反代；④ 业务写只发生在 dorm-service（Agent 不越权改库）；",
           size=11.2, color=MUTED)
    c.text(120, 1044,
           "⑤ 模型在进程之外，失败只能降级不能重试式放大；⑥ Redis 是唯一的跨实例共享状态；⑦⑧ 两个服务各自独占一个库。",
           size=11.2, color=MUTED)
    c.save(out)


# --------------------------------------------------------------------------- 图 2：接入与起流

def diagram_admission(out):
    actors = [
        ("浏览器", "useAgentStream"),
        ("ui-service", "静态 + 反代"),
        ("鉴权拦截器", "JWT + 会话白名单"),
        ("AgentTurnService", "请求线程（Tomcat）"),
        ("Redis", "计数 / 限流 / 状态"),
        ("agent_db", "MySQL"),
        ("生成线程池", "虚拟线程"),
    ]
    s = Seq(1400, "图 2 · 一轮对话（上）：接入、准入与起流",
            "顺序经过刻意安排：护栏 → 幂等 → 限流 → 预算 → 并发 → 落库 → 建通道 → 立刻返回。"
            "任何一步拒绝都以 SSE ERROR 帧表达，而不是 HTTP 500。", actors)

    s.band("① 传输与鉴权", BLUE)
    s.msg(0, 1, "POST /api/agent/chat/stream  { conversationId, turnId, message }（Bearer 令牌）")
    s.msg(1, 2, "透传请求；SSE 逐块 pipe，proxy_buffering off，读超时 180s")
    s.msg(2, 4, "验签 JWT（或 ?token=）+ 会话白名单 + auth epoch")
    s.msg(4, 2, "通过：身份写入 UserContext，令牌写入 AgentTokenContext", style="return")
    s.msg(2, 3, "chat(conversationId, turnId, message)")

    s.band("② 准入：幂等必须早于限流", VIOLET)
    s.own(3, "输入护栏：注入检测 + 违规词 + PII 脱敏（送模型的是掩码文本）")
    s.msg(3, 5, "幂等查询：turnId + role=ASSISTANT（带 user_id 归属条件）")
    s.msg(5, 3, "命中 → 重放既有回答；纯重放不调用模型、不扣配额", style="return")
    s.msg(3, 4, "令牌桶 20 次/分（Lua 原子）→ 预算 → 用户并发 ≤3 → 全局 ≤1000")
    s.msg(3, 5, "beginTurn 单事务：用户消息 + 会话计数/标题 + 助手占位（STREAMING）")

    s.band("③ 起流：请求线程只负责返回", TEAL)
    s.own(3, "new SseEmitter(0) + SseChannel(队列 512) + 登记订阅者 Set（TTL 180s）")
    s.msg(3, 4, "模型闸门 tryAcquire（16 个许可，最多等 8s）")
    s.msg(3, 4, "setState(GENERATING)（键 30 分钟过期）")
    s.msg(3, 6, "submit(generate)：生成跑在独立虚拟线程，不占 Tomcat 线程", style="async")
    s.msg(3, 1, "return SseEmitter → HTTP 200", style="return")
    s.msg(1, 0, "响应头：text/event-stream；连接从此只由 SSE 帧驱动", style="return")

    s.note("此刻请求线程已释放。后续所有事件都由生成线程 / Redis 回调产生，"
           "浏览器收到第一帧 START 时，模型调用可能已经开始 —— 见图 3。", a=0, b=3)
    s.render(out)


# --------------------------------------------------------------------------- 图 3：生成与收口

def diagram_generation(out):
    actors = [
        ("浏览器", "SSE 消费端"),
        ("生成线程", "AgentTurnService"),
        ("AiServices", "LangChain4j"),
        ("LLM Provider", "OpenAI 兼容"),
        ("工具守卫", "GuardedToolProvider"),
        ("dorm-service", "/internal/**"),
        ("Redis", "Stream + Pub/Sub"),
        ("agent_db", "MySQL"),
    ]
    s = Seq(1480, "图 3 · 一轮对话（下）：模型流、工具循环与收口",
            "所有事件都走同一条路：分配 seq → 写 Redis Stream（可续传）→ PUBLISH（实时）→ 本连接队列。"
            "seq 只有一个来源，避免跳号导致续传出现空洞。", actors, left=80)

    s.band("① 开流与组装", BLUE)
    s.msg(1, 6, "publish START（seq=1）：XADD 历史 + PUBLISH 实时")
    s.msg(6, 0, "START { conversationId, startSeq, heartbeatSeconds }", style="return")
    s.own(1, "意图路由：关键词打分 ≥6 直接用；说不清才问快模型；再不行兜底 ROOM")
    s.msg(1, 2, "chat(conversationId, message)：按 conversationId 取记忆窗口（20 条）")
    s.own(2, "组装请求：系统提示词（身份/日期/职责）+ 历史 + 工具 Schema + 候选模型链")

    s.band("② 流式生成与工具循环", TEAL)
    s.msg(2, 3, "流式调用上游（temperature 0.2 / max_tokens 2048 / 超时 120s）")
    s.msg(3, 2, "分片：reasoning_content / 正文 token / tool_call", style="return")
    s.msg(2, 1, "onPartialThinking → REASONING")
    s.msg(1, 0, "DELTA：只推已离开护栏窗口的部分（窗口 60 字，回退 16 字）", style="async")
    s.msg(2, 4, "模型要求调用 listRegions(参数)：先过守卫再执行")
    s.msg(4, 1, "TOOL { phase:start, tool, label, arguments }")
    s.msg(4, 5, "HTTP GET /internal/agent/** + 用户 JWT + X-Internal-Token + traceId")
    s.msg(5, 4, "Result（床位占用、状态机、数据权限只在业务侧判定）", style="return")
    s.msg(4, 2, "工具结果回灌本轮记忆视图 → 模型继续推理（≤6 次 / 3 轮往返）")
    s.msg(4, 1, "TOOL { phase:end, failed }")
    s.msg(3, 2, "onCompleteResponse（含 tokenUsage；可能已降级到备用模型）", style="return")

    s.band("③ 收口：先落库，再发终态", AMBER)
    s.own(1, "guard.finish() 吐出尾窗；BLOCKED 或空正文按失败收口（空回答不算成功）")
    s.msg(1, 7, "finishTurn 单事务：正文 / 状态 / tokens / 首字延迟 / 用量按天累加")
    s.msg(1, 6, "setState(DONE) + publish DONE（seq 继续递增）")
    s.msg(6, 0, "DONE { model, agent, tokensIn/Out, latencyMs, firstTokenMs, toolCalls, ragHits }", style="return")
    s.own(1, "释放并发计数与模型许可 → closeChannel：每条连接先把队列写完再关闭")

    s.note("异步收尾还有两件事：① 审计 AGENT_TURN（异步线程，失败不影响用户拿到 DONE）；"
           "② 会话变长后异步生成摘要（不占流式线程）。", a=0, b=2)
    s.render(out)


# --------------------------------------------------------------------------- 图 4：续传

def diagram_resume(out):
    actors = [
        ("浏览器", "lastSeq=17"),
        ("实例 B", "续传连接"),
        ("Redis", "Stream + Pub/Sub"),
        ("实例 A", "仍在生成"),
        ("agent_db", "归属校验"),
    ]
    s = Seq(1360, "图 5 · 断点续传：先订阅、后补历史，再切实时",
            "顺序写反就会丢事件：在「读完历史」和「订阅生效」之间产生的 token，"
            "既不在历史里、也收不到。所以必须先订阅（只缓存），再补历史。", actors)

    s.band("① 断线与重连", ROSE)
    s.own(0, "连接中断（弱网 / 切后台 / 刷新）。已收到最大 seq=17，回答还没结束")
    s.own(0, "指数退避 1s → 2s → 4s → 8s → 10s（最多 8 次），终态后不再重连")
    s.msg(0, 1, "GET /api/agent/chat/stream/{messageId}?lastEventId=17（token 可用查询参数）")
    s.msg(1, 4, "校验消息归属：只有会话主人（或管理员回放）能续传")
    s.msg(1, 2, "SADD dorm:stream:subs:{id}（TTL 180s，心跳续期）")

    s.band("② 先订阅、再补历史（关键顺序）", TEAL)
    s.msg(1, 2, "SUBSCRIBE dorm:stream:live:{id}")
    s.msg(2, 1, "这段时间的实时事件：先放进 pendingBuffer，还不发给前端", style="return")
    s.msg(1, 2, "XRANGE dorm:stream:{id}：取 seq > 17，最多 1000 条")
    s.msg(2, 1, "历史事件 18 … 120", style="return")
    s.msg(1, 0, "按 seq 补发 18 … 120（同一 seq 只写一次）", style="async")
    s.own(1, "live=true：冲洗 pendingBuffer，凡 seq ≤ 已发最大值的直接丢弃")
    s.own(1, "此刻才把本连接注册进 channels：参与实例级心跳与轮次结束时的统一收尾")

    s.band("③ 实时接续与收尾", BLUE)
    s.msg(3, 2, "XADD + PUBLISH（seq=121, 122 …）")
    s.msg(2, 1, "实时事件（跨实例，走 Pub/Sub）")
    s.msg(1, 0, "推送 121 …（DELTA / TOOL / DONE）", style="async")
    s.note("如果补完历史时流状态已是终态：历史末帧不是终态就补一条 DONE 再关闭；"
           "已经是终态则直接关闭，绝不让前端收到两次结束信号。", a=0, b=2)
    s.render(out)


# --------------------------------------------------------------------------- 图 5：出站管道

def diagram_channel(out):
    c = Canvas(1500, 800)
    header(c, "图 6 · 单连接出站管道：生产者永不阻塞，慢客户端只影响自己",
           "SSE 的写 socket 是阻塞的。把「生产」与「写出」拆开，是一个连接一个队列一个写者。")

    # 生产者
    c.text(50, 130, "生产者（三类，都可能很慢）", size=13, color=TEXT, bold=True)
    producers = [
        ("生成线程 publish()", "模型每吐一个 token / 工具事件", TEAL),
        ("Redis Pub/Sub 回调", "跨实例续传的实时事件", ROSE),
        ("实例级心跳 tick", "15s 一次，遍历全部连接入队", AMBER),
    ]
    yy = 156
    for t, s, col in producers:
        c.rect(50, yy, 250, 84, fill="#ffffff", stroke=BORDER, r=10, width=1.4)
        c.rect(50, yy, 4, 84, fill=col, r=2)
        c.text(66, yy + 26, t, size=12.8, color=TEXT, bold=True)
        c.text(66, yy + 50, s, size=10.6, color=DIM)
        c.arrow(300, yy + 42, 372, yy + 42, col, 1.6)
        yy += 108

    # offer
    c.rect(374, 232, 250, 150, fill=BLUE_BG, stroke=BLUE, r=12, width=1.6)
    c.text(388, 258, "offer() 非阻塞", size=13.4, color=BLUE, bold=True)
    c.text(388, 282, "· 纯内存入队，永不等待", size=10.8, color=MUTED)
    c.text(388, 302, "· 按 seq 去重（同事件两条", size=10.8, color=MUTED)
    c.text(388, 320, "  投递路径，只写一次）", size=10.8, color=MUTED)
    c.text(388, 342, "· 队列满 → 返回 false", size=10.8, color=ROSE, bold=True)
    c.text(388, 362, "  （调用方据此放弃该连接）", size=10.8, color=MUTED)

    c.arrow(624, 307, 676, 307, BLUE, 2.0)

    # queue
    c.rect(678, 200, 260, 214, fill="#ffffff", stroke=BORDER, r=12, width=1.6)
    c.text(692, 226, "有界队列", size=13.4, color=TEXT, bold=True)
    c.text(692, 248, "ArrayBlockingQueue(512)", size=10.8, color=DIM)
    for i in range(8):
        c.rect(694 + (i % 2) * 112, 266 + (i // 2) * 30, 104, 22,
               fill=BLUE_BG if i < 6 else "#ffffff", stroke="#bfdbfe", r=4, width=1)
    c.text(692, 392, "最坏内存 = 连接数 × 512 × 单帧大小", size=10.2, color=DIM)

    c.arrow(938, 307, 990, 307, TEAL, 2.0)

    # writer
    c.rect(992, 232, 196, 150, fill=TEAL_BG, stroke=TEAL, r=12, width=1.6)
    c.text(1006, 258, "虚拟线程 writer", size=13.2, color=TEAL, bold=True)
    c.text(1006, 282, "每连接一条，独占消费", size=10.8, color=MUTED)
    c.text(1006, 302, "阻塞只卡住自己", size=10.8, color=MUTED)
    c.text(1006, 322, "（平台线程会 pin 住 1MB 栈）", size=10.2, color=DIM)
    c.text(1006, 348, "terminator 哨兵", size=10.8, color=TEAL, bold=True)
    c.text(1006, 366, "保证 DONE 写完再关", size=10.2, color=DIM)

    c.arrow(1188, 307, 1236, 307, TEAL, 2.0)

    # socket
    c.rect(1238, 232, 212, 150, fill="#ffffff", stroke=BORDER, r=12, width=1.6)
    c.text(1252, 258, "SseEmitter → socket", size=12.6, color=TEXT, bold=True)
    c.text(1252, 286, "id:<seq> + event:<TYPE>", size=10.6, color=MUTED)
    c.text(1252, 306, "+ data:<json> + 空行", size=10.6, color=MUTED)
    c.text(1252, 332, "心跳 = 注释帧 :ping", size=10.6, color=DIM)
    c.text(1252, 352, "不占 seq、不触发 onmessage", size=10.2, color=DIM)

    # 下方的溢出与收尾
    c.rect(374, 470, 530, 132, fill=ROSE_BG, stroke="#fda4af", r=12, width=1.4)
    c.text(392, 494, "队列满 = 这个客户端跟不上了", size=12.8, color=ROSE, bold=True)
    for i, line in enumerate([
        "① 丢弃最旧一帧腾位置（宁可少一帧中间内容）",
        "② 入队 INTERRUPTED{ resumable:true }，告知「请带 lastEventId 重连」",
        "③ close()：拒绝新帧，写者把队列写完再关闭（不静默截断）",
        "④ 生成本身继续，完整回答仍落库 —— 重连即可补齐",
    ]):
        c.text(392, 518 + i * 20, line, size=10.8, color="#7f1d1d")

    c.rect(930, 470, 520, 132, fill=GREEN_BG, stroke="#86efac", r=12, width=1.4)
    c.text(948, 494, "为什么「丢弃连接」是安全的", size=12.8, color=GREEN, bold=True)
    for i, line in enumerate([
        "事件已在 Redis Stream 里（TTL 30 分钟，最多 1000 条）",
        "续传只依赖 seq，不依赖这条 socket 的存活",
        "旧实现在这里直接 close()，客户端只看到「流莫名结束」——",
        "压测中表现为「HTTP 200 但没有终态事件」，现在不会了",
    ]):
        c.text(948, 518 + i * 20, line, size=10.8, color="#14532d")

    # 旧实现对比
    c.rect(50, 470, 300, 132, fill=AMBER_BG, stroke="#fcd34d", r=12, width=1.4)
    c.text(66, 494, "旧实现：直接写 socket", size=12.8, color=AMBER, bold=True)
    for i, line in enumerate([
        "· 慢客户端占住一个生成线程",
        "· 64 个慢客户端 → 实例停止生成",
        "· Pub/Sub 单分发线程被占",
        "· 心跳 4 线程被占 → 全断",
    ]):
        c.text(66, 518 + i * 20, line, size=10.8, color="#78350f")

    c.text(50, 640, "写者三种退出方式：① 读到 terminator（正常收尾，先写完）"
                    "② 队列空且已请求收尾 ③ 写 socket 失败（客户端已断）",
           size=11.4, color=MUTED)
    c.text(50, 664, "实例级看门狗：连接空闲 > 90s（心跳 15s 必然写过）判定假死 → 关闭并移出注册表；"
                    "断线且宽限期内始终没有订阅者 → 取消生成，避免白烧额度。",
           size=11.4, color=MUTED)
    c.save(out)


# --------------------------------------------------------------------------- 图 6：状态机

def state_box(c, x, y, w, h, name, sub, color=TEXT, fill="#ffffff", stroke=BORDER, bold=True):
    c.rect(x, y, w, h, fill=fill, stroke=stroke, r=h / 2, width=1.6)
    c.text(x + w / 2, y + h / 2 - (8 if sub else 0), name, size=12.8,
           color=color, bold=bold, anchor="mm")
    if sub:
        c.text(x + w / 2, y + h / 2 + 12, sub, size=10, color=DIM, anchor="mm")


def diagram_states(out):
    c = Canvas(1440, 900)
    header(c, "图 4 · 两个状态机：服务端的流状态 与 浏览器的渲染相位",
           "服务端状态决定「重连时补完历史要不要关闭」；浏览器相位决定「界面显示什么、还要不要继续重连」。")

    # ---------------- 服务端 ----------------
    c.text(50, 118, "服务端 StreamState（Redis: dorm:stream:state:{messageId}，TTL 30 分钟）",
           size=13.4, color=TEXT, bold=True)
    state_box(c, 70, 200, 200, 64, "GENERATING", "已起流，模型在生成", BLUE, BLUE_BG, BLUE)
    targets = [
        ("DONE", "正常完成 / 工具超限的优雅收口", GREEN, GREEN_BG, 470, 158),
        ("BLOCKED", "输出触发护栏", AMBER, AMBER_BG, 470, 238),
        ("INTERRUPTED", "用户停止 / 断线宽限超时", VIOLET, VIOLET_BG, 470, 318),
        ("FAILED", "异常 / 空回答 / 超时", ROSE, ROSE_BG, 470, 398),
    ]
    for name, sub, col, bg, x, yy in targets:
        state_box(c, x, yy, 290, 64, name, sub, col, bg)
        c.arrow(270, 232, x - 6, yy + 32, col, 1.6)

    # 事件 → 状态 对照
    c.rect(820, 150, 540, 300, fill=PANEL, stroke=BORDER, r=12, width=1.4)
    c.text(842, 180, "终态事件与落库状态的对应", size=12.8, color=TEXT, bold=True)
    rows = [
        ("DONE", "DONE", "COMPLETED（含优雅收口）"),
        ("BLOCKED", "BLOCKED", "BLOCKED"),
        ("INTERRUPTED", "INTERRUPTED", "INTERRUPTED（保留半成品）"),
        ("ERROR", "FAILED", "FAILED"),
    ]
    yy = 206
    for ev, st, msg in rows:
        c.text(842, yy, ev, size=11.4, color=TEXT, bold=True)
        c.text(972, yy, "→", size=11.4, color=DIM)
        c.text(998, yy, st, size=11.4, color=TEXT, bold=True)
        c.text(1150, yy, msg, size=10.8, color=MUTED)
        yy += 30
    c.text(842, 344, "状态同时用于三处：续传是否关闭连接、", size=10.8, color=DIM)
    c.text(842, 364, "断线看门狗是否继续等待、审计记哪个 action。", size=10.8, color=DIM)
    c.text(842, 396, "键不存在 → UNKNOWN：按「无可续传内容」处理，", size=10.8, color=ROSE)
    c.text(842, 416, "避免把「已过期」误当成「还在生成」。", size=10.8, color=ROSE)

    c.text(70, 486, "终态 = 不会再产生新事件：续传补完历史即可关闭；"
                    "非终态必须继续挂在 Pub/Sub 上等下一帧。",
           size=11, color=MUTED)

    # ---------------- 浏览器 ----------------
    c.line(50, 540, 1390, 540, GRID, 1.2)
    c.text(50, 566, "浏览器 phase（useAgentStream）", size=13.4, color=TEXT, bold=True)

    state_box(c, 80, 640, 120, 54, "idle", None, DIM, "#ffffff")
    state_box(c, 250, 640, 150, 54, "connecting", "已发请求", BLUE, BLUE_BG, BLUE)
    state_box(c, 450, 640, 150, 54, "streaming", "逐帧消费", TEAL, TEAL_BG, TEAL)
    c.arrow(200, 667, 246, 667, BLUE, 1.6)
    c.arrow(400, 667, 446, 667, TEAL, 1.6)
    c.label(423, 645, "200", 10.4, TEAL, BG)

    ends = [("done", "收到终态，停止重连", GREEN, 588),
            ("blocked", "内容被护栏拦截", AMBER, 650),
            ("interrupted", "已保留半成品", VIOLET, 712),
            ("error", "展示错误码与 traceId", ROSE, 774)]
    for name, sub, col, yy in ends:
        state_box(c, 860, yy, 240, 54, name, sub, col, "#ffffff")
        c.arrow(600, 667, 856, yy + 27, col, 1.4)

    state_box(c, 450, 768, 190, 52, "reconnecting", "退避 1→2→4→8→10s", AMBER, AMBER_BG)
    c.arrow(525, 694, 525, 764, AMBER, 1.6)
    c.label(630, 730, "流断了但没收到终态", 10.2, AMBER, BG)
    c.arrow(450, 794, 450, 697, TEAL, 1.5, dash=True)
    c.label(392, 748, "重连成功", 10.2, TEAL, BG)
    c.arrow(640, 794, 856, 806, ROSE, 1.4, dash=True)
    c.label(748, 816, "8 次都失败", 10.0, ROSE, BG)

    c.text(80, 858, "用户点「停止」：先 abort 本地读取，再 POST /api/agent/chat/{messageId}/stop；"
                    "服务端立刻收口为 INTERRUPTED（不等模型自然结束）。",
           size=10.8, color=MUTED)
    c.save(out)


# --------------------------------------------------------------------------- 图 7：LLM 交互

def diagram_llm(out):
    c = Canvas(1500, 980)
    header(c, "图 7 · LLM 交互层：路由 → 装配 → 降级 → 工具循环 → RAG / 记忆",
           "一个接口（DormAgent），多份装配（专家 × 角色）。差别全在配置：提示词、工具子集、模型链、记忆。")

    # 1 消息与路由
    c.card(40, 130, 250, 96, "用户消息", [
        "已过输入护栏与 PII 脱敏",
    ], accent=BLUE)
    c.arrow(290, 178, 340, 178, BLUE, 2.0)

    c.rect(342, 118, 300, 250, fill=PANEL, stroke=BORDER, r=12, width=1.5)
    c.text(360, 146, "AgentRouter 意图路由", size=13.2, color=TEXT, bold=True)
    steps = [
        ("① 关键词打分", "5 类意图，命中词加权"),
        ("② ≥6 分直接采用", "确定、即时、零成本"),
        ("③ 否则快模型分类", "只输出一个词，解析失败回退"),
        ("④ 兜底 ROOM", "挂了最全的查询工具"),
    ]
    yy = 174
    for t, s in steps:
        c.rect(358, yy, 268, 44, fill="#ffffff", stroke="#dbe3ef", r=7, width=1.0)
        c.text(370, yy + 16, t, size=11.4, color=TEXT, bold=True)
        c.text(370, yy + 33, s, size=9.8, color=DIM)
        yy += 48
    c.arrow(642, 243, 690, 243, BLUE, 2.0)

    # 2 专家注册表
    c.rect(692, 118, 300, 250, fill=VIOLET_BG, stroke="#ddd6fe", r=12, width=1.5)
    c.text(710, 144, "AgentRegistry：专家 × 角色", size=13, color=VIOLET, bold=True)
    experts = ["ROOM 房源顾问", "POLICY 政策顾问", "APPLY 申请助手",
               "ORDER 订单助手", "OPS 运维（仅管理员）"]
    yy = 166
    for e in experts:
        c.rect(710, yy, 264, 30, fill="#ffffff", stroke="#e9d5ff", r=6, width=1.0)
        c.text(722, yy + 15, e, size=11, color=TEXT)
        yy += 34
    c.text(710, 344, "员工角色看不到 OPS：越权从装配期就消除", size=9.8, color="#5b21b6")
    c.arrow(992, 243, 1040, 243, VIOLET, 2.0)

    # 3 AiServices 装配
    c.rect(1042, 110, 418, 266, fill="#ffffff", stroke=BORDER, r=12, width=1.5)
    c.text(1060, 136, "AiServices.builder(DormAgent)", size=13, color=TEXT, bold=True)
    parts = [
        ("系统提示词", "按请求动态生成：身份 / 角色 / 日期"),
        ("ChatMemory", "窗口 20 条 + MySQL 存储 + 本轮内存视图"),
        ("ToolProvider", "GuardedToolProvider 包住每个工具"),
        ("模型", "FailoverStreamingChatModel（候选链）"),
    ]
    yy = 158
    for t, s in parts:
        c.rect(1058, yy, 384, 48, fill=PANEL, stroke="#e2e8f0", r=7, width=1.0)
        c.text(1070, yy + 17, t, size=11.4, color=TEXT, bold=True)
        c.text(1070, yy + 35, s, size=9.8, color=DIM)
        yy += 54

    # 4 模型层（右侧竖排）
    c.arrow(1251, 378, 1251, 396, AMBER, 1.8)
    c.rect(1042, 400, 418, 170, fill=AMBER_BG, stroke="#fcd34d", r=12, width=1.5)
    c.text(1060, 426, "模型选择 / 降级 / 保护", size=13, color=AMBER, bold=True)
    c.text(1060, 450, "候选链：routing[type] → fast → primary → fallback", size=10.4, color="#78350f")
    c.text(1060, 472, "熔断：连续失败 N 次 → 跳过；半开探测成功即恢复", size=10.4, color="#78350f")
    c.text(1060, 494, "闸门：Semaphore 16，最多等 8s，拿不到就业务提示", size=10.4, color="#78350f")
    c.text(1060, 516, "只在「还没吐出任何 token」时切换模型", size=10.4, color="#78350f")
    c.text(1060, 538, "已出内容后失败 → 如实报错并保留半成品", size=10.4, color="#78350f")

    # 设计取舍说明（填补中部空白，也回答「为什么不是一个巨型 Agent」）
    c.rect(40, 396, 960, 158, fill=SLATE_BG, stroke=GRID, r=12, width=1.2)
    c.text(58, 422, "为什么是「Supervisor + 专家」而不是一个巨型 Agent", size=12.6,
           color=TEXT, bold=True)
    notes = [
        "· 工具更少：政策顾问只挂 2 个只读工具，模型选错工具的概率显著下降。",
        "· 提示词更聚焦：每类专家只写自己那一段职责，回答风格不会来回摇摆。",
        "· 角色即权限：员工角色的模型在装配期就看不到运维工具，越权无从发生。",
        "· 成本可差异化：路由 / 标题 / 摘要走快模型，重任务才用主模型。",
    ]
    for i, line in enumerate(notes):
        c.text(58, 450 + i * 24, line, size=10.8, color=MUTED)

    # 5 工具循环 + RAG（下半部分）
    c.line(40, 590, 1460, 590, GRID, 1.2)
    c.text(40, 612, "工具循环（LangChain4j 内部，串行执行）", size=13.4, color=TEXT, bold=True)

    chain = [
        ("工具 Schema 下发", "25 个工具按专家裁剪"),
        ("GuardedToolExecutor", "取消 → 熔断 → 去重 → 重试"),
        ("DormServiceClient", "JWT + 内部令牌 + traceId"),
        ("dorm-service /internal", "业务规则只在这里执行"),
        ("结果回灌", "assistant(tool_calls) + TOOL"),
    ]
    x = 40
    for i, (t, s) in enumerate(chain):
        c.rect(x, 640, 240, 74, fill=TEAL_BG if i % 2 else "#ffffff",
               stroke="#99f6e4" if i % 2 else BORDER, r=10, width=1.3)
        c.text(x + 14, 664, t, size=11.6, color=TEXT, bold=True)
        c.text(x + 14, 686, s, size=9.8, color=DIM)
        if i < len(chain) - 1:
            c.arrow(x + 240, 677, x + 272, 677, TEAL, 1.6)
        x += 276

    c.text(40, 740, "去重只对只读工具（工具名 + 参数指纹）；写工具绝不自动重试 —— "
                    "重试一次已经成功的写操作就是重复单据。", size=10.6, color=MUTED)

    # RAG
    c.rect(40, 770, 700, 178, fill=VIOLET_BG, stroke="#ddd6fe", r=12, width=1.4)
    c.text(58, 796, "RAG：以「工具」形式接入（PolicyTools.searchDormPolicy）",
           size=12.6, color=VIOLET, bold=True)
    rag = [
        "① 结果缓存（同问高并发直接复用）",
        "② 本地 BGE-small-zh 向量化（512 维，ONNX，内嵌）",
        "③ 向量检索 TopK×2，按区域过滤（属地 + 全局）",
        "④ MMR 重排（λ=0.7，去掉同段落的多个变体）",
        "⑤ 写检索日志 + 缓存；返回「带出处的片段」而非现成答案",
    ]
    for i, line in enumerate(rag):
        c.text(58, 822 + i * 22, line, size=10.4, color="#4c1d95")

    # 记忆
    c.rect(770, 770, 690, 178, fill=GREEN_BG, stroke="#86efac", r=12, width=1.4)
    c.text(788, 796, "记忆：三层来源，只有一份真相", size=12.6, color=GREEN, bold=True)
    mem = [
        "· 短期：MessageWindow(20) + DormChatMemoryStore（MySQL，重启不丢）",
        "· 本轮：工具循环的内存覆盖层（否则模型看不到上一轮工具结果）",
        "· 长期：会话摘要（超过 12 轮异步生成）+ 用户偏好（用户可见可清）",
        "· 落库只由 AgentTurnService 负责：seq / turnId / token / 状态都在一处",
        "· STREAMING / BLOCKED 的消息绝不回灌上下文（否则半句与违规内容会绕过护栏）",
    ]
    for i, line in enumerate(mem):
        c.text(788, 822 + i * 22, line, size=10.2, color="#14532d")

    c.save(out)


# --------------------------------------------------------------------------- 入口

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "docs", "diagrams")
    print("生成示意图 →", out)
    diagram_topology(os.path.join(out, "01-topology.png"))
    diagram_admission(os.path.join(out, "02-sequence-admission.png"))
    diagram_generation(os.path.join(out, "03-sequence-generation.png"))
    diagram_resume(os.path.join(out, "05-sequence-resume.png"))
    diagram_channel(os.path.join(out, "06-sse-channel-backpressure.png"))
    diagram_states(os.path.join(out, "04-state-machines.png"))
    diagram_llm(os.path.join(out, "07-llm-interaction.png"))
    print("完成。")


if __name__ == "__main__":
    main()
