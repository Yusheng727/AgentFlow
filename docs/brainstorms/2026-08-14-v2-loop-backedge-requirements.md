---
date: 2026-08-14
topic: v2-loop-backedge
title: v2 循环/回边（有界有环图 + 迭代轮次执行）需求
---

## Summary

给 AgentFlow 的静态 DAG 加循环/回边：从「无环」放宽到「有界有环」，让节点输出决定「重跑一段子图直到收敛或达迭代上限」。最小切入是反思循环（生成→评估→不满意回边重跑→满意退出），复用 v2 条件边的 `when` 谓词做「回边 = 指向更早节点的条件边」；执行从「一次性预分层」升级为「外层迭代轮次 + 每轮内可达性剪枝」，checkpoint 增加「迭代轮次」维度。

## Problem Frame

v2 条件分支已把「静态 DAG」扩成「无环 + 运行时路由」，但循环/回边仍被排除在硬边界之外：`SemanticValidator.checkAcyclic` 用 Kahn 拓扑强制无环，`DAGLayerer` 依赖无环做最长路径分层，`BspEngine` 用「一次性 for 遍历预分层」执行、superStep 是有限静态层号。三者共同构成「不能循环」的边界。

循环是条件分支的自然延伸，也是 Multi-Agent 编排的经典模式（Anthropic「Building Effective Agents」把 reflection 列为核心 pattern）。能力追问链从「动态路由怎么办」自然延伸到「那迭代收敛怎么办」——这正是条件分支 brainstorm 的 Scope Boundaries 明示的「更远的差异化能力，不是被无限搁置」。

三个候选切入（反思 / 重试闭环 / 迭代到收敛）本质相同：一个带终止条件的循环，区别在环的规模和收敛判据位置。选反思循环切入，因为它是最小增量、复用条件边谓词最多、叙事最完整；重试闭环是它的退化形式，迭代到收敛留作后续。

## Key Decisions

**切入反思循环，而非迭代到收敛。** 反思（生成→评估→回边→退出）是最小的循环形态，复用 v2 条件边谓词最多；迭代到收敛（多节点循环 + 状态稳定判据）不在本需求。

**图模型 = 显式回边 + 有界有环。** 回边是一条「指向图中不晚于源节点」的边，显式标记 `loop: true`（不靠环检测自动推断，避免「识别回边需要分层、分层需要无环」的循环依赖）。回边从静态分层豁免，静态图（去回边）仍无环、可最长路径分层。

**退出条件 = 回边上的 `when` 谓词，复用条件边语义。** 回边必须带 `when`（无条件回边会无限循环）；节点按声明序求值出边 `when` 取第一条 true——回边命中继续迭代，退出边（默认边/另一条件边）命中离开环。这是 v2 `resolveTakenTargets` 的复用，非新增求值机制。

**执行模型 = 保留预分层 + 外层迭代轮次，而非放弃 BSP。** 静态分层忽略回边，barrier 边界不变；外层 while 轮次循环，每轮内复用 v2 可达性剪枝逐层执行。环上节点每轮执行一次，上一轮输出对下一轮可见。这是「BSP 怎么在循环下保留」的答案：静态分层 + barrier 不变，代价是节点可能重复执行，checkpoint 轮次重放买回确定性。

**有界性 = 每环声明迭代上限 + 引擎全局兜底。** 校验强制每个环声明 `max_iterations`（静态保证有界）；引擎另设全局 hard stop（纵深防御，防逻辑 bug）。

**checkpoint 加迭代轮次维度。** barrier / 节点级 / 路由决策 checkpoint 从「superStep」扩到「(round, superStep)」；恢复期重建「第几轮 + 已完成节点 + 路由决策」，崩溃层已完成节点跳过不重复计费。

## Requirements

**图模型与 DSL**

- R1. 边可显式标记为回边（`loop: true`），回边指向图中不晚于源节点的节点（形成环）。
- R2. 回边必须带 `when` 谓词；无条件回边在解析期拒绝。
- R3. 每个环必须声明迭代上限（`max_iterations`）；未声明上限的环在解析期拒绝。

**执行模型**

- R4. 静态分层豁免回边：回边不参与 DAG 最长路径分层，静态图（去回边）仍须无环。
- R5. 迭代轮次：引擎外层按轮次循环；每轮内按静态分层逐层执行可达节点（复用 v2 可达性剪枝），barrier 后求值回边/退出条件。
- R6. 环上节点每轮执行一次，上一轮输出对下一轮可见（喂回语义）。
- R7. 终止条件：退出边命中（离开环）、或达到迭代上限（FAILED，诊断「迭代超限」）、或无可执行节点。

