# -*- coding: utf-8 -*-
"""AgentFlow BSP execution timeline diagram — Schematic Discipline style.
FIG. 02: what happens during one workflow run. super-step parallelism,
barrier sync, two-level checkpoint, recovery.
Renders docs/design/agentflow-execution.png (2000x1400).
"""
from PIL import Image, ImageDraw, ImageFont
import math

W, H = 2000, 1400
SS = 2

PAPER      = (250, 249, 246)
INK        = (26, 32, 56)
INK_SOFT   = (98, 106, 132)
GRID       = (228, 226, 219)
AMBER      = (191, 116, 25)
AMBER_FILL = (250, 236, 213)
CARD       = (255, 255, 255)
HAIRLINE   = (208, 205, 196)

FD = r"C:\Users\YushengWang\.claude\skills\canvas-design\canvas-fonts"
def F(path, size):
    return ImageFont.truetype(path, size)

f_title    = F(FD + r"\InstrumentSans-Regular.ttf", 40)
f_sub      = F(FD + r"\IBMPlexMono-Regular.ttf", 17)
f_node     = F(FD + r"\InstrumentSans-Bold.ttf", 24)
f_node_sub = F(r"C:\Windows\Fonts\Deng.ttf", 16)
f_label    = F(FD + r"\IBMPlexMono-Regular.ttf", 15)
f_label_b  = F(FD + r"\IBMPlexMono-Bold.ttf", 15)
f_small    = F(FD + r"\IBMPlexMono-Regular.ttf", 13)
f_tag      = F(FD + r"\IBMPlexMono-Regular.ttf", 12)
f_step     = F(FD + r"\IBMPlexMono-Bold.ttf", 14)
f_cjk      = F(r"C:\Windows\Fonts\Deng.ttf", 16)

img = Image.new("RGB", (W*SS, H*SS), PAPER)
d = ImageDraw.Draw(img)

def S(v):
    return v * SS

def text(x, y, s, font, fill=INK, anchor="la", cjk_font=None):
    if cjk_font is None and any('一' <= c <= '鿿' for c in s):
        cjk_font = f_cjk
    d.text((S(x), S(y)), s, font=cjk_font or font, fill=fill, anchor=anchor)

def text_w(s, font):
    b = d.textbbox((0, 0), s, font=font)
    return (b[2] - b[0]) / SS

def line(x1, y1, x2, y2, fill=INK, w=1.5, dash=None):
    lw = max(1, round(w * SS))
    if dash is None:
        d.line([S(x1), S(y1), S(x2), S(y2)], fill=fill, width=lw)
        return
    on, off = dash
    dx, dy = x2 - x1, y2 - y1
    dist = math.hypot(dx, dy)
    if dist == 0: return
    ux, uy = dx / dist, dy / dist
    t = 0.0
    while t < dist:
        t2 = min(t + on, dist)
        d.line([S(x1+ux*t), S(y1+uy*t), S(x1+ux*t2), S(y1+uy*t2)], fill=fill, width=lw)
        t = t2 + off

def arrow_head(x, y, angle, fill=INK, size=7, w=1.5):
    s = size * SS / 2
    ax, ay = math.cos(angle), math.sin(angle)
    p1 = (S(x) - ax*s, S(y) - ay*s)
    bx, by = -ay, ax
    p2 = (p1[0] + bx*s*0.62, p1[1] + by*s*0.62)
    p3 = (p1[0] - bx*s*0.62, p1[1] - by*s*0.62)
    d.polygon([(S(x), S(y)), p2, p3], fill=fill)

def varrow(x, y1, y2, fill=INK, w=1.5, head=True):
    line(x, y1, x, y2 - (8 if head else 0), fill=fill, w=w)
    if head:
        arrow_head(x, y2, math.pi/2, fill=fill, size=8)

def harrow(x1, x2, y, fill=INK, w=1.5, head=True):
    line(x1, y, x2 - (8 if head else 0), y, fill=fill, w=w)
    if head:
        arrow_head(x2, y, 0, fill=fill, size=8)

def rrect(x1, y1, x2, y2, r=8, outline=INK, width=1.5, fill=CARD):
    d.rounded_rectangle([S(x1), S(y1), S(x2), S(y2)], radius=S(r),
                        outline=outline, width=max(1, round(width*SS)), fill=fill)

