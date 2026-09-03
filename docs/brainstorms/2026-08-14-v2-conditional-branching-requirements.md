---
date: 2026-08-14
topic: v2-conditional-branching
title: v2 条件分支（运行时动态路由 + on_error 兜底）需求
---

## Summary

给 AgentFlow 的静态 DAG 加上**条件分支**：节点出边可带 `when` 谓词在运行时选一条下游路径，节点可声明 `on_error: goto` 在失败时跳兜底节点。保持图无环、保留 BSP super-step 分层与 barrier，只把「跑每一层所有节点」改成「跑每一层**可达**节点」。

---

## Problem Frame

v1 的工作流是**静态 DAG**：`WorkflowDefinition` 只有固定 `nodes` + `edges`，`DAGLayerer.computeSuperSteps` 在执行前用最长路径一次性算出所有节点的层号，`BspEngine` 逐层「并行执行 → barrier」。这意味着 v1 无法表达「节点 A 的输出决定下一步走 B 还是 C」，也无法表达「节点失败时跳 cleanup 而非整体 abort」——这两点正是 `02-requirements.md` 明列的 v2 能力（KTD-9：`on_error: goto cleanupNode`；Deferred 项：Agent 输出动态决定下一步）。

这是对 v1 边界最尖锐的追问点：「你 v1 只做静态 DAG，动态路由怎么办？」。本需求把这条从「承认是边界」变成「有实现深度的一章」。**先做条件分支而非直攻循环/反思**，是因为条件分支是通往循环的必经最小步——在保住「无环」不变量的前提下先落运行时路由；循环/反思（回边）是更远的差异化能力，不是被无限搁置。

---

## Key Decisions

- **分支表达用「条件边」，不用路由节点、不用 Agent 吐 next**。在 `EdgeDefinition` 上加可选 `when` 谓词，保持「万物皆 nodes + edges」——多条出边共享一个 `from`，运行时按谓词选第一条为真。相比「路由节点 + 映射表」少引入一种节点类型，相比「Agent 直接吐 next 字段」保留静态可校验性。**迁移语义：一个节点一旦拥有带 `when` 的出边，其出边就从 v1 的并行 fan-out 切换为独占路由；需要并行 fan-out 时，用无条件边（或中间节点）表达。**
- **不允许循环，仍是 DAG**。分支是「选一条下游路径」，多条分支可以汇合到同一节点，但不能回边到上游。这保住了 `DAGLayerer` 的预分层与「图无环」不变量。
- **执行模型用「BSP + 动态可达性剪枝」，不放弃 BSP**。静态图（忽略谓词）仍可预分层；运行时每个 barrier 之后，把「被路由决策切断的路径下游」标为 SKIPPED、不再执行。**这是两处深引擎改动，不是最小增量**：① 可达性剪枝是全新概念（v1 每层无条件全跑，无就绪/可达门）；② `on_error` 把「任一节点失败即 abort」（`applyBarrier` 对任一 `NodeResult.Failure` 抛异常中止）改成「终态失败可续跑」。两处都要动 `BspEngine` 的 barrier 逻辑、checkpoint、recovery。
- **`when` 谓词用 SpEL，但新增布尔求值入口，不复用 `SpelPromptResolver` 本体**。项目已有 `com.agentflow.prompt.SpelPromptResolver`，但它只做 `${...}` 字符串模板替换（返回 `String`，根对象 `Root(context, inputs)`），无 `output` 根、不返回 boolean，不能直接当谓词求值器。真正复用的是它那几行 hardened `SimpleEvaluationContext`（禁 `T()`、禁方法调用、`DataBindingPropertyAccessor` + `MapAccessor`，KTD-2 安全）；需要**新增一个谓词求值入口**（原始表达式 → boolean，根对象为 from 节点的输出，`output.<field>` 引用）。
- **`on_error: goto <nodeId>` 是节点属性，不是边，但作为隐式边参与环校验**。语义对齐 KTD-9；触发时机是「节点**终态失败**」（retry 耗尽或 Fatal）。**「下游」定义为静态图可达性**（含正常边 + on_error 隐式边），on_error 的回边同样被环校验捕获。
- **同一节点可同时声明条件边与 on_error，两者互斥不叠加**。成功时按谓词选分支；终态失败时走 on_error。`on_error` 目标自身失败不触发二次跳转，走终态失败路径。
- **承认确定性的取舍**：数据驱动路由使「执行哪些节点」取决于运行时输出，BSP 保留的是静态分层 + barrier，失去的是执行路径的确定性；R10 的 checkpoint 路由决策重放把它买回来——这是「BSP 怎么在动态路由下保留」的叙事核心。

