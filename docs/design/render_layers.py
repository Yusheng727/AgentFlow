# -*- coding: utf-8 -*-
"""AgentFlow layered architecture diagram — Schematic Discipline style.
FIG. 01: what the system is made of. Modules layered bottom→top:
DSL → core engine (BSP/checkpoint/fault/security) → adapters → api/kafka/starter → UI+infra.
Renders docs/design/agentflow-layers.png (2200x1500).
"""
from PIL import Image, ImageDraw, ImageFont
import math

W, H = 2200, 1500
SS = 2

# ---------- palette ----------
PAPER      = (250, 249, 246)
INK        = (26, 32, 56)
INK_SOFT   = (98, 106, 132)
GRID       = (228, 226, 219)
AMBER      = (191, 116, 25)
AMBER_FILL = (250, 236, 213)
CARD       = (255, 255, 255)
CORE_WASH  = (243, 245, 250)   # very light indigo wash — core engine zone
HAIRLINE   = (208, 205, 196)

# ---------- fonts ----------
FD = r"C:\Users\YushengWang\.claude\skills\canvas-design\canvas-fonts"
def F(path, size):
    return ImageFont.truetype(path, size)

f_title    = F(FD + r"\InstrumentSans-Regular.ttf", 44)
f_sub      = F(FD + r"\IBMPlexMono-Regular.ttf", 18)
f_node     = F(FD + r"\InstrumentSans-Bold.ttf", 26)
f_node_sub = F(r"C:\Windows\Fonts\Deng.ttf", 17)
f_label    = F(FD + r"\IBMPlexMono-Regular.ttf", 16)
f_label_b  = F(FD + r"\IBMPlexMono-Bold.ttf", 16)
f_small    = F(FD + r"\IBMPlexMono-Regular.ttf", 14)
f_tag      = F(FD + r"\IBMPlexMono-Regular.ttf", 13)
f_step     = F(FD + r"\IBMPlexMono-Bold.ttf", 15)
f_cjk      = F(r"C:\Windows\Fonts\Deng.ttf", 17)

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

def arrow_head(x, y, angle, fill=INK, size=8, w=1.5):
    s = size * SS / 2
    ax, ay = math.cos(angle), math.sin(angle)
    p1 = (S(x) - ax*s, S(y) - ay*s)
    bx, by = -ay, ax
    p2 = (p1[0] + bx*s*0.62, p1[1] + by*s*0.62)
    p3 = (p1[0] - bx*s*0.62, p1[1] - by*s*0.62)
    d.polygon([(S(x), S(y)), p2, p3], fill=fill)

def varrow(x, y1, y2, fill=INK, w=1.5, head=True):
    line(x, y1, x, y2 - (9 if head else 0), fill=fill, w=w)
    if head:
        arrow_head(x, y2, math.pi/2, fill=fill, size=8)

def harrow(x1, x2, y, fill=INK, w=1.5, head=True):
    line(x1, y, x2 - (9 if head else 0), y, fill=fill, w=w)
    if head:
        arrow_head(x2, y, 0, fill=fill, size=8)

def rrect(x1, y1, x2, y2, r=8, outline=INK, width=1.5, fill=CARD):
    d.rounded_rectangle([S(x1), S(y1), S(x2), S(y2)], radius=S(r),
                        outline=outline, width=max(1, round(width*SS)), fill=fill)

# ══════════════════ background grid ══════════════════
g = 40
for x in range(0, W+1, g):
    line(x, 0, x, H, fill=GRID, w=0.5)
for y in range(0, H+1, g):
    line(0, y, W, y, fill=GRID, w=0.5)

# margin frame
d.rectangle([S(28), S(28), S(W-28), S(H-28)], outline=HAIRLINE, width=SS)
d.rectangle([S(36), S(36), S(W-36), S(H-36)], outline=GRID, width=SS)

# ══════════════════ title block ══════════════════
text(72, 64, "AgentFlow", f_title, fill=INK)
text(72 + text_w("AgentFlow", f_title) + 16, 64 + 18, "·  模块分层架构  what the system is made of", f_sub, fill=INK_SOFT)
text(W-72, 66, "FIG. 01", f_tag, fill=INK_SOFT, anchor="ra")
text(W-72, 86, "MODULE / LAYERED ARCHITECTURE", f_tag, fill=INK_SOFT, anchor="ra")

# ══════════════════ geometry: 5 horizontal layers, bottom→top ══════════════════
# Each layer: left label gutter + card row. Canvas x 72..2128.
GUT = 72            # left gutter for layer labels
LX1, LX2 = 250, 2128
ROW_H = 150         # card height
GAP_Y = 56

layers = [
    # (tag, title, subtitle, cards[(name, sub)], wash)
    ("L5", "交付与体验层", "UI · REST API · Starter",
     [("agentflow-ui", "React 18 六 Tab 控制台"),
      ("agentflow-api", "REST · 鉴权 · 审批 · 诊断"),
      ("agentflow-starter", "@EnableAgentFlow 自动装配"),
      ("agentflow-kafka-starter", "Kafka 提交/执行解耦")], None),
    ("L4", "Agent 适配层", "框架调用收敛在适配器窄表面 —— 换框架只动适配器",
     [("adapters / spring-ai", "SpringAiAgentAdapter"),
      ("adapters / langchain4j", "LangChain4jAgentAdapter"),
      ("adapters / mock", "MockAgentFunction 零成本"),
      ("demo-rag 扩展点", "RagAgentFunction 检索→增强→委托")], None),
    ("L3", "引擎核心", "agentflow-core —— 零 LLM 框架依赖",
     [("BspEngine", "super-step 并行 + barrier"),
      ("两级 Checkpoint", "节点级 + barrier 级"),
      ("RecoveryProtocol", "崩溃恢复 · 路由决策重放"),
      ("容错链路", "Timeout→Classify→Retry"),
      ("security", "列加密 AES-256-GCM · 授权")], CORE_WASH),
    ("L2", "DSL 与定义层", "YAML → 不可变 DAG",
     [("WorkflowDSLParser", "三层校验"),
      ("DAGLayerer", "最长路径分层"),
      ("version 管理", "(name, version) 定义存储"),
      ("PredicateEvaluator", "when 谓词 · on_error · loop")], None),
    ("L1", "基础设施", "运行时依赖（外部）",
     [("PostgreSQL", "checkpoint · 列加密"),
      ("Kafka", "提交/执行解耦"),
      ("Micrometer + Grafana", "5 指标族 · 6 面板"),
      ("LLM Provider", "OpenAI 兼容 / DeepSeek")], None),
]