**checkpoint 与恢复**

- R8. checkpoint 增加迭代轮次维度：barrier / 节点级 / 路由决策均记录 (round, superStep)。
- R9. 恢复期重建「第几轮 + 已完成节点 + 已走路由决策」；同轮崩溃层已完成节点跳过不重复计费，不复活已完成轮次。

**可观测与兼容**

- R10. trace 记录迭代轮次与每轮回边/退出决策，供诊断「为何迭代了 N 次」。
- R11. 无回边的现有工作流行为不变（退化回 v2 条件分支的静态执行）。

## Key Flows

- F1. 反思循环收敛
  - **Trigger:** 回边节点（评估）完成、输出就绪。
  - **Steps:** 按声明序求值出边 `when` → 回边命中（如 `output.score < 0.8`）→ 记录回边决策 → 进入下一轮、回边目标重新激活；退出边命中 → 离开环、下游激活。
  - **Covered by:** R1, R2, R5, R6

- F2. 迭代超限失败
  - **Trigger:** 环达到 `max_iterations` 仍未退出。
  - **Steps:** 引擎终止 → FAILED → 诊断「迭代超限」。
  - **Covered by:** R3, R7

- F3. 循环中崩溃恢复
  - **Trigger:** 第 N 轮崩溃。
  - **Steps:** 恢复期查最新 barrier 的 (round, superStep) + 路由决策 → 重建第 N 轮崩溃层状态 → 已完成节点跳过、其余重跑。
  - **Covered by:** R8, R9

## Acceptance Examples

- AE1. 二轮收敛：generate→critique，score 0.6→回边重跑，0.9→退出 finalize；generate 执行 2 次、finalize 执行 1 次。
- AE2. 迭代上限：`when` 恒真、`max_iterations=3` → 第 3 轮后 FAILED「迭代超限」，不无限循环。
- AE3. 无条件回边拒绝：回边无 `when` → 解析期校验拒绝。
- AE4. 无上限环拒绝：回边带 `when` 但环无 `max_iterations` → 解析期校验拒绝。
- AE5. 喂回可见：第 N+1 轮 generate 能读到第 N 轮 critique 输出。
- AE6. 恢复不重复计费：第 2 轮崩溃 → 恢复从第 2 轮崩溃层继续，第 1 轮节点不重跑。
- AE7. 静态回归：无回边工作流逐层执行，行为与 v2 一致。

## Scope Boundaries

**Deferred for later**

- 多节点迭代到收敛（辩论收敛、状态稳定判据）
- 图级并发循环（多个环并行、嵌套环）
- 循环运行时可视化（UI 高亮迭代轮次与回边路径）

**Outside this product's identity**

- Agent 运行时发明任意新节点/目标（纯自主构图）——与「YAML 声明式 + 可静态校验」冲突，不做。

## Dependencies / Assumptions

- v2 条件分支的 `when` 谓词、可达性剪枝、路由决策持久化已落地，本需求复用（回边 = 条件边 + `loop` 标记 + 指向更早节点）。
- 「上次输出喂回」的引用语义：复用 v2 的 `context.<channel>`（channel 每轮 barrier 后更新），不引入新的「上一轮」引用视图（待 planning 确认）。
- 恢复期 inputs 不可得（原入参未持久化）的既有约束在循环下不变；谓词/模板求值对缺失键宽容。
- SpEL 谓词求值错误按 Fatal 暴露并可诊断（与 v2 约定一致）。

## Sources / Research

- docs/plans/agentflow/02-requirements.md — Deferred 项「运行时条件分支（v2）」与 KTD-9
- docs/brainstorms/2026-08-14-v2-conditional-branching-requirements.md — 条件分支需求（Scope Boundaries 明示「循环/回边」为后续）
- agentflow-core/src/main/java/com/agentflow/dsl/SemanticValidator.java — 当前无环校验（checkAcyclic，需放宽为「有界有环」）
- agentflow-core/src/main/java/com/agentflow/dsl/DAGLayerer.java — 最长路径分层（回边需豁免）
- agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java — 预分层 for 循环 + 可达性剪枝（迭代轮次改造点）
- agentflow-core/src/main/java/com/agentflow/engine/checkpoint/RecoveryProtocol.java — superStep 维度恢复（轮次维度改造点）
- agentflow-core/src/main/java/com/agentflow/dsl/EdgeDefinition.java — 回边标记落点