---

## Requirements

**DSL 表达**

- R1. 边可带可选 `when` 谓词。`when` 是布尔表达式，求值上下文是出边 `from` 节点的输出，用 `output.<field>` 引用字段。
- R2. 一个节点的多条出边中，最多一条不带 `when`（默认边）。运行时按声明顺序求值各 `when`，取第一条**求值为 true** 的边；都不为 true 且无默认边 → 工作流 FAILED，诊断为「无分支命中」而非静默挂起。
- R3. 节点可声明 `on_error: goto <nodeId>`。节点终态失败时，引擎跳过其正常下游、改走该节点，工作流不因这一处失败而整体 abort。

**执行模型与校验**

- R4. 引擎保留 BSP super-step 执行：每层内所有**可达**节点并行执行，之后 barrier。可达 = 存在一条从源节点出发、全由「已走边」（命中的条件边 / 默认边 / on_error 跳转）构成的路径到达该节点。
- R5. 不可达节点标记为 SKIPPED（非 FAILED），不执行、不触发重试、不写 channel。
- R6. 分支保持无环：静态图（普通边 + on_error 隐式边，忽略谓词）须通过无环校验；`on_error` 目标必须是静态图可达的下游节点；回边在解析期拒绝。
- R7. 校验层（`SemanticValidator`）拒绝：条件边目标不存在、单节点多条默认边、`on_error` 目标不存在、`on_error` 目标指向非下游（上游）节点、静态环。

**可观测与恢复**

- R8. 执行轨迹记录每次路由决策（命中了哪条边、哪些节点被 SKIPPED），供事后 trace / 诊断解释为何选了某条分支。
- R9. 工作流级 outcome 区分三种终态：正常完成、经 `on_error` 兜底完成、FAILED。FAILED 的**原因**（无分支命中 / 节点终态失败）作为诊断标签，不单列 outcome。
- R10. checkpoint 持久化**路由决策**（已走边 / on_error 跳转目标）作为唯一权威记录；恢复时用「已走路径 + 静态图」确定性重算 SKIPPED 集合，不单独落库。恢复不重跑已完成的分支节点、不复活 SKIPPED 节点。checkpoint/recovery 层需新增「路由决策」记录承载位。

**向后兼容**

- R11. 不含条件边、不含 `on_error` 的现有静态 DAG 工作流行为不变；动态机制是增量的，无条件构造时关闭。

---

## Key Flows

- F1. 条件路由
  - **Trigger:** 一个带条件出边的节点完成、输出就绪。
  - **Steps:** 引擎按声明顺序求值各出边 `when` → 取第一条为 true（或默认边）→ 标记未走分支的下游为 SKIPPED → 被选路径的节点进入后续可达集合。
  - **Covered by:** R1, R2, R4, R5

- F2. on_error 兜底
  - **Trigger:** 节点终态失败（retry 耗尽或 Fatal）。
  - **Steps:** 引擎查该节点的 `on_error` 目标 → 标记其正常下游 SKIPPED → 激活兜底节点 → 工作流继续，整体不 FAILED。
  - **Covered by:** R3, R4, R5

- F3. 无分支命中失败
  - **Trigger:** 节点所有 `when` 均为 false 且无默认边。
  - **Steps:** 引擎终止工作流 → 标记 FAILED → 诊断写「无分支命中」。
  - **Covered by:** R2, R9

---

## Acceptance Examples

