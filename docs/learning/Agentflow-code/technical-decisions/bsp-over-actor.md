# 技术决策：BSP 执行模型 + 可达性剪枝（而非放弃 BSP 走事件驱动/Actor）

> 分析时间：2026-08-25 ｜ target：main@2a37d6b27e5d50687c656adbeedd2b92c0fdee92 ｜ 模式：deep-dive
> 分析范围：KTD-1 BSP 选型 + v2 动态路由/循环扩展策略 ｜ 未覆盖/不可访问区域：无
> 状态：生效中（三次演进均保持 BSP 不变量）[代码已确认]

## 1. 背景与约束

- 多 Agent 工作流的节点=LLM 调用（分钟级、按 token 计费），执行语义的核心诉求是**确定性**：谁与谁并行、输出何时可见、失败定位到层 [需求已确认]（KTD-1）。
- 业界对照：LangGraph（Python StateGraph）、Spring AI Alibaba（Sequential/Parallel/Routing/Loop 模式）已存在 [需求已确认]（01-problem-frame 对比表）。
- 本项目定位从0复现展示工程深度，选型本身就是展示物 [需求已确认]（01-problem-frame「诚实声明」）。

## 2. 当前方案

静态 DAG 预分层（`DAGLayerer.computeSuperSteps`：`level[v]=max(level[u])+1`）→ 每层 VT 并行 + allOf barrier → 声明序 Reducer 合并 → 逐层推进。v2 两个扩展均不动分层结构：

1. **动态路由=可达性剪枝**：静态图仍全量分层，运行时每层只跑 `active` 集合内的节点，被切断的标 SKIPPED [代码已确认]（`agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#runStep` 过滤逻辑）。
2. **循环=回边豁免分层**：静态分层时去掉 loop 边仍无环可分层；执行时外层 runRounds 轮次循环，回边目标进 nextActive 下一轮从层 0 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/dsl/DAGLayerer.java` + `BspEngine.java#runRounds`）。

## 3. 可确认的替代方案

- `docs/plans/agentflow/07-sources-revision-interview.md` 档 C 自述稿含「BSP vs Actor」叙事追问的收口（说明 Actor 模型作为备选被明确讨论过）[历史已确认]。
- 未在可访问资料中发现事件驱动/数据流引擎方案的实现痕迹或详细比较文档。

## 4. 为什么当前方案可行（代码事实论证）

- **免锁并发**：全局 context 只在 barrier 单线程段写、节点持 `Map.copyOf` 只读快照——并发正确性不依赖锁 [代码已确认]（`agentflow-core/src/main/java/com/agentflow/engine/WorkflowContext.java#L11-L16`）。
- **确定性可测**：同层并发写同一 channel，按 nodeIds 声明序合并（SuperStep record javadoc 明示）——并发测试可确定性断言 [代码已确认]。
- **恢复语义简单**：barrier 边界=checkpoint 边界，崩溃定位「最新 barrier+1」一句话说清 [代码已确认]（RecoveryProtocol 五步算法）。
- **扩展不破坏不变量**：路由/循环两次演进均保持「层内并行、层间同步」——runRounds 三入口共享同一骨架是结构证据 [代码已确认]（CodeGraph: 恰 3 callers）。

## 5. 正面影响

- 379 个 core 测试可确定性断言并发行为；恢复/审批/循环三路径复用同一执行骨架。
- 「BSP 怎么在动态路由/循环下保留」有单句面试答案（可达性剪枝/回边豁免+轮次重放）[历史已确认]（CLAUDE.md 面试叙事段）。

## 6. 代价与风险

- **barrier 串行瓶颈**：最快等最慢（LLM 调用时长方差大时层等待浪费）[代码已确认]（allOf 语义本身）。
- **动态图表达力受限**：运行时才知道的节点数（如 map-reduce 每项一节点）无法表达——静态分层要求节点集编译期已知 [合理推断]（支撑：WorkflowDefinition.nodes 是 YAML 声明的固定列表，无动态展开语法）。
- 回边使环上节点每轮重执行（无增量记忆）[代码已确认]（runRounds 每轮从层 0 完整遍历）。

## 7. 何时可能需要替换

[合理推断] 触发条件：① 节点时长方差大到层等待成为主要延迟（可用 node.duration P95/P50 比值监控）→ 考虑细粒度调度或 Actor 化；② 出现 map-reduce 动态展开需求 → 需 DSL 动态节点语法（破坏静态分层前提）。

## 8. 证据

| # | 证据 | 类型 | 支持的结论 |
|---|---|---|---|
| 1 | `docs/plans/agentflow/03-key-technical-decisions.md` KTD-1 | 需求文档 | 选型决议原文 |
| 2 | `agentflow-core/src/main/java/com/agentflow/dsl/DAGLayerer.java` | 源码 | 最长路径分层实现 |
| 3 | `agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#L343-L374` | 源码 | runRounds 轮次循环 |
| 4 | commit `93fab2a`/`4fe5c42` | git | 路由/循环两次演进落地 |
| 5 | `docs/plans/agentflow/07-sources-revision-interview.md` | 需求文档 | BSP vs Actor 追问收口 |

## 9. 个人学习收获

我学到的最重要一点：**架构选型的价值不在「选了什么」而在「守住了什么不变量」**。BSP 的不变量是「层内并行、层间同步、barrier 确定性合并」——路由和循环两次演进都在问「怎么在不破坏这三句话的前提下扩展」，答案（可达性剪枝/回边豁免）都是「静态结构不变、运行时语义扩展」。对比直接换执行模型的方案，这种演进的测试/恢复/可观测基建全部零改动。面试被问「为什么不用 Actor」时，答案的核心不是 Actor 不好，而是 BSP 的确定性语义（免锁、可测、恢复简单）对 LLM 工作流更值钱——Actor 的活泛在这里是负资产。
