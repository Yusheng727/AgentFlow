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

---

## 为什么主 Demo 选 3 并行 → 1 汇总拓扑（供应商风险评估）？

**决策**：U10 供应商风险评估用「3 专家 Agent 并行分析（财务/合规/声誉）→ Supervisor 汇总评级」的 fork-join 拓扑。

**为什么**：
- **覆盖引擎核心能力**：并行节点互不可见（BSP 只读快照）、barrier 同步（等最慢专家）、channel 合并（三路输出写入独立 channel，汇总 SpEL 引用）、Reducer 确定性合并。单拓扑跑通即验证 BSP 全链路。
- **差异化的 Demo 场景**：供应商风险评估是经典的多视角决策——财务/合规/声誉三个维度天然并行、互相独立，汇总节点综合三路做评级。场景自洽，不需要编造用例。
- **mock 数据真实感**：财务（资产负债率 35%）、合规（1 次环保违规）、声誉（5 年合作），数据有区分度，汇总能产出有意义的 LOW risk → 可留观建议。

**替代方案**：
- **链式串行**（U11）：4 步串行依赖链，适合展示上下文传递，但不验证并行能力。
- **双层 fork-join**（U12）：更复杂拓扑（6 节点 4 super-step），能展示多层并行，但 Demo 调试成本高。

**为什么不选**：fork-join 是最典型的并行场景——3 个专家同时看一个问题然后汇总，这个模式理解成本最低。串行和双层 fork-join 留给辅助 Demo（U11/U12），主 Demo 先验证核心并行能力。

---

## 为什么 Demo 编程式组装引擎，不依赖 @EnableAgentFlow？

**决策**：U10 Demo 用 `new WorkflowDSLParser()` + `new BspEngine()` + `new InMemoryCheckpointManager()` 手动组装，不依赖 U13 的 `@EnableAgentFlow` 一键启动。

**为什么**：
- **证明引擎的「原子可用」**：不需要任何 Starter 封装，只需要 BspEngine + Parser + MockAgentFunction + YAML 即可跑通完整工作流。引擎核心自洽，Starter 是锦上添花。
- **v4.3 解耦约定**：plan 明确「Demo 不阻塞 Starter 交付」——Starter/Docker/docs 对引擎核心交付，Demo 是验证不是构建依赖。U10 自包含、U13 封装的分离策略让两者可独立 verify。
- **U13 尚未完成**：U10 在 U13 前做，没有 @EnableAgentFlow 可用。如果硬等 U13，U10 的验收场景（mock 跑通 <30s）无法验证。

**为什么不选**：等 U13 再跑 Demo 会推迟引擎验证——U10 的 Recovery 测试、并行验证、channel 传递验证都是引擎核心能力的回归点，越早跑越早发现 bug。

---

## 为什么 trace 穿线用 AgentInput 字段而非 ThreadLocal？

**决策**：U7 让 BspEngine 在 runSuperStep 构造 AgentInput 时塞入 `ExecutionTrace` 引用（AgentInput 第 9 字段），AgentFunction 从 `input.trace()` 取 trace 写 NodeTrace。traceRegistry=null 整条链路 no-op。

**为什么**：
- **Virtual Threads 跨任务边界 ThreadLocal 脆弱**（OQ-3 决议延伸）：BspEngine 用 VT 并行跑同 super-step 节点，trace 写入发生在 AgentFunction 内部，ThreadLocal 在 VT 调度切换时语义不可靠。record 字段显式传递是确定性的。
- **MockAgentFunction 是无状态单例**：per-agent-name 复用，不能持有 per-workflow 状态。trace 引用必须从调用入参（AgentInput）来，不能从单例字段来。
- **向后兼容**：trace 字段可空，旧构造器/旧工厂传 null，traceRegistry=null 时整条链路 no-op。U3 已有 `SpringAiAgentAdapter` 构造器注入 trace 的路径（OQ-3），U7 优先 `input.trace()`、构造器 trace 作 fallback。

**为什么不选**：改 per-workflow 实例破坏 MockAgentFunction 无状态单例假设（任意 agent name 复用）；新建 TraceContext carrier 对象对 v1 内部 API 是过度设计（v2 暴露公共 SPI 再考虑）。

---

## 为什么成本单价表三层定价（代码默认 → JSON → 程序化）？

**决策**：CostCalculator 三层定价——代码内置默认价（gpt-4o 等）→ classpath `agentflow-cost-pricings.json` 覆盖 → `override()` 程序化最高优先级。缺文件 warn 不崩，畸形条目跳过。

**为什么**：
- **R4 规避硬编码过时**：模型单价变化频繁，全硬编码会过时。代码默认价保底（永远有值），JSON 让运维改配置不改代码，程序化 `override()` 给测试和特殊场景最高优先级。
- **启动不失败契约**：缺文件 → warn + 用默认；畸形 JSON（非数字单价）→ 跳过该条 + warn；只有代码默认价全错才崩。生产环境改单价表不重启代码。
- **warn-once 去重**：未知模型 fallback 用 `synchronizedSet` 记已 warn 的 model，避免日志刷屏。

**为什么不选**：全配置化（无代码默认）会让缺文件时单价全 unknown，成本指标失真。代码默认价保证"开箱即有合理成本估算"。

---

## 为什么 ExecutionTraceRegistry 保留无清理（v1.1 加 TTL）？