- AE1. 二路分支。节点 `classify` 出边 `when: output.verdict == 'approved'` → `approve`，另有默认边 → `reject`。给定 `verdict=approved`，`approve` 执行、`reject` 标 SKIPPED。
- AE2. 无默认且不命中。节点只有带 `when` 的出边、谓词均 false → 工作流 FAILED、诊断为「无分支命中」。
- AE3. on_error 兜底。节点终态失败 → `on_error` 目标执行、正常下游 SKIPPED → 工作流按「兜底完成」结束。
- AE4. 分支汇合。两条分支汇入同一 join 节点；被选分支完成后 join 执行一次，不被已跳过分支阻塞。
- AE5. 静态回归。不含条件构造的既有工作流逐层执行、行为与 v1 一致。
- AE6. 环拒绝。`on_error` 目标指向上游节点，或条件边构成静态环 → 解析期校验拒绝。
- AE7. 双 channel join 一侧被跳过。两条分支分别写不同 channel 后汇入 join；被跳分支的 channel 从未被写，join 引用该 channel 读到缺键/缺失值，谓词与模板求值对缺失键宽容，不抛异常。
- AE8. 恢复不复活 SKIPPED。崩溃后 `recoverAndExecute` 按持久化的路由决策重算 SKIPPED，被跳节点不重跑、不重复计费。
- AE9. 谓词求值错误可诊断。`when: output.verdct == 'approved'`（字段拼错）→ 求值异常按 Fatal 暴露并诊断，而非静默当作「不命中」走默认边。
- AE10. 条件边 + on_error 组合。同一节点同时带条件出边与 `on_error`：成功走谓词分支，终态失败走 `on_error`，两者互斥不叠加。

---

## Scope Boundaries

**Deferred for later**

- 循环 / 回边（反思、重试闭环、迭代到收敛）——需动态 ready-set 执行模型，本次不碰。
- `when` 谓词引用跨节点 context（`context.<channel>`）——首版只引用 `from` 节点输出 `output`，跨节点引用后续评估。
- 分支的运行时可视化（UI 高亮实际走路径）——先落到 trace 数据，可视化另排。

**Outside this product's identity**

- 让 Agent 在运行时**发明**任意新节点 / 任意目标（无声明、纯自主构图）——与「YAML 声明式 + 可静态校验」的定位冲突，不做。

---

## Success Criteria

- 新增一个演示工作流（对齐 U11/U12 的 demo 模块风格），覆盖条件路由、分支汇合、`on_error` 兜底三类场景。
- 覆盖动态语义的正确性验收：双 channel join 一侧被跳过、恢复不复活 SKIPPED、谓词求值错误可诊断（对应 AE7/AE8/AE9）。
- 全仓 `mvn verify` 绿（JaCoCo ≥ 80% 门禁不破）。
- 能一句话讲清「BSP 怎么在动态路由下保留」——保留静态分层 + barrier、牺牲执行路径确定性、checkpoint 重放买回确定性。

---

## Dependencies / Assumptions

- `SpelPromptResolver` 的 hardened `SimpleEvaluationContext` 配置可被布尔谓词复用；但谓词求值需要**新增入口**（表达式 → boolean、根对象为 from 节点输出），`SpelPromptResolver` 本体（字符串模板替换）不可直接复用。
- 谓词求值**异常**（SpEL 解析错误、类型不匹配、字段缺失、`T()` 违例）按 Fatal 暴露并可诊断，与既有「SpEL 错误即 Fatal」约定一致；只有成功求值且结果为 false 才算「不命中」。
- `on_error` 目标节点自身的失败不触发二次跳转（避免级联兜底），走正常终态失败路径。
- `when` 谓词的「声明顺序」由 YAML 边的列表顺序决定，DSL 解析层须保持边为有序列表（不落成 Set/Map），否则 first-true-wins 会静默突变。

---

## Sources / Research

- `docs/plans/agentflow/02-requirements.md` — R18/R19 与 Deferred 项「运行时条件分支（v2）」。
- `docs/plans/agentflow/03-key-technical-decisions.md` — KTD-9（静态 DAG 的 v2 扩展边界）。
- `docs/plans/agentflow/04-high-level-design.md` — BSP 执行模型与状态机。
- `agentflow-core/src/main/java/com/agentflow/dsl/DAGLayerer.java` — 静态最长路径分层（本需求要兼容的前提）。
- `agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java` — 逐层并行 + barrier；`applyBarrier` 对任一失败抛异常中止（on_error 要改的不变量）。
- `agentflow-core/src/main/java/com/agentflow/dsl/EdgeDefinition.java` — 现有边定义（`when` 谓词的落点）。
- `agentflow-core/src/main/java/com/agentflow/dsl/SemanticValidator.java` — 环校验（`on_error` 隐式边需纳入）。
- `agentflow-core/src/main/java/com/agentflow/prompt/SpelPromptResolver.java` — 字符串模板替换（复用其 hardening 配置，非本体）。
- `agentflow-core/src/main/java/com/agentflow/engine/checkpoint/` — NodeStatus/ExecutionState/RecoveryProtocol（SKIPPED 与路由决策的承载位）。
