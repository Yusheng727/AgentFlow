# -*- coding: utf-8 -*-
"""AgentFlow architecture diagram — Schematic Discipline style.
Renders a 2000x1400 PNG: left static compile chain, right BSP dynamic timeline.
"""
from PIL import Image, ImageDraw, ImageFont
import math

W, H = 2000, 1400
SS = 2  # supersample factor for crisp lines

# ---------- palette ----------
PAPER      = (250, 249, 246)   # warm paper white
INK        = (26, 32, 56)      # indigo ink — structure
INK_SOFT   = (98, 106, 132)    # muted ink — secondary text
GRID       = (228, 226, 219)   # engineering grid
AMBER      = (191, 116, 25)    # the ONE accent: barrier semantics
AMBER_FILL = (250, 236, 213)   # barrier wash
CARD       = (255, 255, 255)   # node card face
HAIRLINE   = (208, 205, 196)   # hairline strokes

# ---------- fonts ----------
FD = r"C:\Users\YushengWang\.claude\skills\canvas-design\canvas-fonts"
def F(path, size):
    return ImageFont.truetype(path, size)

f_title    = F(FD + r"\InstrumentSans-Regular.ttf", 40)
f_sub      = F(FD + r"\IBMPlexMono-Regular.ttf", 17)
f_node     = F(FD + r"\InstrumentSans-Bold.ttf", 24)
f_node_sub = F(r"C:\Windows\Fonts\Deng.ttf", 15)
f_label    = F(FD + r"\IBMPlexMono-Regular.ttf", 15)
f_label_b  = F(FD + r"\IBMPlexMono-Bold.ttf", 15)
f_small    = F(FD + r"\IBMPlexMono-Regular.ttf", 13)
f_tag      = F(FD + r"\IBMPlexMono-Regular.ttf", 12)
f_step     = F(FD + r"\IBMPlexMono-Bold.ttf", 14)
f_cjk      = F(r"C:\Windows\Fonts\Deng.ttf", 15)   # CJK-capable fallback (等线)

img = Image.new("RGB", (W*SS, H*SS), PAPER)
d = ImageDraw.Draw(img)

def S(v):  # scale
    return v * SS

def text(x, y, s, font, fill=INK, anchor="la", cjk_font=None):
    """Draw text; CJK runs auto-routed to a CJK-capable font (latin fonts lack CJK glyphs)."""
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
    # dashed line
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
    """solid triangular arrowhead at (x,y) pointing along angle (radians, 0=right)"""
    s = size * SS / 2
    ax, ay = math.cos(angle), math.sin(angle)
    p1 = (S(x) - ax*s, S(y) - ay*s)
    bx, by = -ay, ax
    p2 = (p1[0] + bx*s*0.62, p1[1] + by*s*0.62)
    p3 = (p1[0] - bx*s*0.62, p1[1] - by*s*0.62)
    d.polygon([ (S(x), S(y)), p2, p3 ], fill=fill)

def harrow(x1, x2, y, fill=INK, w=1.5, head=True):
    line(x1, y, x2 - (8 if head else 0), y, fill=fill, w=w)
    if head:
        arrow_head(x2, y, 0, fill=fill, w=w)

def varrow(x, y1, y2, fill=INK, w=1.5, head=True):
    line(x, y1, x, y2 - (8 if head else 0), fill=fill, w=w)
    if head:
        arrow_head(x, y2, math.pi/2, fill=fill, w=w)

def rrect(x1, y1, x2, y2, r=8, outline=INK, width=1.5, fill=CARD):
    d.rounded_rectangle([S(x1), S(y1), S(x2), S(y2)], radius=S(r),
                        outline=outline, width=max(1, round(width*SS)), fill=fill)

def hline_all(y, color=GRID, w=0.75):
    line(0, y, W, y, fill=color, w=w)

# ══════════════════ background: engineering grid ══════════════════
g = 40
for x in range(0, W+1, g):
    line(x, 0, x, H, fill=GRID, w=0.5)
for y in range(0, H+1, g):
    line(0, y, W, y, fill=GRID, w=0.5)

# margin frame (double hairline, drafting-sheet feel)
d.rectangle([S(28), S(28), S(W-28), S(H-28)], outline=HAIRLINE, width=SS)
d.rectangle([S(36), S(36), S(W-36), S(H-36)], outline=GRID, width=SS)