# background grid
g = 40
for x in range(0, W+1, g):
    line(x, 0, x, H, fill=GRID, w=0.5)
for y in range(0, H+1, g):
    line(0, y, W, y, fill=GRID, w=0.5)

d.rectangle([S(28), S(28), S(W-28), S(H-28)], outline=HAIRLINE, width=SS)
d.rectangle([S(36), S(36), S(W-36), S(H-36)], outline=GRID, width=SS)

# title block
text(72, 64, "AgentFlow", f_title, fill=INK)
text(72 + text_w("AgentFlow", f_title) + 14, 64 + 16, "·  BSP 执行时序  what happens in one run", f_sub, fill=INK_SOFT)
text(W-72, 66, "FIG. 02", f_tag, fill=INK_SOFT, anchor="ra")
text(W-72, 84, "ENGINE / BSP EXECUTION TIMELINE", f_tag, fill=INK_SOFT, anchor="ra")

# ─────────────── lane separator (same as FIG.01 dual-lane feel) ───────────────
LANE_X = 640
line(LANE_X, 140, LANE_X, H-120, fill=HAIRLINE, w=0.75, dash=(6, 5))
text(72, 130, "静态编译链", f_step, fill=INK_SOFT)
text(LANE_X + 32, 130, "运行时 · 一次工作流执行的时间轴", f_step, fill=INK_SOFT)

# ─────────────── LEFT lane: compile chain (condensed from old diagram) ───────────────
LX1, LX2 = 100, 560
def compile_card(y, name, sub):
    rrect(LX1, y, LX2, y+60, r=7, outline=INK, width=1.5, fill=CARD)
    text(LX1+18, y+10, name, f_node, fill=INK)
    text(LX1+18, y+36, sub, f_node_sub, fill=INK_SOFT)

cy = [190, 300, 410]
compile_card(cy[0], "YAML DSL",            "节点 / 边 / when / loop 声明")
compile_card(cy[1], "WorkflowDefinition", "解析 + 三层校验 → 不可变 DAG")
compile_card(cy[2], "DAGLayerer",          "最长路径分层 → super-steps")

