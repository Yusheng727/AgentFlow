# 技术决策：两级 Checkpoint 只存最小事实，恢复期重算派生态

> 分析时间：2026-08-25 ｜ target：main@2a37d6b27e5d50687c656adbeedd2b92c0fdee92 ｜ 模式：deep-dive
> 分析范围：KTD-3 恢复协议设计（checkpoint 存什么/不存什么） ｜ 未覆盖/不可访问区域：无
> 状态：生效中（V1→V8 演进保持「存事实、重算派生」纪律）[代码已确认]

## 1. 背景与约束

- LLM 调用分钟级 + 按 token 计费 → 崩溃恢复必须避免重复计费（R3 软约束）但不能牺牲正确性（硬约束）[需求已确认]（RecoveryProtocol 注释原文「宁可 LLM 重复计费，不换错误结果」）。
- 无跨表事务（长事务在分钟级 LLM 调用下不可行）[代码已确认]（全部写入是独立单条 SQL）。

## 2. 当前方案

checkpoint 只持久化三类**最小事实**：
1. `workflow_node_outputs`：单节点 AgentOutput（COMPLETED 当下同步写）
2. `workflow_checkpoints`：barrier 后的 channel 快照
3. `workflow_routing_decisions`：每轮累计已走边

恢复期**重算**全部派生态：可达集（BFS `computeReachable`）、onError 激活集（`rebuildOnErrorActivated`）、下一轮起点（`backedgeTargets`）、轮次（`hasLoopDecision` 转换检测）[代码已确认]（`agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#L311-L336` + #L1041-L1089）。

一致性靠**写入顺序**而非事务：路由决策先于 barrier 落盘（崩溃窗口内路由不丢）[代码已确认]（`BspEngine.java#runStep` 末两行顺序）。

## 3. 可确认的替代方案

- 「把 SKIPPED 等派生状态也存进 checkpoint」的方案在 ce-doc-review 中被明确否决——「checkpoint 只持久化路由决策不存 SKIPPED 态」[历史已确认]（CLAUDE.md v2 条件分支交付记录，doc-review 13 findings 修正之一）。
- 未在可访问资料中发现事件溯源/快照全量存储方案的实现比较。

## 4. 为什么当前方案可行（代码事实论证）

- 事实间彼此独立落盘，任意崩溃点最多丢"最后一条"，恢复算法按最新事实重算——不存在状态间不一致 [代码已确认]（第 5 节崩溃窗口分析，4 个窗口全部有确定恢复行为）。
- COMPLETED 终态不可覆盖（`ON CONFLICT ... WHERE status<>'COMPLETED'`）——重放/重试幂等 [代码已确认]。
- round 维度加入时只需三表各加一列+唯一约束重构（V5），派生态重算逻辑零改动 [历史已确认]（commit `15dcbca`）。

## 5. 正面影响

- off-by-one 修复后恢复语义一句话可讲：「从最新 barrier+1 层起跑，崩溃层里 COMPLETED 的跳过+重放输出」。
- 迭代轮次/条件路由/审批暂停三个 v2 特性加入时恢复协议只做增量扩展（round 检测/BFS/审批单），核心算法稳定。

## 6. 代价与风险

- 恢复期重算有 O(V+E) BFS 成本（demo 规模可忽略）[代码已确认]（computeReachable 是标准 BFS）。
- replayOutputs 与 completedNodeIds「同序同源」靠注释约定无类型绑定 [待确认]（open-questions Q9）。
- 恢复入口本身无并发防护（双恢复双跑）[代码已确认]——生产靠调用纪律或外部 tryClaim。

## 7. 何时可能需要替换

[合理推断] 触发条件：① 工作流图规模大到 BFS/重放成为恢复延迟主项（万级节点）→ 考虑增量快照；② 需要跨表原子性保证的敏感写入出现 → 引入 outbox/事务边界。

## 8. 证据

| # | 证据 | 类型 | 支持的结论 |
|---|---|---|---|
| 1 | `agentflow-core/src/main/java/com/agentflow/engine/checkpoint/RecoveryProtocol.java#L21-L41` | 源码 | 五步算法 + off-by-one 修复自述 |
| 2 | `agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#L880-L882` | 源码 | 路由先于 barrier 落盘顺序 |
| 3 | `agentflow-core/src/main/resources/db/migration/V5__loop_round.sql` | 迁移 | round 维度增量加入（约束重构、算法零改动） |
| 4 | CLAUDE.md v2 条件分支交付记录 | 历史 | 「不存 SKIPPED 态」doc-review 修正 |
| 5 | commit `15537dc` | git | 恢复路径 2 P1 修复（mid-round 崩溃/双计费） |

## 9. 个人学习收获

这个决策让我真正理解了事件溯源的工程动机——不是因为时髦，而是「事实与派生态分离」在无事务环境下天然容错。对比直觉的「把所有状态存下来」：存的越多，崩溃窗口内状态间不一致的概率越大，恢复时要写越来越多的对账逻辑。而只存事实、重算派生，恢复=重放事实，代码里连"对账"这个概念都不存在。同构地，动态路由的「只存已走边」、审批的「只存快照+决策」都是同一思想的实例——这个项目里它至少出现了三次，说明作者是把这个当**设计纪律**而非单点技巧在用。面试讲「你的 checkpoint 设计」时，这一层抽象比表结构细节有价值得多。