# ══════════════════ title block ══════════════════
text(72, 66, "AgentFlow", f_title, fill=INK)
text(72 + text_w("AgentFlow", f_title) + 14, 66 + 16, "·  BSP 编排引擎架构", f_sub, fill=INK_SOFT)
text(72, 108, "POST /api/workflows  →  WorkflowController  →  BspEngine", f_label, fill=INK_SOFT)
# small drafting tag top-right
text(W-72, 70, "FIG. 01", f_tag, fill=INK_SOFT, anchor="ra")
text(W-72, 88, "ENGINE / EXECUTION MODEL", f_tag, fill=INK_SOFT, anchor="ra")

# ══════════════════ lane separators ══════════════════
LANE_X = 700            # left lane right edge
line(LANE_X, 150, LANE_X, H-120, fill=HAIRLINE, w=0.75, dash=(6, 5))
text(72, 140, "STATIC COMPILE CHAIN", f_step, fill=INK_SOFT)
text(LANE_X + 32, 140, "RUNTIME · BSP SUPER-STEP TIMELINE", f_step, fill=INK_SOFT)

# ══════════════════ LEFT LANE: vertical compile chain ══════════════════
LX1, LX2 = 130, 560     # card bounds
def compile_card(y, name, sub, tag):
    rrect(LX1, y, LX2, y+64, r=7, outline=INK, width=1.5, fill=CARD)
    text(LX1+18, y+10, name, f_node, fill=INK)
    text(LX1+18, y+38, sub, f_node_sub, fill=INK_SOFT)
    # right-edge tag
    tw = text_w(tag, f_tag)
    text(LX2-tw-16, y+24, tag, f_tag, fill=INK_SOFT)

cy = [186, 306, 426, 546]
compile_card(cy[0], "WorkflowDSLParser",    "YAML 解析 + 三层校验",        "DSL")
compile_card(cy[1], "WorkflowDefinition",   "DAG + Channels（不可变）",    "DEF")
compile_card(cy[2], "DAGLayerer",           "最长路径分层 → super-steps",  "PLAN")
compile_card(cy[3], "CheckpointManager",    "两级：节点级 + barrier 级",   "CKPT")