for i in range(2):
    varrow(330, cy[i]+60+4, cy[i+1]-4, fill=INK, w=1.5)
    text(344, (cy[i]+60+cy[i+1])//2 - 8, "parse / layer", f_small, fill=INK_SOFT)

# two-level checkpoint card (bottom left, feeds into timeline)
rrect(LX1, 540, LX2, 640, r=7, outline=INK, width=2, fill=CARD)
text(LX1+18, 552, "两级 Checkpoint", f_node, fill=INK)
text(LX1+18, 578, "节点级：完成即写（防 LLM 重复计费）", f_node_sub, fill=INK_SOFT)
text(LX1+18, 600, "barrier 级：层合并快照（恢复边界）", f_node_sub, fill=INK_SOFT)
text(LX1+18, 622, "PostgreSQL / InMemory", f_small, fill=INK_SOFT)
# arrow from layerer to checkpoint
varrow(330, cy[2]+60+4, 540-4, fill=INK, w=1.5)

# ─────────────── RIGHT lane: runtime timeline ───────────────
RX = 700  # right lane left edge
def rt_card(x, y, w, h, name, sub, outline=INK, width=1.5):
    rrect(x, y, x+w, y+h, r=7, outline=outline, width=width, fill=CARD)
    text(x+w/2, y+8, name, f_node, fill=INK, anchor="ma")
    text(x+w/2, y+36, sub, f_node_sub, fill=INK_SOFT, anchor="ma")

# Step 0: submit
Y0 = 180
bw = text_w("POST /api/workflows", f_label) + 30
rt_card(RX, Y0-24, bw, 48, "POST /api/workflows", "", outline=INK_SOFT, width=1)
text(RX+bw/2, Y0+8, "submit", f_small, fill=INK_SOFT, anchor="ma")
harrow(RX+bw+12, 940-8, Y0, fill=INK, w=2)
text((RX+bw+12+940)/2, Y0-26, "202 + 异步执行", f_small, fill=INK_SOFT, anchor="ma")

# BspEngine hero card
rrect(940, Y0-44, 1230, Y0+44, r=8, outline=INK, width=2.5, fill=CARD)
text(1085, Y0-32, "BspEngine", f_node, fill=INK, anchor="ma")
text(1085, Y0+2, "runSuperStep × N", f_label, fill=INK_SOFT, anchor="ma")
text(1085, Y0+24, "Virtual Threads", f_small, fill=INK_SOFT, anchor="ma")

# compile chain feeds the engine (dashed, crossing lanes)
line(LX2, cy[2]+30, 700, cy[2]+30, fill=INK_SOFT, w=1, dash=(5, 4))
line(700, cy[2]+30, 700, Y0-10, fill=INK_SOFT, w=1, dash=(5, 4))
line(700, Y0-10, 940-8, Y0-10, fill=INK_SOFT, w=1, dash=(5, 4))
arrow_head(940, Y0-10, 0, fill=INK_SOFT, size=7)
text(712, cy[2]+14, "super-steps 计划", f_small, fill=INK_SOFT)

# ─────────────── super-step 0: 3 parallel agents ───────────────
S0_Y = 330
text(RX-12, S0_Y-34, "S0", f_step, fill=INK, anchor="ra")
names = ["Agent A", "Agent B", "Agent C"]
cw, ch, gap = 190, 64, 40
cx0 = 760
tops = [cx0 + i*(cw+gap) + cw/2 for i in range(3)]
for i, n in enumerate(names):
    x = cx0 + i * (cw + gap)
    rrect(x, S0_Y, x+cw, S0_Y+ch, r=7, outline=INK, width=1.5, fill=CARD)
    text(x+cw/2, S0_Y+8, n, f_node, fill=INK, anchor="ma")
    text(x+cw/2, S0_Y+38, "agent.apply()", f_label, fill=INK_SOFT, anchor="ma")

# engine fans out VTs to S0
FANY = S0_Y - 52
line(1085, Y0+44, 1085, FANY, fill=INK, w=1.5)
line(tops[0], FANY, tops[2], FANY, fill=INK, w=1.5)
for tx in tops:
    varrow(tx, FANY, S0_Y-8, fill=INK, w=1.5)
text(1097, (Y0+44+FANY)//2 - 8, "spawn VTs", f_small, fill=INK_SOFT)

# fan-in to barrier (THE amber line)
BARY = S0_Y + ch + 90
for tx in tops:
    line(tx, S0_Y+ch, tx, BARY, fill=INK, w=1.5)
    arrow_head(tx, BARY+0.5, math.pi/2, fill=INK, size=7)
line(740, BARY, 1560, BARY, fill=AMBER, w=3.5)
text(740, BARY+14, "barrier  ·  allOf 全局同步  ·  Reducer 声明序合并", f_label_b, fill=AMBER)

# node-level checkpoint ticks (on the fan-in, right side)
for tx in [tops[2]]:
    text(tx+14, S0_Y+ch+16, "ckpt » 节点级", f_small, fill=INK_SOFT)
    line(tx, S0_Y+ch+30, tx+12, S0_Y+ch+30, fill=INK_SOFT, w=0.75)

# ─────────────── super-step 1: aggregate ───────────────
S1_Y = BARY + 90
text(RX-12, S1_Y-34, "S1", f_step, fill=INK, anchor="ra")
rrect(cx0, S1_Y, cx0+300, S1_Y+64, r=7, outline=INK, width=1.5, fill=CARD)
text(cx0+150, S1_Y+8, "Supervisor", f_node, fill=INK, anchor="ma")
text(cx0+150, S1_Y+38, "汇总聚合", f_node_sub, fill=INK_SOFT, anchor="ma")
varrow(cx0+150, BARY+8, S1_Y-8, fill=INK, w=1.5)

# barrier-level checkpoint tick
text(1560+14, BARY-10, "ckpt » barrier 级", f_small, fill=INK_SOFT)
line(1560, BARY, 1572, BARY, fill=INK_SOFT, w=0.75)

# ─────────────── conditional / loop note (right of S1) ───────────────
NX = cx0 + 380
rrect(NX, S1_Y-6, NX+460, S1_Y+74, r=7, outline=INK_SOFT, width=1, fill=CARD)
text(NX+16, S1_Y+2, "动态路由 / 循环在此时间轴上展开", f_node_sub, fill=INK)
text(NX+16, S1_Y+26, "when 谓词剪枝下游（SKIPPED）· 回边 loop: true 进入下一迭代轮次", f_node_sub, fill=INK_SOFT)
text(NX+16, S1_Y+48, "路由决策随 checkpoint 持久化 → 恢复期重放", f_node_sub, fill=INK_SOFT)
line(cx0+300+8, S1_Y+28, NX-8, S1_Y+28, fill=INK_SOFT, w=1, dash=(4,4))
arrow_head(NX, S1_Y+28, 0, fill=INK_SOFT, size=6)

# ─────────────── success / recovery fork ───────────────
END_Y = S1_Y + 160
# success path
rrect(cx0, END_Y, cx0+300, END_Y+56, r=7, outline=INK, width=1.5, fill=CARD)
text(cx0+150, END_Y+8, "SUCCESS", f_node, fill=INK, anchor="ma")
text(cx0+150, END_Y+36, "context 输出 · metrics 记账", f_node_sub, fill=INK_SOFT, anchor="ma")
varrow(cx0+150, S1_Y+64, END_Y-8, fill=INK, w=1.5)

# crash + recovery path (dashed, from S0 barrier zone down the right side)
CRX = 1700
rrect(CRX-180, S1_Y+120, CRX+180, S1_Y+206, r=7, outline=INK_SOFT, width=1.25, fill=CARD)
text(CRX, S1_Y+130, "进程崩溃于 S1 执行中", f_node_sub, fill=INK_SOFT, anchor="ma")
text(CRX, S1_Y+152, "恢复：读最新 barrier（step=0）", f_node_sub, fill=INK_SOFT, anchor="ma")
text(CRX, S1_Y+174, "→ 只重跑崩溃层未完成节点", f_node_sub, fill=INK_SOFT, anchor="ma")
line(cx0+300+40, S1_Y+28+0, 0, 0, fill=INK_SOFT, w=0) # no-op guard
# dashed crash arrow from S1 zone to recovery card
line(1560, BARY+8, 1560, S1_Y+162, fill=INK_SOFT, w=1, dash=(4,4))
line(1560, S1_Y+162, CRX+180+8, S1_Y+162, fill=INK_SOFT, w=1, dash=(4,4))
arrow_head(CRX+180, S1_Y+162, math.pi, fill=INK_SOFT, size=6)
text(1560+14, S1_Y+120, "崩溃恢复路径", f_small, fill=INK_SOFT)

# ─────────────── legend ───────────────
LG_Y = 900
text(72, LG_Y, "阅读方式", f_step, fill=INK)
text(72, LG_Y+26, "一次提交沿时间轴向下：层内并行 →", f_node_sub, fill=INK_SOFT)
text(72, LG_Y+48, "琥珀 barrier 同步合并 → 下一层。", f_node_sub, fill=INK_SOFT)
text(72, LG_Y+70, "左列把 YAML 变成可执行计划；", f_node_sub, fill=INK_SOFT)
text(72, LG_Y+92, "虚线是崩溃恢复路径。", f_node_sub, fill=INK_SOFT)
line(72, LG_Y+130, 120, LG_Y+130, fill=AMBER, w=3)
text(132, LG_Y+122, "同步屏障（barrier）", f_node_sub, fill=INK_SOFT)
line(72, LG_Y+158, 120, LG_Y+158, fill=INK_SOFT, w=1, dash=(4,4)); arrow_head(120, LG_Y+158, 0, fill=INK_SOFT, size=6)
text(132, LG_Y+150, "恢复 / 异步路径", f_node_sub, fill=INK_SOFT)

# footer
fy = H - 84
line(72, fy, W-72, fy, fill=HAIRLINE, w=0.75)
text(72, fy+14, "CompletableFuture.allOf · Virtual Threads · 两级 Checkpoint · Reducer 声明序合并", f_tag, fill=INK_SOFT)
text(W-72, fy+14, "AGENTFLOW-2026 · REV B", f_tag, fill=INK_SOFT, anchor="ra")

out = img.resize((W, H), Image.LANCZOS)
out.save(r"C:\Users\YushengWang\project\my-project\AgentFlow\AgentFlow\docs\design\agentflow-execution.png", dpi=(192, 192))
print("saved execution")
