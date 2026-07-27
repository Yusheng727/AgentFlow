# 实现思路与技术决策

> 回答「为什么这么设计」类问题的弹药。每条是「决策 + 为什么 + 替代方案 + 为什么不选」。
> 面试官最爱深挖的就是「为什么」，这里备好有证据的答案。

---

## 为什么选 BSP（Bulk Synchronous Parallel）执行模型？

**决策**：AgentFlow 用 BSP 驱动多 Agent 协作——工作流按 DAG 最长路径分层成 super-step，每个 super-step 内节点并行执行（互不可见），barrier 同步后合并 channel，再进下一层。

**为什么**：
- **天然契合 LLM Agent 工作流**：大部分 Agent DAG 是「分层依赖」（分析→汇总、收集→决策），BSP 的 super-step 分层直接对应这种依赖结构，不需要复杂的事件驱动调度。
- **确定性**：同一输入 + 同一 channel 合并序（声明序 Reducer）= 同一输出。调试可复现。
- **容错边界清晰**：barrier 是天然的 checkpoint 点——一个 super-step 完成即一个持久化单元，崩溃恢复边界明确。

**替代方案**：
- **Actor 模型**（如 Akka）：消息驱动，灵活但非确定性强、调试难，Agent 间依赖要自己管。对静态 DAG 过重。
- **事件驱动/DAG 调度器**（如 Airflow）：节点完成触发下游，适合无屏障依赖；但 channel 合并、并发写冲突处理要自己造，不如 BSP 的 Reducer 内建。
- **LangGraph 的图遍历**：节点驱动，无显式 super-step 概念。BSP 的 super-step 让「并行节点互不可见」这个不变式更易保证（只读快照）。

**为什么不选**：BSP 的代价是「barrier 等最慢节点」——一个慢 Agent 拖慢整个 super-step。v1 通过节点级超时 + cancel 中断缓解，v2 可考虑 work-stealing。但对 v1 的静态 DAG + 中等规模，BSP 的确定性和容错边界优势远大于这个代价。

---

## 为什么用两级 Checkpoint（节点级 + barrier 级）？

**决策**：节点级（Agent 完成当下立即持久化 output）+ barrier 级（super-step 合并后持久化 channel 快照）。

**为什么**：
- **节点级防 LLM 重复计费（R3）**：LLM 调用贵且有副作用（计费）。崩溃恢复时，已完成节点的 output 直接复用，不重调 LLM。
- **barrier 级防 channel 状态丢失**：channel 快照是下游 super-step 的输入。只有节点级 checkpoint 不够——崩溃后能复用节点 output，但不知道哪些 channel 值是「合并后」的，会重复合并或漏合并。barrier checkpoint 锁定「已完成 super-step 的 channel 状态」。
- **恢复边界清晰**：`latestBarrier.step = k` → `nextSuperStep = k+1`，崩溃层是 k+1。查 k+1 的 COMPLETED 节点跳过，其余重跑。

**替代方案**：
- **单级（只 barrier）**：崩溃在 super-step 中间，该层所有节点都得重跑——LLM 重复计费，违反 R3。
- **单级（只节点）**：恢复时要自己重建 channel 合并状态，逻辑复杂且易错（哪些 channel 已合并、合并序）。
- **事件溯源（event sourcing）**：记录每次 channel write，回放重建。最灵活但实现重、回放慢，对 v1 过度设计。

**为什么不选**：两级 checkpoint 的复杂度可接受——节点级是 per-node INSERT（同步），barrier 级是 per-super-step INSERT（barrier 后一次），写放大可控。Recovery 逻辑就两步查（latestBarrier + findCompletedNodes），简单。

---

## 为什么 off-by-one 修复查 `nextSuperStep` 而非 `nextSuperStep-1`？

**决策**：Recovery 查崩溃层本身（`nextSuperStep = latestBarrier.step + 1`）的 COMPLETED 节点，不是已 barrier 的层（`nextSuperStep - 1`）。