**决策**：5 个 reviewer 指出 registry 无清理会 OOM，但保留现状记为 residual，v1.1 加 TTL eviction 或 Caffeine LRU。

**为什么**：
- **简单 remove 破坏核心用例**：若 BspEngine.execute finally 里 `registry.remove(workflowId)`，TraceController 在工作流完成后就查不到 trace——而"跑完查 trace"正是 TraceController 的核心用例。
- **正确解法需设计**：TTL eviction（trace 存活 N 分钟后自动清）或 Caffeine LRU（容量上限）是 v1.1 范围，plan 已声明"v1 不主动清理"。demo 规模 <100 workflow 内存占用 <1MB，不阻断 v1 演示。

**为什么不选**：盲目按 reviewer 建议 remove 会破坏用例——体现 review 修复要懂设计权衡，不是机械执行。5 票共识是"需生产前解决"，不是"现在阻断合并"。

---

## 为什么 WorkflowSubmissionGuard 用「提交时预防」而不是依赖 post-hoc 告警？

**决策**：`POST /api/workflows` 提交时做预防性守卫——节点数 / 预估成本任一超上界 → 422 拒绝（在 initWorkflow 前）。

**为什么**：
- **post-hoc 只能事后止损**：既有的 `budget_exceeded` 是跑完之后才报警，无界 VT + 烧成本已经发生。恶意/失控提交要在**起资源前**拦截。
- **拦截点选在鉴权/授权之后、持久化之前**：解析 YAML → 工具授权 → 守卫 → initWorkflow。守卫拒绝不产生执行记录（`listByCreatedBy` 无残留），避免脏数据。

**为什么不选**：只在 mock 记账里全局设阈值——那不是 per-workflow、也不拦提交。预防性是安全缺口 #2 的本质，post-hoc 只是补充。

## 为什么 R10 预算用 per-workflow 的 edge-triggered 累加器（而非全局 checkBudget）？

**决策**：新增 `WorkflowBudget` 累加器（`budget_tokens`/`budget_cost` 声明在 YAML `agentflow:` 段），`record()` 只在累计用量**首次跨过上界**返回 true。

**为什么**：
- **per-workflow 是语义正确**：全局 `checkBudget(totalCost())` 对所有工作流共享一个 cost counter，无法表达"这个工作流超了自己的预算"。
- **edge-triggered 是告警语义正确**：全局实现每次调用都自增（事件数=节点数）；per-workflow 应该记"跨过预算"这件事一次，而不是每个节点记一次。

**为什么不选**：把 per-workflow 状态塞进 `AgentFlowMetrics` 单例（会泄漏/难清理）；或共用全局阈值（无法单工作流控制）。独立 `WorkflowBudget` 值对象 + 线程安全 synchronized（超步内多节点并行记账，低频可忽略锁开销）。

## 为什么 v1.1 造第二个适配器 LangChain4j？（KTD-7 可移植性实证）

**决策**：用 LangChain4j 1.0.0 写 `LangChain4jAgentAdapter`，依赖面**仅 core + langchain4j、零 Spring AI**。

**为什么**：
- **KTD-7 的承诺要有实证不是口号**："所有框架调用收敛在适配器窄表面、换框架只动适配器"——用一个完全不依赖 Spring AI 的第二个适配器，从**构建级**证明（引擎/DSL/上游零改动），面试官问"换框架怎么办"时有代码可指。
- **零 Spring AI 依赖是刻意的**：若新 module 依赖 spring-ai 模块去复用 `OutputSchemaValidator`，会把 Spring AI 拉进 LangChain4j 的 classpath，可移植性证明就掺水。

**为什么不选**：在 spring-ai 模块里加一个 LangChain4j 适配器重逢（框架同仓，哈希不了隔离）；或用 AiServices 那套接口抽象（与 Spring 适配器的 ChatModel 窄表面不同构）。保持两适配器**同构窄表面**（AgentFunction → SpEL → ChatClient/ChatModel → usage → AgentOutput），对比才成立。

## 为什么把 SpelPromptResolver 下沉 core？

**决策**：`SpelPromptResolver` 从 spring-ai 适配器（包私有）提升为 core 的 `com.agentflow.prompt` public 类，+ `WorkflowContext` 重载收敛 channel 扁平化。

**为什么**：prompt 模板 SpEL 解析是 **DSL/Agent 域逻辑、与 LLM 框架无关**——两个适配器都要用。放 core = 单一真相源，不重复造轮子（否则 LangChain4j 适配器要么复制一份、要么反向依赖 spring-ai 模块把 Spring 拉进来）。KTD-2 安全约束（SimpleEvaluationContext 禁 T()/反射）随类移动原样保留，未因 public 化改变。

## 为什么 LangChain4j 工具执行循环要手写？

**决策**：裸 `ChatModel` 不像 Spring `ChatClient` 内置工具回调循环，适配器自建 `chat → toolExecutionRequest → DefaultToolExecutor 执行 → 回填 → 再 chat` 循环，≤5 轮。

**为什么**：LangChain4j 的内置闭环在 AiServices（接口抽象）那层，不在 ChatModel；为保持与 Spring 适配器同构的 ChatModel 窄表面，需自行驱动。有界 + usage 跨轮累加计费是必要约束。

**为什么不选**：切 AiServices 会引入与 Spring 适配器完全不同的调用范式，KTD-7"同形对比"就破了强度。