# vertical arrows with side annotations
ann = [
    ("parse + validate", 244),
    ("ir", 364),
    ("layer assignment", 484),
]
for i, (lbl, _) in enumerate(ann):
    y0, y1 = cy[i]+64, cy[i+1]
    varrow(345, y0+6, y1-6, fill=INK, w=1.5)
    text(358, (y0+y1)//2 - 8, lbl, f_small, fill=INK_SOFT)

# left side: Auth filter + entry arrow
rrect(LX1, 636, LX2, 636+56, r=7, outline=INK_SOFT, width=1, fill=CARD)
text(LX1+18, 646, "ApiKeyAuthFilter", f_node, fill=INK)
text(LX1+18, 670, "SHA-256 · 所有权 / 工具授权", f_node_sub, fill=INK_SOFT)
# RecoveryProtocol
rrect(LX1, 716, LX2, 716+56, r=7, outline=INK_SOFT, width=1, fill=CARD)
text(LX1+18, 726, "RecoveryProtocol", f_node, fill=INK)
text(LX1+18, 750, "崩溃恢复 · off-by-one 修复", f_node_sub, fill=INK_SOFT)
# dashed connectors: auth -> checkpoint manager, recovery -> checkpoint manager
# arrow tips stop just below the card bottom edge (y=614), never pierce the card
line(LX2, 664, 626, 664, fill=INK_SOFT, w=0.75, dash=(4, 4))
line(626, 664, 626, 620, fill=INK_SOFT, w=0.75, dash=(4, 4))
arrow_head(626, 614, -math.pi/2, fill=INK_SOFT, size=6)

line(LX2, 744, 610, 744, fill=INK_SOFT, w=0.75, dash=(4, 4))
line(610, 744, 610, 620, fill=INK_SOFT, w=0.75, dash=(4, 4))
arrow_head(610, 614, -math.pi/2, fill=INK_SOFT, size=6)

# ══════════════════ RIGHT LANE: horizontal chain ══════════════════
RY = 236  # main row centerline
# node 1: POST
bw = text_w("POST /api/workflows", f_label) + 30
rrect(760, RY-26, 760+bw, RY+26, r=6, outline=INK, width=1.5, fill=CARD)
text(760+bw/2, RY-8, "POST /api/workflows", f_label, fill=INK, anchor="ma")
harrow(760+bw+14, 950-8, RY, fill=INK, w=2)
text((760+bw+14+950)/2, RY-30, "yaml", f_small, fill=INK_SOFT, anchor="ma")

# node 2: WorkflowController
rrect(950, RY-40, 1180, RY+40, r=7, outline=INK, width=1.5, fill=CARD)
text(1065, RY-28, "WorkflowController", f_node, fill=INK, anchor="ma")
text(1065, RY+6, "鉴权 · 守卫 · 派发", f_node_sub, fill=INK_SOFT, anchor="ma")
harrow(1180+14, 1330-8, RY, fill=INK, w=2)
text((1180+14+1330)/2, RY-30, "async 202", f_small, fill=INK_SOFT, anchor="ma")

# node 3: BspEngine (hero node)
rrect(1330, RY-48, 1620, RY+48, r=8, outline=INK, width=2.5, fill=CARD)
text(1475, RY-34, "BspEngine", f_node, fill=INK, anchor="ma")
text(1475, RY+2, "Plan → Execute → Barrier", f_node_sub, fill=INK_SOFT, anchor="ma")
text(1475, RY+24, "Virtual Threads", f_label, fill=INK_SOFT, anchor="ma")

# agent function note (top right, quiet)
text(1475, RY-92, "AgentFunction 唯一合约", f_small, fill=INK_SOFT, anchor="ma")
text(1475, RY-72, "Spring AI · LangChain4j · Mock · RAG", f_small, fill=INK_SOFT, anchor="ma")
line(1475, RY-54, 1475, RY-48, fill=INK_SOFT, w=0.75)

# ══════════════════ BSP timeline (below, right lane) ══════════════════
TY0 = 420   # super-step 0 row
SW_A, SW_B = 760, 1530   # timeline span

# step labels
text(720, TY0-6, "S0", f_step, fill=INK, anchor="ra")
text(720, TY0-6+78, "S1", f_step, fill=INK, anchor="ra")
text(700, TY0-34, "super-steps", f_small, fill=INK_SOFT, anchor="ra")

# 3 parallel node cards (equal modulus)
cw, ch, gap = 200, 58, 46
cx0 = 760
names = ["A", "B", "C"]
for i, n in enumerate(names):
    x = cx0 + i * (cw + gap)
    rrect(x, TY0, x+cw, TY0+ch, r=7, outline=INK, width=1.5, fill=CARD)
    text(x+cw/2, TY0+8, n, f_node, fill=INK, anchor="ma")
    text(x+cw/2, TY0+34, "agent", f_label, fill=INK_SOFT, anchor="ma")

# fan-out from engine down to S0 cards: spine + bus to cover all three taps
FANY = 372
line(1475, RY+48, 1475, FANY, fill=INK, w=1.5)
tops = [cx0 + i*(cw+gap) + cw/2 for i in range(3)]
BUS_L, BUS_R = tops[0], tops[2]
line(tops[0], FANY, BUS_R, FANY, fill=INK, w=1.5)
# spine joins the bus (spine x may sit right of the last tap)
if BUS_L <= 1475 <= BUS_R:
    pass  # already connected
else:
    line(BUS_R, FANY, 1475, FANY, fill=INK, w=1.5)
for tx in tops:
    varrow(tx, FANY, TY0-8, fill=INK, w=1.5)
text(1475+10, (RY+48+FANY)//2 - 8, "spawn VTs", f_small, fill=INK_SOFT)

# fan-in to barrier
BARY = 560
for tx in tops:
    line(tx, TY0+ch, tx, BARY, fill=INK, w=1.5)
    arrow_head(tx, BARY+0.5, math.pi/2, fill=INK, size=7)
# the barrier: the ONE amber element
line(SW_A, BARY, SW_B, BARY, fill=AMBER, w=3)
text(SW_A, BARY+14, "barrier  ·  全局同步  ·  reducer 合并", f_label_b, fill=AMBER)

# super-step 1: aggregate node
TY1 = 640
rrect(760, TY1, 760+320, TY1+70, r=7, outline=INK, width=1.5, fill=CARD)
text(920, TY1+12, "Supervisor", f_node, fill=INK, anchor="ma")
text(920, TY1+42, "aggregate", f_label, fill=INK_SOFT, anchor="ma")
harrow(920, 920, BARY+40, fill=INK, w=1.5) if False else None
# arrow from barrier to supervisor: drop from barrier midpoint
varrow(920, BARY+8, TY1-8, fill=INK, w=1.5)

# checkpoint save marks on both sides of barrier (Schematic: observation ticks)
text(SW_B+14, BARY-10, "ckpt » barrier level", f_small, fill=INK_SOFT)
line(SW_B, BARY, SW_B+14, BARY, fill=INK_SOFT, w=0.75)
text(SW_B+14, TY0+ch+10, "ckpt » node level", f_small, fill=INK_SOFT)
line(SW_B, TY0+ch, SW_B+14, TY0+ch, fill=INK_SOFT, w=0.75)

# downstream loop hint: iteration arrow (thin, dashed) from S1 back — bounded loop
text(1120, TY1+86, "回边 when 谓词 · max_iterations 有界", f_small, fill=INK_SOFT)
line(1080, TY1+35, 1120, TY1+35, fill=INK_SOFT, w=0.75)
line(1080, TY1+35, 1080, TY1+78, fill=INK_SOFT, w=0.75, dash=(3,3))
line(1080, TY1+78, 1120, TY1+78, fill=INK_SOFT, w=0.75, dash=(3,3))
arrow_head(1120, TY1+78, 0, fill=INK_SOFT, size=5)

# ══════════════════ CONVERGENCE: WorkflowContext ══════════════════
CY0 = 800
rrect(760, CY0, 1530, CY0+84, r=8, outline=INK, width=2, fill=CARD)
text(1145, CY0+12, "WorkflowContext", f_node, fill=INK, anchor="ma")
text(1145, CY0+44, "channel 快照 · 只读视图 · BarrierCheckpoint 持久化", f_node_sub, fill=INK_SOFT, anchor="ma")
# supervisor -> context
varrow(920, TY1+70, CY0-8, fill=INK, w=1.5)
# left chain also converges: checkpoint manager -> context (crossing lanes)
y_join = CY0 + 42
line(LX2, cy[3]+28, 700, cy[3]+28, fill=INK_SOFT, w=1, dash=(5, 4))
line(700, cy[3]+28, 700, y_join, fill=INK_SOFT, w=1, dash=(5, 4))
line(700, y_join, 760-8, y_join, fill=INK_SOFT, w=1, dash=(5, 4))
arrow_head(760, y_join, 0, fill=INK_SOFT, size=7)

# ══════════════════ legend (bottom left, baseline-aligned with convergence row) ══════════════════
LG_Y = 812
text(72, LG_Y, "LEGEND", f_step, fill=INK)
# solid = dataflow
line(72, LG_Y+34, 120, LG_Y+34, fill=INK, w=2); arrow_head(120, LG_Y+34, 0, fill=INK, size=6)
text(132, LG_Y+27, "数据流", f_node_sub, fill=INK_SOFT)
# dashed = recovery/async
line(72, LG_Y+62, 120, LG_Y+62, fill=INK_SOFT, w=1, dash=(4,4)); arrow_head(120, LG_Y+62, 0, fill=INK_SOFT, size=6)
text(132, LG_Y+55, "恢复 / 异步路径", f_node_sub, fill=INK_SOFT)
# amber = barrier
line(72, LG_Y+90, 120, LG_Y+90, fill=AMBER, w=3)
text(132, LG_Y+83, "同步屏障（barrier）", f_node_sub, fill=INK_SOFT)
# cards
rrect(72, LG_Y+112, 120, LG_Y+140, r=5, outline=INK, width=1, fill=CARD)
text(132, LG_Y+118, "引擎组件（引擎不感知 Agent 实现）", f_node_sub, fill=INK_SOFT)

# ══════════════════ footer strip ══════════════════
fy = H - 84
line(72, fy, W-72, fy, fill=HAIRLINE, w=0.75)
text(72, fy+14, "Java 21 · Virtual Threads · Spring Boot 4 · PostgreSQL · Kafka", f_tag, fill=INK_SOFT)
text(W-72, fy+14, "AGENTFLOW-2026 · REV A", f_tag, fill=INK_SOFT, anchor="ra")

# ---------- save ----------
out = img.resize((W, H), Image.LANCZOS)
out.save(r"C:\Users\YushengWang\project\my-project\AgentFlow\AgentFlow\docs\design\agentflow-architecture.png", dpi=(192, 192))
print("saved")