**为什么**：
- `latestBarrier.step = k` 表示「super-step k 已 barrier 合并完成」。
- 崩溃发生在 super-step N 执行中 → 最新 barrier 是 N-1 → `nextSuperStep = N`。
- 崩溃层 N 中可能有节点在崩溃前已完成（saveNodeOutput 写了 COMPLETED，但 barrier 未执行）。这些节点的 output 已持久化，恢复时应跳过避免重跑 LLM。
- 查 `nextSuperStep - 1 = N-1` 会返回**已 barrier 层**的节点——这些节点的 output 已经进 barrier 了，跳过它们是对的，但**漏掉了崩溃层 N 中已完成的节点**，导致那些节点被重复执行 → LLM 重复计费，违背 R3。

**这是 plan review 发现的真 bug**（P0-1）：伪代码最初写错查 `targetSuperStep-1`，v4 修正为 `nextSuperStep`。

**深挖**：但这个修复只解决了「节点重复执行」，没解决「跳过节点的 channel 输出如何恢复」——这是 ce-code-review 后来发现的 ADV-1（见 [02-bugs-and-fixes Bug-4](./02-bugs-and-fixes.md#bug-4p0-崩溃层-completed-节点的-channel-输出丢失--adv-1)），靠 replayOutputs 机制补全。

---

## 为什么用 Virtual Threads + Semaphore(20) 限流？

**决策**：Java 21 Virtual Threads 并行执行 super-step 内节点；`Semaphore(20)` 限制同时在飞的 checkpoint DB 写入。

**为什么**：
- **Virtual Threads**：super-step 内节点数不固定（v1 Demo 最大并行度 3，但拓扑可到 8+）。VT 轻量（~KB 级），可创建数千个不耗资源，由 JVM 调度到 carrier thread。比传统线程池更适配「节点数动态」的场景。
- **Semaphore(20)**：VT 让并发不再受线程数限制，但 DB 连接池（HikariCP 默认 max 10-20）是硬瓶颈。50+ VT 同时 `saveNodeOutput` 会耗尽连接池。Semaphore 在应用层限流，把并发 DB 写入压到 ≤ HikariCP maxPoolSize，避免连接池耗尽。

**替代方案**：
- **固定线程池**：并行度受池大小限制，且池大小难定（不同工作流节点数不同）。VT 更灵活。
- **异步批量写**：节点完成不立即写，攒批后一次 flush。但 R3 要求「节点完成立即保存」——攒批有丢数据窗口（攒批期间崩溃丢整批）。v4.3 改回同步写。
- **不限流**：50+ VT 同时打 DB → HikariCP 等连接 → 超时雪崩。

**为什么不选**：VT + Semaphore 的组合兼顾了「并行度高」和「DB 不被打挂」。v1 Demo 最大并行度 3，Semaphore(20) 完全不构成瓶颈；即便 stretch 到 50 节点并行，也能安全限流。

**深挖**：Semaphore 的 `acquire/release` 必须 try/finally，否则 DB 写异常时 permit 泄漏，反复失败会耗尽 20 个 permit 导致死锁。这个 pattern 在 review 里被 reliability reviewer 确认实现正确。

---

## 为什么 channel 合并用 Reducer 而非让 Agent 自己协调？

**决策**：channel 声明 Reducer 策略（OVERWRITE / CONCAT / MAX / CUSTOM），同一 super-step 内多个节点写同一 channel 时，按节点声明序用 Reducer 合并。

**为什么**：
- **并行节点互不可见**（BSP 语义）：同一 super-step 的节点用只读快照，不能看到彼此的写。所以并发写同一 channel 不能靠「后写覆盖前写」（顺序非确定），必须有确定性的合并策略。
- **声明序保证确定性**：按 YAML 里节点的声明序应用 Reducer，同一输入永远同一输出。可复现、可调试。
- **Reducer 可组合**：OVERWRITE 适合「最后结果」、CONCAT 适合「收集多路」、MAX 适合「取最优」、CUSTOM 适合业务逻辑。覆盖常见场景。

**替代方案**：
- **让 Agent 自己处理冲突**：每个 Agent 检查 channel 当前值再决定怎么写——违反 BSP 互不可见，且 Agent 要懂并发协调，职责泄漏。
- **CRDT**：无冲突合并，但 CRDT 类型有限（G-Counter、LWW-Set 等），不能表达「MAX」或「CUSTOM 业务逻辑」。

**为什么不选**：Reducer 简单、确定、可组合。CRDT 过度学术化，Agent 自协调违反 BSP 语义。

---

## 为什么 SpEL 解析 prompt 模板，禁用 `T()`？

**决策**：YAML 节点的 `prompt_template` 用 `${...}` 引用 context 变量，SpEL `SimpleEvaluationContext` 解析，**禁用 `T()` 类型引用和方法调用**。

**为什么**：
- **安全**：`T(java.lang.System).exit(0)` 这种 SpEL 注入是经典攻击面。AgentFlow 的 prompt 模板可能包含用户输入（如 `inputs`），如果允许 `T()`，攻击者可通过 inputs 注入恶意 SpEL 执行任意代码。
- **Spring 7 SpEL 变化**：`forReadOnlyDataBinding()` 在 Spring 7 不再注册 `MapAccessor`（Map 属性访问移出 ReflectivePropertyAccessor）。用 `forPropertyAccessors(DataBindingPropertyAccessor.forReadOnlyAccess(), new MapAccessor())` 同时支持 record 组件 + 嵌套 Map 键访问，且不设 TypeLocator/MethodResolver → `T()` 和方法调用都抛 `SpelEvaluationException`。

**替代方案**：
- **字符串替换**（`String.replace`）：简单但不支持嵌套路径（`${context.financeAnalysis.riskScore}`）。
- **全功能 SpEL**：灵活但有注入风险。
- **自研模板引擎**：重复造轮子。

**为什么不选**：SpEL 禁 `T()` + 只读 + MapAccessor 的组合，既支持嵌套路径引用，又堵死注入。安全审计友好。

---

## 为什么 CheckpointManager 是 SPI 接口（U2 引入 seam，U5 实现）？

**决策**：U2 引入 `CheckpointManager` 接口 + `NoopCheckpointManager`（默认），BSP 引擎依赖接口；U5 提供 `InMemoryCheckpointManager`（开发测试）+ `PostgresCheckpointManager`（生产）+ `RecoveryProtocol`。

**为什么**：
- **解耦**：U2 BSP 引擎的核心逻辑（分层/并行/barrier/Reducer）不应依赖具体持久化实现。接口让引擎可独立编译、独立测试（用 Noop），持久化策略可插拔。
- **渐进交付**：U2 先把引擎做对（57 tests），U5 再接持久化（20 tests），各自 verify 绿。如果一开始就绑死 PG，U2 测试要起 DB，反馈慢。
- **测试替身**：`InMemoryCheckpointManager` 用 `ConcurrentHashMap`，开发测试不需要 DB；`PostgresCheckpointManager` 生产用。同一接口，两套实现，测试/生产切换零成本。

**替代方案**：
- **U2 直接实现 PG checkpoint**：U2 测试要起 PG，开发反馈慢；且 U2 核心逻辑和持久化耦合，职责不清。
- **不要接口，直接 new PostgresCheckpointManager**：测试要 mock 时无法替换。

**为什么不选**：SPI seam 是教科书级解耦，且 plan 明确 U2 seam → U5 实现的交付节奏。代价是接口要稳定（U5 扩展了 4 个方法 + U14 又改了签名，导致合并冲突——见 [CLAUDE.md 当前状态](../../CLAUDE.md)），但接口演进的可控性 > 一次性绑死。