# layout rows bottom→top: L1 at bottom
n = len(layers)
bottom_y = H - 190            # L1 row top
def row_top(i):
    # i=0 → L1 (bottom)
    return bottom_y - i * (ROW_H + GAP_Y)

# core engine zone wash (behind L3 row)
core_top = row_top(2) - 18
core_bot = row_top(2) + ROW_H + 18
d.rectangle([S(GUT), S(core_top), S(LX2), S(core_bot)], fill=CORE_WASH)

for i, (tag, title, subtitle, cards, wash) in enumerate(layers):
    y = row_top(i)
    # left gutter: layer tag + title
    text(GUT, y + 12, tag, f_step, fill=INK_SOFT)
    text(GUT, y + 34, title, f_node, fill=INK)
    text(GUT, y + 66, subtitle, f_node_sub, fill=INK_SOFT)
    # cards
    nc = len(cards)
    total_w = LX2 - LX1
    gap_x = 28
    cw = (total_w - gap_x * (nc - 1)) / nc
    for j, (name, sub) in enumerate(cards):
        x = LX1 + j * (cw + gap_x)
        outline = INK if tag == "L3" else INK_SOFT
        wdt = 2 if tag == "L3" else 1.25
        rrect(x, y, x + cw, y + ROW_H, r=7, outline=outline, width=wdt,
              fill=CARD)
        text(x + cw/2, y + 22, name, f_node, fill=INK, anchor="ma")
        text(x + cw/2, y + 62, sub, f_node_sub, fill=INK_SOFT, anchor="ma")

# ══════════════════ vertical flow arrows between layers (dependency direction: top→bottom uses) ══════════════════
# draw arrows going DOWN from L4..L2 with side note "depends on"
for i in range(n - 1):
    # arrow from layer i+1 bottom to layer i top (L5→L4, L4→L3, L3→L2, L2→L1)
    y_from = row_top(i + 1) + ROW_H
    y_to = row_top(i)
    x = (LX1 + LX2) // 2
    varrow(x, y_from + 6, y_to - 6, fill=INK, w=2)
    text(x + 14, (y_from + y_to)//2 - 9, "依赖 ↓", f_small, fill=INK_SOFT)

# ══════════════════ the ONE amber: AgentFunction contract between L4 and L3 ══════════════════
# AgentFunction is the only contract crossing the core boundary — amber tick on the L4→L3 arrow
af_y_top = row_top(2) - 6             # L3 top edge
af_y_bot = row_top(2) + GAP_Y + ROW_H  # wait: L4 bottom = row_top(3)+ROW_H
af_x = LX1 + 200
line(af_x, row_top(3) + ROW_H + 6, af_x, row_top(2) - 6, fill=AMBER, w=3)
text(af_x + 14, (row_top(3) + ROW_H + row_top(2))//2 - 10,
     "AgentFunction  唯一跨层合约", f_label_b, fill=AMBER)
text(af_x + 14, (row_top(3) + ROW_H + row_top(2))//2 + 12,
     "core 不感知框架 / 适配器不感知编排", f_small, fill=INK_SOFT)

# ══════════════════ legend ══════════════════
LG_Y = 118
text(72, LG_Y, "阅读方式", f_step, fill=INK)
text(72, LG_Y + 26, "自下而上：基础设施 → DSL → 引擎核心 → Agent 适配 → 交付。", f_node_sub, fill=INK_SOFT)
text(72, LG_Y + 50, "琥珀线 = 唯一允许跨「引擎/Agent」边界的合约。", f_node_sub, fill=INK_SOFT)
line(72, LG_Y + 86, 116, LG_Y + 86, fill=AMBER, w=3)
text(128, LG_Y + 78, "AgentFunction 合约（窄边界）", f_node_sub, fill=INK_SOFT)
rrect(72, LG_Y + 104, 116, LG_Y + 132, r=5, outline=INK, width=1, fill=CARD)
text(128, LG_Y + 110, "引擎组件（零框架依赖）", f_node_sub, fill=INK_SOFT)

# ══════════════════ footer ══════════════════
fy = H - 84
line(72, fy, W-72, fy, fill=HAIRLINE, w=0.75)
text(72, fy + 14, "Java 21 · Spring Boot 4.1 · Spring AI 2.0 / LangChain4j · PostgreSQL · Kafka · Micrometer + Grafana", f_tag, fill=INK_SOFT)
text(W-72, fy + 14, "AGENTFLOW-2026 · REV B", f_tag, fill=INK_SOFT, anchor="ra")

out = img.resize((W, H), Image.LANCZOS)
out.save(r"C:\Users\YushengWang\project\my-project\AgentFlow\AgentFlow\docs\design\agentflow-layers.png", dpi=(192, 192))
print("saved layers")
