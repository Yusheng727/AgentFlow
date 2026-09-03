# Code Review 发现与修复

> 记录 ce-code-review 多 agent 审查的高价值发现 + 修复思路。
> **用途**：「怎么保证代码质量」「有没有做 code review」「工程 rigor」的原始记录。
> ce-code-review 是 compound-engineering 插件的审查 skill：派 10 个 persona reviewer 并行审，交叉验证后 merge/dedup。

---

## U5 Review 概览

- **审查方式**：ce-code-review 多 agent 流程，10 个 persona reviewer 并行
- **diff 规模**：17 文件，~1580 行
- **reviewer 清单**：correctness / testing / maintainability / project-standards / performance / api-contract / data-migration / reliability / adversarial / agent-native / learnings-researcher
- **关键机制**：cross-reviewer agreement——多个 reviewer 独立报告同一问题，confidence 提权。U5 最严重的两个 P0 被 **4 个 reviewer 独立确认**（adversarial + correctness + reliability + testing），可信度极高。

**复盘**：「我不只写测试，还跑了多 agent code review——10 个不同视角的 reviewer（correctness、security、adversarial 等）并行审我的 diff，交叉验证发现的问题。最严重的两个 P0 是 adversarial reviewer 用混沌工程思路构造的崩溃时序攻击场景，另外 3 个 reviewer 独立佐证了同一根因，我才确信这是真问题不是误报。」

---

## 高价值发现（按严重度）

### P0-1: 崩溃层 COMPLETED 节点的 channel 输出丢失（ADV-1）

详见 [02-bugs-and-fixes.md Bug-4](./02-bugs-and-fixes.md#bug-4p0-崩溃层-completed-节点的-channel-输出丢失--adv-1)。

**为什么这个发现有价值**：这不是「代码写错了」，而是「恢复协议设计层面有缺陷」。plan 的 off-by-one 修复（查崩溃层本身）解决了节点重复执行，但漏了「跳过节点的输出如何恢复」。adversarial reviewer 用「crash between saveNodeOutput and saveBarrier」的时序攻击暴露了这个 gap——单靠 correctness reviewer 看 recover() 代码是看不出来的，需要构造跨组件的失败链。

---

### P0-2: timeout abort stray COMPLETED 防护未实现（ADV-2）

详见 [02-bugs-and-fixes.md Bug-5](./02-bugs-and-fixes.md#bug-5p0-timeout-abort-的-stray-completed-防护未实现--adv-2)。

**为什么这个发现有价值**：典型的「Javadoc 承诺了但代码没实现」——文档与代码不一致。testing reviewer 先发现（看 Javadoc 觉得该有防护，grep 代码发现没有），reliability reviewer 独立确认，adversarial 构造了完整攻击链，correctness 第四个佐证。这种问题单测覆盖不到（因为测的是「承诺的行为」），只有 review 能抓。

---

### P2: workflow_checkpoints 缺 UNIQUE 约束 + saveBarrier 无 ON CONFLICT

**发现**：reliability reviewer（REL-003）+ adversarial（ADV-3）+ correctness（F2）三方确认。
**问题**：`workflow_checkpoints` 表只有 `id BIGSERIAL PK`，没有 `UNIQUE(workflow_id, super_step)`；`saveBarrier` 是 plain INSERT 无 `ON CONFLICT`。重放/重试时同一 super-step 会插入重复行，`findLatestBarrier`（`ORDER BY super_step DESC LIMIT 1`）可能返回陈旧快照。
**对比**：`workflow_node_outputs` 表有 `UNIQUE(workflow_id, super_step, node_id)` + `ON CONFLICT DO UPDATE`，幂等设计完整。barrier 表漏了同样设计。
**修复**：V1 migration 加 `CONSTRAINT uq_workflow_checkpoint UNIQUE (workflow_id, super_step)`；saveBarrier SQL 加 `ON CONFLICT (workflow_id, super_step) DO NOTHING`（barrier 数据不可变，重放直接忽略）。
**为什么有价值**：这是 schema 设计的一致性问题——同一子系统两个表，一个幂等一个不幂等。data-migration reviewer 也独立指出了索引/约束的对称性。体现了「多视角审查能发现单一视角遗漏的一致性问题」。

---

## Review 方法论的价值

ce-code-review 的核心不是「找 bug」，是「用不同视角的 reviewer 交叉验证」。这套机制的价值：

1. **Persona 分工避免盲区**：correctness 看「逻辑对不对」，adversarial 看「怎么构造场景打破它」，reliability 看「依赖挂了怎么办」，data-migration 看「schema 对不对」。一个 reviewer 看不出的问题，另一个视角能发现。
2. **Cross-reviewer agreement 提权**：同一问题被 N 个独立 reviewer 报告，confidence 提升。U5 的两个 P0 被 4 个 reviewer 独立确认——如果只有 adversarial 报，可能是它构造的场景太极端；但 correctness/reliability/testing 都从各自视角独立得出同样结论，可信度就极高。
3. **Confidence 门控**：anchor 50 以下的 finding 被 suppress（除非 P0），避免低信号噪音淹没真问题。

**复盘**：「我的 code review 不是人肉 review，是用多 agent 编排做的——10 个 persona reviewer 并行，每个有专属视角（逻辑正确性、安全、混沌攻击、数据迁移等），独立报告后交叉验证。这模仿了真实团队里不同角色（SRE、DBA、安全工程师）的 review 视角，但能并行跑、且每个 reviewer 都是深度专家。」

---

## U7/U11/U12 Review 概览（2026-08-02）

- **审查方式**：ce-code-review 多 agent 流程，11 个 persona reviewer 并行
- **diff 规模**：34 文件，~2338 行（U7 可观测性 + U11 合同审核 Demo + U12 投资分析 Demo）
- **reviewer 清单**：correctness / testing / maintainability / project-standards / agent-native / learnings-researcher（always-on 6）+ security / performance / api-contract / reliability / adversarial（cross-cutting 5）
- **关键机制**：cross-reviewer agreement——同一问题被多个 reviewer 独立报告，confidence 提权。本次最严重的 P0（recoverAndExecute 不接 trace）被 **4 个 reviewer 独立确认**（adversarial + correctness + reliability + agent-native），与 U5 的两个 P0 同等可信度。

**复盘**：「U7 这次 review 又复现了 U5 的模式——最严重的 P0 被 4 个 reviewer 从不同视角独立确认：adversarial 构造了 checkpoint=SUCCESS 但 trace=FAILED 的状态分裂场景，correctness 从代码路径看出 recoverAndExecute 不引用 traceRegistry，reliability 指出 trace 终态与恢复路径不一致，agent-native 指出恢复工作流对 TraceController 不可见。四个视角独立得出同一根因，我才确信这是真问题——和 U5 的 ADV-1/ADV-2 一模一样的 cross-reviewer 交叉验证模式。」

### P0-1: TraceController 缺 ownership check → IDOR

**发现**：security（P0）+ api-contract（F4 P2）两票确认。
**问题**：新增 `GET /api/workflows/{id}/trace` 端点只接 ApiKeyAuthFilter 认证（401），未注入 WorkflowOwnershipChecker。workflowId 是 @PathVariable 用户可控，任意合法 Key 持有者可读他人 workflow 的完整 trace（含 LLM 输出摘要、token、拓扑、error）。同仓 WorkflowController 的 status/retry 已落地 ownership 防线（U14 R21），trace 端点遗漏。代码注释自认"留 v1.1"，但 plan 把防 IDOR 列为 R21 P0。
**修复**：TraceController 注入 WorkflowOwnershipChecker，getTrace 开头取 callerId 调 requireOwnership，非创建者 → 403。补 TraceControllerTest 6 测试（owner 200 / 非owner 403 / IDOR 隔离）。
**为什么有价值**：这是典型的"同一个 /api/workflows 基路径下两个端点，鉴权模型分裂"——security reviewer 从 authz bypass 视角抓到，api-contract reviewer 从契约一致性视角独立佐证。单测覆盖不到（测的是"调通"，不是"非owner该被拒"）。

### P0-2: recoverAndExecute 不接 trace → checkpoint 与 trace 状态分裂

**发现**：adversarial（P0）+ correctness（#2）+ reliability（REL-2）+ agent-native（AN-1）**4 票独立确认**。
**问题**：BspEngine.recoverAndExecute 硬编码 `null` 作为 trace 参数，从不调 `traceRegistry.register(workflowId)`。崩溃恢复成功后 checkpoint 翻 SUCCESS，但 registry 里仍是原崩溃 execute() 留下的 FAILED trace——TraceController 返回 FAILED 快照给一个实际成功的工作流。这是 U5 ADV-1/ADV-2 的同类问题：trace 没穿进恢复路径。
**修复**：recoverAndExecute 开头注册 trace，runSuperStep 传 trace，成功/失败路径 markCompleted(COMPLETED/FAILED)。
**为什么有价值**：和 U5 的两个 P0 是同一个"恢复路径遗漏"模式——adversarial reviewer 用"checkpoint vs trace 状态分裂"的时序攻击暴露，correctness 从代码路径独立确认，reliability 从终态一致性确认，agent-native 从可观测性覆盖确认。4 视角独立得出同一根因，可信度极高。这体现 ce-code-review 的核心价值：同一设计缺陷，不同 persona 从各自视角都能抓到。

### P2: BspEngine.execute catch 不覆盖 RuntimeException → trace 永留 RUNNING

**发现**：correctness（#1）+ adversarial（cascade 同源）2 票。
**问题**：execute() 的 `catch(WorkflowExecutionException)` 不覆盖 reducer.merge/applyOutput 抛的其他 RuntimeException（如 CUSTOM reducer 异常），trace 永留 RUNNING，TraceController 误报状态。
**修复**：finally 里兜底——trace 仍 RUNNING 则标 FAILED。

### P2: ExecutionTraceRegistry 无清理 → 内存泄漏

**发现**：security（R1）+ performance（perf-1）+ reliability（REL-1）+ correctness（residual）+ api-contract（residual）**5 票**——本次最高票数。
**问题**：ConcurrentHashMap 永不清理，BspEngine.execute 每次 register 一个新 trace，无 remove。生产长跑 OOM。
**处置**：**保留现状记为 residual**（非不修，是设计权衡）。理由：若 finally 里 remove，TraceController 在工作流完成后就查不到 trace（破坏核心用例）。正确解法是 TTL eviction 或 Caffeine LRU，属 v1.1 范围（plan 已声明"v1 不主动清理"）。5 票共识是"需生产前解决"，不是"现在阻断合并"。
**复盘**：「5 个 reviewer 都指出了 registry 无清理会 OOM。但我没盲改——简单 remove 会破坏 TraceController 的核心用例（工作流跑完查 trace）。正确解法是 TTL eviction，记为 v1.1。这体现 review 修复要懂设计权衡，不是机械执行 reviewer 建议。」

### P2: CostCalculator 畸形 JSON → ClassCastException 阻断启动

**发现**：reliability（REL-3）+ adversarial（residual）+ security（SEC-R3）3 票。
**问题**：`((Number) inVal).doubleValue()` 对 String/Array 抛 ClassCastException（非 IOException，不被 catch），畸形单价表阻断 Spring 启动，违背"启动不失败"契约。
**修复**：`instanceof Number` 检查 + warn 跳过非数字条目。

### P2: U12 下划线命名偏离项目约定

**发现**：project-standards（PS-1）+ maintainability（#3 stale comment）2 票。
**问题**：U12 用下划线 `company_finance` 绕开连字符正则 bug，但 U11 同分支已修正则支持连字符——下划线偏离无技术必要，且注释引用旧正则误导。U10/U11 都用连字符。
**修复**：6 个 node id 下划线 → 连字符，注释更新。

### 死代码删除（maintainability）

- `AgentInput.ofMock()` 零调用删除（grep 确认）
- `CostCalculator(String)` 1-arg 构造器零调用删除（所有调用用 no-arg + loadFromClasspath 链式）

---

## 已知未修的 Residual Risks（记录备查，非合并阻塞）

这些是 advisory 级，记在 `docs/handoff/u5-checkpoint-recovery.md`，演示前定即可：

- **ADV-4**: Recovery 无版本检查——崩溃后 workflow 定义变了（nodeId 同但语义不同），Recovery 仍按旧 nodeId 跳过。v1 静态 DAG 无此场景，U8 建 `workflow_definitions` 表后补。
- **ADV-5**: 并发 recovery 双执行——两个 JVM 同时 recover 同一 workflowId，无锁，会 LLM 双计费。v1 单实例不触发，分布式是 v1.1 stretch。
- **ADV-6**: InMemory vs Postgres 数值类型分歧——JSONB 往返 `Long 100L` → `Integer 100`，下游强类型断言会在 PG 路径 ClassCastException。需 PG 集成测试覆盖（当前 PG 零测试，见下）。
- **ADV-7**: `BspEngine.saveNodeOutput` 失败被 warn-only 吞掉——DB 瞬时故障 → 节点未持久化 → 下次 recovery 重跑 → LLM 重复计费。根因是 U2 代码（非 U5 diff），记为跨单元 follow-up。
- **REL-002**: `JdbcTemplate` 无 `setQueryTimeout`——DB 操作可无限阻塞 VT。生产前加 `jdbc.setQueryTimeout(5)`。
- **PG 零测试**: `PostgresCheckpointManager` 的 Semaphore/ON CONFLICT/JSONB/Flyway 全未测试（当前只用 InMemory 测）。需 H2 或 Testcontainers 补集成测试。这是 U5 最大的测试缺口。

**复盘（问「还有什么没做好」时）**：「我清楚知道哪些是 known risk：比如 PostgresCheckpointManager 还没有集成测试（只有 InMemory 覆盖），Recovery 的版本检查依赖 U8 的 workflow_definitions 表还没建。这些不是 bug，是 v1 范围外的 stretch，我记在 handoff 文档里，演示前补。」——主动暴露 known gap 比假装完美更可信。

---

## v1.1 回顾 · LangChain4jAgentAdapter ce-code-review（2026-08-11）

- **审查方式**：ce-code-review 多 agent 流程，10 persona reviewer 并行（correctness/security/adversarial 用 Opus，其余 Sonnet）
- **diff 规模**：10 文件，~772 行（新模块 agentflow-adapters/langchain4j + SpelPromptResolver 下沉 core）
- **意图**：v1.1 R5/KTD-7 的第二个框架适配器——用 LangChain4j 1.0.0、零 Spring AI 依赖，实证"框架调用收敛在适配器窄表面、换框架只动适配器"

### 本轮最高危发现（P1，adversarial + correctness 交叉确认 → confidence 100）

**工具循环静默成功 bug**：`chatWithTools` 的循环上限 `MAX_TOOL_ROUNDS-1`（第 4 轮）在"模型仍要工具"时直接返回 `aiMessage.text()`——工具专用轮 text 为 null → `execute()` 造出 `AgentOutput(null)` + `nodeTrace.succeed(null)`，一个 **broken 的 agent 循环被记为绿色 SUCCESS**：前 3 轮副作用（外部调用 + token 计费）已发生、无回滚无信号、第 4 轮 pending 工具被丢弃；下游 `${context.<nodeId>}` SpEL 再抛 Fatal（真正的"截断"隐在 warn 日志里）。

**修复**（`e86059e`，已提交验证）：轮数耗尽仍要工具 → **抛 `FatalException` 判节点失败**，而非静默 SUCCESS-with-null；`chatWithTools` 显式 `throws FatalException`，`execute()` 加 FatalException 分支原样抛（对齐 Spring 适配器 schema 路径处理）。配套测试 `toolLoopBoundedFailsNode` 改为断言 FatalException。

**教训**：有界循环不能只保证"终止"，还要保证**终止态表达**——cap 触发时要么 FAILED 要么带截断标志，绝不能伪装 SUCCESS。这类"坏状态伪装成功"是 agentic 编排里最隐蔽的缺陷。

### 其余已应用修复（同 commit `e86059e`）

- **REL-1（P2, reliability）**：工具循环顶缺 `Thread.interrupted()` 检查 → NodeExecutor 取消后仍可能多开最多 3 轮付费 LLM 调用。修复：每轮循环顶检查中断、提前停手（取消场景下结果已被 future.cancel 丢弃，仅防追加计费）。
- **api-contract + agent-native（P2）**：适配器算了 usage 但不记账 → LangChain4j 工作流在 Grafana token/成本面板空白。修复：注入可空 `AgentFlowMetrics`，成功路径 `recordTokens` 记 token/成本（Grafana 数据源）。
- **api-contract-01（P2）**：无 `OutputSchemaValidator` → `structuredOutput` 恒空，与 Spring 适配器行为**静默分歧**。修复：节点声明 `output_schema` 时 `log.warn` 明示不支持（防静默）。**后续（C2，`9ba7271`）**：`OutputSchemaValidator` 下沉 core + LC4j 接入 `validateWithRetry`，`structuredOutput` 不再恒空——补上 KTD-7 "相同 DSL 相同结果"对价，warn 已移除。
- **executeTool 硬化**：未知工具名 `jsonEscape`；异常返回通用 error（防御纵深）；`@Tool` 返回 null → 哨兵。

### 延后 / 需人工决策项（已入 residual 文档，未自动改）

> 完整明细见 `docs/residual-review-findings/langchain4j-adapter-review.md`（评审后新建的持久化位置）。

- **SEC-1（P2/manual）工具异常详情泄漏给模型**：LangChain4j `DefaultToolExecutor` **内部吞异常并回传原始消息**，不走适配器 catch——框架行为、等价 Spring 工具错误处理，但异常若含 SQL/路径/连接串会被模型在 content 复述外泄。真修需自定义 `ToolExecutor`（策略决策）。
- **ErrorClassifier 不识别 `dev.langchain4j.*` 异常（M2+REL-2，P2/manual）** → **已解决（`18978e8`）**：core 加 `composed()` 组合分类器，两适配器各自注册框架 transient 规则（LC4j `RetriableException`、Spring `org.springframework.web.client.*`）；core 移除 spring 前缀，框架知识全部移出 core——B2（LC4j 网络误判 Fatal 不重试）与 M2（框架知识泄漏进 core）同解。
- **继承/接口 `@Tool` 不注册（ADV-2，P2/manual）**：`collectTools` 只扫 `getDeclaredMethods()`。修法（`getMethods()`/层级遍历）可能丢非 public @Tool，需处理。
- **`mapException` 两适配器逐字重复（M1, P2/advisory）**：仅 2 消费者，原本暂不收敛（避免过早共享抽象）。→ **B2 review 已收敛**（`bf09771`）：P1 修复本就同时改两处 mapException 逻辑，顺势抽共享 `ErrorClassifier.toExecutionException()`（见下方 B2 review 章节）。

### 共享限制（非 LC4j 引入，两真实适配器共有）

- **预算未在真实 LLM 路径强制执行**：R10 `WorkflowBudget` 经 `AgentInput.budget()` 穿线，但只有 `MockAgentFunction` 消费；Spring 与 LangChain4j 两真实适配器都不读——YAML `budget_*` 在真实路径不生效。
- **`redactor` 默认 `Function.identity()`** 不脱敏；生产应注入 PromptRedactionFilter。

**复盘**：「这个 P1 bug 是两个 reviewer 独立抓到同一处（交叉确认提到 100）——我把有界循环的『终止态表达』拎出来讲：上限不能只防死循环，还要决定超限时以什么状态结束。这正好体现 review 交叉验证的价值。」

---

## v1.1 回顾 · B2 ErrorClassifier ce-code-review（2026-08-11）

审查 B2（`ErrorClassifier.composed` 组合分类器，LC4j 可重试识别 + 框架知识移出 core）。

### ★ P1 / conf 100：测试给假绿，真实路径 429 仍不重试（本批最重要）

**Situation**：B2 让 LC4j 的 transient 异常（429/5xx/超时）识别为可重试，测试也绿了。
**Bug**：LangChain4j 的 `ExceptionMapper` 构造 status marker 时**携带 cause**——真实形状是 `RateLimitException(cause=HttpException)`（marker 在**外层**，内层是 `HttpException`）。而适配器 `mapException` 先 unwrap 一层（`cause = e.getCause()`）再对 cause 判 `instanceof RetriableException` → 剥掉外层 marker，对内层 `HttpException` 判 → **HttpException 直接继承 LangChain4jException（非 Retriable，javap 证实）→ FatalException → 真实 429/5xx/超时仍不重试**。
**为何测试漏了**：新增测试用 **message-only 构造器**（`getCause()==null`），所以绿——是 **false-confidence 测试**（testing + reliability 两位评审各自 javap 字节码独立证实，交叉确认提到 conf 100）。
**修复**（`bf09771`）：`ErrorClassifier.toExecutionException(classifier, e)` **沿 cause 兜底分类**（判 e 与其 getCause()），两适配器把**原始异常 `e`**（未 unwrap）传给分类器；LC4j RateLimit 测试改用真实 cause 形状（TDD——修前会红）。
**教训**：「断言测试绿 ≠ 生产行为生效」是最隐蔽的缺陷形态，尤其当**异常是 marker-外层-包裹结构**时——unwrap 剥掉标记、测试又用无 cause 构造器，双重重叠出假绿。正确姿势：测试用**框架真实产出的形状**（含 cause 装载）。

### 其余应用（同 `bf09771`）

- **Spring 前缀 7 评审收敛**：`org.springframework.web.client.*` 规则移出 core 后**零测试**（7/9 评审独立指向同一缺口，删掉 `SPRING_CLASSIFIER` CI 仍绿 = 零回归伪证）→ 补 `ResourceAccessException`→Transient 回归测试钉住。
- **`composed()` 加固（P3）**：null-cause 兜底 + varargs 防御拷贝。
- **删 tombstone javadoc（P3）**（SpringAiAgentAdapter 残留的"已下沉 SpelPromptResolver"注释）。

### Deferred / Residual

- **NonRetriable 包装 IOException → 误判 transient**：理论上会过度重试，但 reliability 用字节码证实真实 LC4j status marker 只包 cause-less `HttpException`、不产生该形状 → 记残余（如需加固，给框架分类器加 veto 能力）。
- **agent-native**：`retryPolicy` 在 demo-api/starter 接线为 `null` —— B2 使 LC4j retry 有能力但当前无部署会触发（属独立 wiring 项）；retry 失败调用成本不计 metrics + 真实路径 budget 不读 = C1 范畴。

## v1.1 回顾 · C1 per-workflow 预算真实路径挂钩（2026-08-12）

**背景**：R10 的 `budget_tokens/budget_cost` 只在 mock 路径生效（`MockAgentFunction` 消费 `AgentInput.budget()`），两个真实适配器（`SpringAiAgentAdapter` / `LangChain4jAgentAdapter`）都不读——真实 LLM 调用下 YAML 预算形同虚设。

**关键设计取舍（防双计的坑）**：Spring 适配器 token/成本已由 `TokenCountingAdvisor` 记账，若在适配器里再调 `metrics.recordTokens` 会**双计** token/cost counter。故提炼 core `AgentFlowMetrics.recordBudget(...)` 助手：用 `costCalculator.cost` **纯算成本、不写 token/cost counter**，只做「累进 WorkflowBudget + 首次超限触发一次 `budget_exceeded`（edge-triggered）」。该助手成为三个消费方（mock + 两真实适配器）的**单一真相源**——既解 C1，又把 mock 里重复的 `budget.record→recordBudgetExceeded` 逻辑收敛。
- `LangChain4jAgentAdapter`：在既有 `metrics.recordTokens` 处并记 `recordBudget`（它无 advisor，`recordTokens` 本就是单一来源）。
- `SpringAiAgentAdapter`：新增 8-arg 构造注入 `AgentFlowMetrics`+`model`（6-arg 委托 null，构造点零改动），成功路径 `recordBudget`。

**复盘**：「我给 YAML 预算补上真实路径的闭环。难点是成本记账与现有 advisor 的**双计冲突**——Spring 路径 token/cost 已被 TokenCountingAdvisor 记过，直接再 record 会重复。解法是把『预算累加 + 超限事件』抽成 `recordBudget` 助手，成本用 costCalculator **纯算不落 counter**，与指标记账正交；这样 same-DSL-same-result（KTD-7）在两个框架真实路径都成立。」——这是「改动要绕开既有记账路径避免双计」的体现。

## v1.1 回顾 · B1 工具异常防泄漏到模型（2026-08-12）

**背景（SEC-1）**：LangChain4j 的 `DefaultToolExecutor.execute()` 在 @Tool 抛异常时，捕获 `InvocationTargetException` **直接返回原始异常消息字符串**（`areturn getCause().getMessage()`）、**不抛出**——@Tool 若抛含 DB 连接串/文件路径/内网地址的异常，细节会经工具结果回填进模型 `content`，再一路流到 workflow channel/渲染进 UI/日志。`redactor` 只作用 NodeTrace，管不到这条链。

**关键技术点（为什么不能包装 DefaultToolExecutor）**：用 `javap -c` 反编译确认——框架把异常转成**字符串返回**而非抛出，**外部包一层 catch 根本拦不到**。所以必须**自持 bean + method 自行反射 invoke**，在异常边界拦截：真实 cause 只 `log.warn` 进服务端，返回泛化 `{"error":"tool execution failed"}` 给模型。参数处理对齐框架语义（`@ToolMemoryId` 透传、String/primitive 强转、record 走 Jackson；void→"Success"、null→"null"、非 String→JSON）。`collectTools` 从 `DefaultToolExecutor(bean, m)` 换成 `SafeToolExecutor(bean, m)`。
- 覆盖：`SafeToolExecutor` 37%→93.3%（补 typed 参数渲染 / ToolMemoryId / 畸形 JSON 等分支测试）。

**复盘**：「安全 review 抓到一个真漏洞：LangChain4j 的默认工具执行器把 @Tool 抛的**原始异常消息直接当结果回给模型**，异常里的 DB 连接串/内网地址会被模型在回复里复述出来。我反编译确认它是『吞异常返回字符串』而非抛出，所以**包一层 catch 没用**，只能自己反射 invoke 在异常边界截——真实原因进服务端日志，给模型泛化错误。这展示『先反编译确认框架真实行为再动手，而不是靠猜』的严谨性。」——这是「防 prompt-injection 侧信道泄漏 + 反编译定边界」的强表达。

## v1.1 回顾 · B3 继承/接口 @Tool 不注册（2026-08-12）

**背景（ADV-2）**：`collectTools` 用 `getDeclaredMethods()` 只扫本类——工具方法放基类/接口时不会被注册，模型一调就是 unknown tool → 进截断/失败路径。

**陷阱（为什么不能简单换 getMethods()）**：`getMethods()` 只返回 **public** 方法，会**丢掉非 public @Tool**（本项目工具多为包私有，`SafeToolExecutor` 测试的工具也是）→ 改成 getMethods() 会更糟。故**必须全层级遍历**：`collectToolMethods(Class)` 逐层用 `getDeclaredMethods()` 扫（本类 + 基类 + 接口含父接口），保留非 public + 覆盖继承面；`signature()`（名+参数类型）作去重键，`LinkedHashMap` + `putIfAbsent` 让**类实现优先于接口抽象**、同一逻辑方法不重复注册。

**复盘**：「工具注册有个隐蔽坑：反射只用 getDeclaredMethods 会漏基类/接口上的 @Tool；但换 getMethods 又会丢包私有的 @Tool（工具常写包私有）。所以不能二选一，得**全层级遍历 + 签名去重**——既补上继承/接口面，又保住非 public 方法。这是个『看似一行改动、实则两个方向都会踩坑』的典型。」——「反射工具注册的完整边界」的强表达。

## v1.1 回顾 · C1+B1+B3 ce-code-review 复审与应用修复（2026-08-12，10 评审）

**背景**：对已合 main 的 C1+B1+B3 三个 commit（`e2a9664/2c6973f/8318d92`，BASE `4c59ac0`）做 10-persona 代码复审。多数评审确认 B1（SEC-1 泄漏已闭环）、C1 不双计、B3 层级遍历方向正确；抓到 7 条 P2 真实缺口（cross-reviewer 提升至 conf 100）。**已应用**（下述）+ 补 6 测试，全仓 verify 绿。

**已应用修复（`fix(review)`）**：
- **预算逐轮记账（correctness+reliability，conf 100）**：`chatWithTools` 每轮真实付费调用即 `metricAndBudget(...)`，schema 重试/工具多轮/**随后失败**的轮次都计入预算与 token——修复「schema last-wins 只记末次 → 重试花费被剔除（undercount ≤3x）」与「节点失败路径不记预算」两条（LC4j）。
- **coerce 归一 Jackson（maintainability+adversarial+testing，conf 100）**：删手写 int/long/double/boolean 分支，统一 `MAPPER.convertValue`——修复「字符串数字 `{"count":"3"}`→CCE、越界静默截断、数字 1→boolean 误判」。
- **重名 @Tool 去重（agent-native+adversarial，conf 100）**：`collectTools` 仅首次遇某 `spec.name()` 才 `specs.add`——B3 扩大枚举后同名重载/共享基类不再给模型重复 ToolSpecification 名。
- **中断标志恢复（reliability，conf 75）**：`SafeToolExecutor` root cause 为 `InterruptedException` 时 `Thread.currentThread().interrupt()`——避免 defeat REL-1 中断、已取消节点再开付费轮。
- 补测试：coerce 字符串数字 / 重写 dedup concrete-wins / 同名重载单 spec / 中断标志恢复 / schema 重试预算累加 / 失败路径预算计入。

**待人工决策（未自动改）**：
- **预算记账非阻断**（agent-native，P2 manual）→ **✅ 已解决（2026-08-12，拍板为「记账/告警非阻断」）**：`src/main` 无任何代码读 `WorkflowBudget.isExceeded()` 去 halt/skip——C1 文档原称「强制执行」与代码不符。决定：per-workflow budget 是**记账 + `budget_exceeded` 告警**，运行中不中止执行；硬性防护由提交前 `WorkflowSubmissionGuard`（422）承担。已统一 residual/CLAUDE.md 措辞为「记账/告警（非阻断）」，本文档对应历史 C1 节同步修正。
- **Spring 适配器预算路径**（正确性上同样存在 schema last-wins / 失败路径缺口，且 8-arg 构造无生产接线）：本批只修了 LC4j（工具循环所在地），Spring 因 advisors + 无生产消费者未动——若后续接真实 Spring 路径需补。
- **metrics==null 静默停用预算**（adversarial，conf 50 → residual）：两真实适配器把 recordBudget 挂 metrics 非空之后，手配/极简部署无 Micrometer bean 时预算被静默禁用；且仓库内 demo-api/starter **均不构造真实适配器**（无生产接线）——端到端预算强制在仓库内不可验证（同 B2「retryPolicy wired null」模式）。→ **✅ 档 1（2026-08-13）已补生产接线 + 真实端到端跑通**：demo-api `ApiConfig.nodeRegistry` 加 `agentflow.real.enabled` + env `DEEPSEEK_API_KEY` 条件装配真实 DeepSeek 适配器；`DeepSeekE2eIT` 用真实 key 跑通（DSL→BspEngine→真实适配器→DeepSeek，`metrics.totalCost()>0`）。

**复盘**：「审自己前一轮的代码，10 个 persona 抓到一个共性：C1 预算只在成功路径记**末次** token——schema 重试（3 次真实付费）只算最后一次，重试花的 2/3 成本被静默剔除，正是『测试绿 ≠ 生产生效』的又一形态。修复是把记账从『成功路径收尾』改成『每轮真实调用即记』，重试和失败轮自然都进预算。另外自写 coerce 想对齐 DefaultToolExecutor 却漏了字符串数字→CCE——教训是**尽量复用框架/Jackson 语义，别手写易碎的强转**。」——这是「跨评审互证抓分数账 bug + 复用而非重造」的强表达。

**复盘**：「我写过 update 打的 P1 是『测试全绿但功能没生效』——根因是框架异常是 marker-外层-包裹结构，适配器 unwrap 剥掉了可重试标记，而测试用了无 cause 的构造器给了假确认。两位评审各自对 jar 做 javap 独立证实同一处，交叉提到 conf 100。教训：测试必须用框架真实产出的形状。」——这是「怎么防止假绿测试」的强表达。

---

## v2 条件分支 ce-code-review（2026-08-14，10 评审）

**背景**：对 `feat/v2-conditional-branching` 全量 diff（U1–U8，26 文件 ~1330 行，BASE `acab553`）做 10-persona 代码审查。核心正常路径（条件路由/on_error/三终态）逻辑正确、Security 0 漏洞（SpEL 正确复用 KTD-2 hardened 上下文）；抓到 4 条 P1，全落在**生产/恢复路径**——再次印证「mock 绿 ≠ 生产生效」。

**已应用修复（`fix(review)`，`db1eb99`）**：
- **Postgres 路由决策持久化未实现（correctness+reliability+adversarial+project-standards，4 票，conf 100）**：`saveRoutingDecisions`/`findRoutingDecisions` 是 interface default no-op，只有 InMemory 实现——生产 Postgres 恢复时 `findRoutingDecisions` 恒空，条件分支工作流崩溃恢复会整片误 SKIPPED/复活。补 `PostgresCheckpointManager` 实现 + `V4__routing_decisions.sql`（累计列表，upsert latest-wins）。
- **`computeReachable` 恢复 BFS 把 on_error 已走的节点误当 fan-out（reliability，conf 80）**：节点类型判定只看「有无 when 边」，漏了「已走 on_error」这一维——失败转兜底的节点其正常出边应被剪枝，却按 fan-out 全走，恢复复活本应 SKIPPED 的正常下游。修复：`fanOut = !hasWhen && !onErrorTaken`。
- **`saveBarrier` 先于 `saveRoutingDecisions` 落盘（correctness+adversarial，2 票，conf 100）**：崩溃窗口内 barrier 已写、路由未写，恢复丢本层路由。修复：把 `saveBarrier` 移出 `applyBarrier`，在两个执行循环里统一 `saveRoutingDecisions → saveBarrier` 顺序。
- **`recoverAndExecute` 丢 on_error 三终态 + 不重建 onErrorActivated（5 票，最高共识）**：恢复路径硬编码 `STATUS_SUCCESS`、不标 `markCompletedViaOnError`、级联守卫从空集重建——`execute`/`recoverAndExecute` 双路径已开始漂移。修复：从 takenEdges 重建 `onErrorActivated`，终态对齐 execute（FALLBACK + markCompletedViaOnError）。
- **`PredicateEvaluator` 只 catch SpelEvaluationException（correctness，conf 100）**：`SpelParseException`（语法错误）与 `SpelEvaluationException`（求值错误）是兄弟类，语法错误泄漏为裸异常。修复：catch `ExpressionException` 超类。

**复盘**：「这轮审出最有价值的两个 P1 都是『测试全绿但生产不生效』：一是 Postgres 路由持久化是接口 default no-op、只有内存实现，生产崩溃恢复直接丢路由——这正是我项目里反复出现的那类『mock 绿 ≠ 部署生效』坑，这次 4 个 reviewer 独立命中同一处；二是 execute 和 recoverAndExecute 两条 BSP 主循环复制粘贴后开始漂移，恢复路径漏了 on_error 三终态，5 个 reviewer 都抓到——教训是**核心循环要抽共享方法，复制必然漂移**。还有一个纯逻辑 bug：恢复期 BFS 判定节点是 fan-out 还是路由只看『有无 when 边』，漏了『已走 on_error』这一维，把失败节点当成并行 fan-out、复活了本应跳过的下游——单测全过因为恢复+on_error 组合路径根本没覆盖到。」

**待人工/后续**：`execute`/`recoverAndExecute` 循环仍有 ~15 行重复（本次只修行为、未抽共享方法，见 maintainability P1）；`"from->to"` 边键字符串 4 处手写无单一真相源；`STATUS_FALLBACK` 指标 tag 无 metrics-registry 断言（当前 on_error 测试均 metrics=null）。

---

## v2 循环/回边 ce-code-review（2026-08-17，10 评审）

**背景**：对 `feat/v2-loop-backedge` 全量 diff（U1–U7，27 文件 ~2750 行，BASE `3fa3f2c`）做 10-persona 代码审查。执行路径（迭代轮次、双 active 集合、checkpoint round 维度）逻辑正确、测试覆盖充分（RecoveryLoopTest / BspEngineLoopTest / CheckpointManagerTest round 往返）；抓到的 **2 个 P1 全在恢复路径**——第三次印证「execute / recoverAndExecute 双路径漂移」历史模式。

**已应用修复（`fix(review)`：2 个 P1 + 补 2 个 P1 回归测试）**：
- **轮次转换检测不完整（correctness + reliability + testing 3 票，conf 100）**：mid-round 崩溃（回边命中但末层 barrier 未写）不触发轮次转换 → resume 丢 pending 迭代、SUCCESS 缺 finalize；且 final-barrier 后崩溃 else 分支 `crashLayerStep=0` 被从层 0 整体重跑（R11 破坏 + 双计费）。修复：轮次转换判据改按「该轮路由含回边」（`hasLoopDecision`）→ 无条件 round++ 进下一轮；无回边命中时保持 `crashLayerStep=size` → for 循环跑零层 → 收尾 SUCCESS。
- **backedge 目标非 layer-0 源时恢复复活上游（adversarial，conf 85）**：active 重建用 `computeReachable(layer-0 源)` BFS 而非回边目标，backedge 非源时复活已完成轮内上游 → 双计费。修复：轮次转换后 `active = backedgeTargets(上一轮决策)`（复刻 execute 的 nextActive）。
- 补 2 个 P1 回归测试：`midRoundCrashEntersNextRound`（mid-round 崩溃 finalize 最终执行）+ `finalBarrierCrashDoesNotReRun`（静态/收敛 DAG 末层后崩溃节点不重跑）。

**记录为已知残留/后续（P2/P3）**：
- `execute` / `recoverAndExecute` 外层轮次 while 循环整体重复（maintainability P1 conf 100，第三轮同款——v2 条件分支 review 已提「抽共享」未做，本次再次确认）→ 抽 `runRounds` 共享方法。
- `maxIterationsOf` 只取第一条 loop 边上限（adversarial + reliability，P2）——validator 不拒绝多回边，与「单回边 scope」不符，多回边静默只按首个上限约束。
- V5 migration 只进不退（drop 3 constraints，data-migration P2）——标注不可逆、非滚动部署；否则旧节点 ON CONFLICT 报错 → checkpoint 丢 → LLM 双计费。
- `DiagnosisService` 字符串字面量识别「迭代超限」（maintainability P2）+ 新分支零测试（testing P2）——建议 `FatalException` 子类或 workflowErrorKind 枚举替代 message 子串。
- `saveBarrier` / `saveRoutingDecisions` 未捕获（reliability P2，pre-existing，循环放大）——transient PG 故障中止为 raw RuntimeException 无 FAILED 状态，破坏 recovery stray 防护。
- Postgres round 维度 SQL（V5 + ON CONFLICT/SELECT round 过滤）零集成测试（multi-reviewer testing_gaps）——历史 PG 零测试缺口延续，需补 Failsafe IT。
- backedge 源节点缺退出边校验（correctness P3）——忘记退出边时运行时 Fatal 而非解析期拒绝。

**复盘**：「这轮 10-persona 审查抓到 2 个 P1，都在恢复路径，第三次印证『execute 和 recoverAndExecute 两条主循环复制粘贴必然漂移』——这次是轮次转换检测：mid-round 崩溃（回边命中但末层 barrier 未写）不触发转换，恢复报 SUCCESS 但退出节点从未执行；final-barrier 后被从层 0 整体重跑导致 LLM 双计费。修复方向是判断『该轮实质完成』要按该轮路由是否含回边，而不是看崩溃层号——恢复的 round 模型是 execute 的简化副本，边界情况处理不完整。测试全绿是因为这些崩溃窗口在 mock/恢复路径根本没覆盖到。」

---

## v1.1 Kafka U3 ce-code-review（2026-08-18，10 评审）

**背景**：对 `feat/v11-kafka-e2e`（U3）全量 diff（BASE `a086cb4`：KafkaDispatchE2eIT + auto.offset.reset 装配 + 自持 Jackson-2 ObjectMapper + demo-api 接 kafka-starter + ROADMAP/CLAUDE docs）做 10-persona 审查（correctness/testing/maintainability/project-standards/security/reliability/api-contract/adversarial + agent-native/learnings）。Kafka 传输链 + E2E 逻辑正确；抓到 **1 个 P1（3 独立 reviewer 置信 100）+ 2 个 P1（validator 验证后 1 个降 P2）+ 若干 P2/P3**。3 个 P1 均经独立 validator 验证确认。

**已应用修复（未 commit，plan「不 commit/push」约束；validator 确证后应用）**：
- **Kafka 模式 retry 静默失效（security+reliability+adversarial 3 票 conf 100，validator 确证 P1）**：`WorkflowController.retry` 派发前不改状态，FAILED 是终态 → 消费者「终态跳过」把 retry 派发的消息直接丢弃，`POST /retry` 返回 202/PENDING 但状态永远 FAILED；本地 dispatcher 直接 `service.run` 无此问题 → 双路径漂移。修复：`retry()` 在 dispatch 前置 `updateStatus(PENDING)`（本地 run() 立即置 RUNNING 语义不变）+ 回归测试 `retryResetsToPendingBeforeDispatch`（9-arg 构造注入 mock dispatcher 断言复位 + 派发参数）。
- **未 staged 任意 id 也执行（security P1 → validator 降 P2）**：`KafkaWorkflowConsumer.onMessage` findStatus 空时也 `run()`，绕过 REST 的 API-Key/ownership/submission-guard 执行任意 workflowId 并写 checkpoint（ledger 污染 + 成本放大；文档化单 JVM 信任边界内为防御加固，分布式未保护才 P1）。修复：findStatus 空 → log+丢弃不执行（合法 submit 总是先 initWorkflow）；补 `unstagedIdDropped` 单测。
- **默认 `auto.offset.reset=latest` 冷启动丢消息（reliability P1，validator 确证）**：新消费组无已提交 offset 时 latest 从分区末端起读，订阅前 produce 的提交静默丢失、工作流永 PENDING 无兜底；E2E 仅靠覆盖 earliest 通过。修复：默认改 `earliest`（消费者幂等终态跳过，重扫旧消息无双计费——对任务队列 strictly safer）+ javadoc 记录取舍。
- **E2E replay 固定 sleep(1500) 弱断言（testing+reliability+adversarial+correctness 多票）**：重放消息未在窗口内被消费时 counter 未变会假通过。修复：barrier 工作流模式——单分区 FIFO 下 barrier SUCCESS 即证明重放已被消费，断言 `counter == before+2`（barrier 恰好 2 节点），确定性替代固定 sleep。
- **机械 P3**：IT 类 javadoc 已陈旧（`@Import` vs 实际 `@EnableAutoConfiguration`）；`WorkflowExecutionMessage` javadoc 仍称 JsonSerializer+类型头（与实际 String+自持 mapper 不符）；`BspEngine(new DAGLayerer(),null,null,null,null)` → 1-arg 构造；ROADMAP 标注「本地未 commit/push 待评审」；E2E 场景 1 加 `dispatcher instanceof KafkaWorkflowDispatcher`（防误走本地回退仍全绿）。

**记录为已知残留/后续（P2/P3）**：
- consumer 幂等是 check-then-act 非原子（security P2）——并发重复投递可双跑双计费；需 `tryClaim`（条件 PENDING→RUNNING）闭合，延后（multi-node 计划范围外）。
- `auto.offset.reset` 默认 earliest 对「真新部署消费旧 topic 重扫历史」无碍（幂等跳过），但文档化的未来 multi-node 场景需复核（reliability residual）。
- 畸形消息被 ack-drop 且注释称「重放会重投」与实际（默认 enable.auto.commit）不符（reliability P2, pre-existing）——已仅修注释，DLT/手动 ack 属后续。
- `KafkaWorkflowDispatcher.dispatch` fire-and-forget：broker 挂 → submit 202/PENDING 但消息丢、无 FAILED 兜底（adversarial P2, pre-existing，U2 遗留，at-most-once 已文档化）。
- auto-config 手揉半套 `spring.kafka.*`（maintainability P2）——注入 `KafkaProperties` 收编更优，但改装配面有回归 E2E 风险，延后。
- KTD-E 计划要求的无 broker `KafkaCompatContextLoadTest` 未交付（project-standards P2, pre-existing，U2 缺口）——auto-config 已自持 mapper/offset 装配，补 context-load 冒烟是合理下一步。
- `agentflowKafkaObjectMapper` 是裸 `new ObjectMapper()` 无 JavaTimeModule——java.time inputs 序列化会失败（multi-reviewer residual，E2E 只用 Map.of() 未覆盖）。
- demo-api 加 kafka-starter 后即使 disabled，Boot KafkaAutoConfiguration 仍因 classpath 惰性装配额外 bean（correctness/adversarial P3）——懒连接不炸启动，默认路径测试验证过无碍。

**复盘**：「这轮 10-persona 审查最有价值的是两个『测试绿但生产不生效』：一是 Kafka 模式的 retry——`WorkflowController.retry` 派发消息但 FAILED 是终态，消费者幂等跳过把重试消息吞了，返回 202 但永远不重跑，3 个 reviewer 独立置信 100 命中同一处，而本地 dispatcher 直跑 run() 所以单测全绿；二是 `auto.offset.reset` 默认 latest 的新消费组冷启动会丢订阅前 produce 的提交——E2E 靠显式 earliest 覆盖才绿，生产默认路径没人测。两个都是『配置/装配层的生产默认 vs 测试显式覆盖』的落差，和项目里反复出现的 mock 绿 ≠ 部署生效是同一族教训。」

---

## Kafka U3 review 残留闭环（2026-08-18，feat/review-residual-r21）

**背景**：U3 ce-code-review 记录的 3 个残留（KTD-E 冒烟未交付 / tryClaim check-then-act / mapper 无 JavaTimeModule）本轮全部闭环 + 新增 R21 DB 授权。

**已交付**：
- **KTD-E `KafkaCompatContextLoadTest`（project-standards P2，U2 计划要求但此前未交付）**：无 broker @SpringBootTest 冒烟——context 能起、自持 `agentflowKafkaObjectMapper` bean 存在、wire 消息往返一致（null version / 中文 / java.time）、消费端 auto.offset 默认 earliest。与 E2E 分工：E2E 证真 broker 链路，本测试证无 broker 时装配与 serde 不炸。
- **`tryClaim` 原子幂等（security P2）**：`CheckpointManager` 接口新增 `default boolean tryClaim(workflowId)`（默认恒 true 保旧实现）；InMemory 用 `ConcurrentHashMap.compute`（PENDING→RUNNING 恰一次，10 线程恰一胜出测试）；Postgres 用条件 UPDATE `WHERE status='PENDING'`（影响行数判定，SQL 常量单一真相源 + H2 兼容表断言）。`KafkaWorkflowConsumer` 从 check-then-act 升级为「tryClaim 失败即跳过」——并发重复投递不再双跑双计费。
- **mapper 补 JavaTimeModule + 禁用 WRITE_DATES_AS_TIMESTAMPS**：裸 `new ObjectMapper()` 遇 java.time inputs 序列化失败；且默认会把 LocalDate 写成 `[2026,8,18]` 数组而非 ISO 字符串（读回 Object 变 List）。
- **R21 工具级授权 DB 表 + 管理 API**：见 CLAUDE.md / ROADMAP §3。

**记坑**：
- **H2 不支持 PG `ON CONFLICT DO NOTHING`**——JdbcToolGrantRepository 幂等 INSERT 改 `INSERT ... SELECT ... WHERE NOT EXISTS`（PG/H2 双兼容）。若遇 H2 报 BadSqlGrammar 优先怀疑 SQL 方言。
- **`queryForObject` 0 行抛 `EmptyResultDataAccess`**——`isGranted` 用 `queryForList` 判空而非 queryForObject。
- **`Set.copyOf` 打乱顺序**——`findGrantedTools` 保留 SQL ORDER BY 需 `LinkedHashSet`，否则管理 API 列表/测试顺序不确定。
- **Spring 宽松绑定 env 名**（live 起服踩坑）：`agentflow.api.api-keys` 的 env 是 **`AGENTFLOW_API_API_KEYS`**（非 `AGENTFLOW_API_KEYS`）；`agentflow.admin.api-keys` → `AGENTFLOW_ADMIN_API_KEYS`。用错名静默 401（filter 白名单没加进 key）。

**复盘**：「Kafka 消费者幂等从 check-then-act 升级成原子 claim——`tryClaim` 用条件 UPDATE 在 DB 层做 PENDING→RUNNING 独占转移，10 线程并发只有 1 个成功；R21 把工具级授权从配置硬编码升级成 DB 表 + 管理 API，admin key 门控变更，提交时强制即时生效——这是把安全从『静态配置』推进到『可运营的运行时授权』。」

---

## R22 扩列 + 审批中心 ce-code-review（2026-08-24，11 评审）

**背景**：`feat/r22-extend-approval-ui` 的 U6 ce-code-review 原计划门禁此前漏跑，本会话补跑——11 评审（correctness/security/adversarial/data-migration/api-contract/maintainability/project-standards/testing/agent-native/learnings-researcher/deployment-verify），base `8540a1c`。本批为「向已合 main 代码补做 review」的实例（review 迟于 merge），finding 全量落 `docs/residual-review-findings/r22-extend-approval-ui-review.md`，P1/P2 全量修复。

**本轮最高危发现（P1，adversarial conf 75）**：
- **聚合端点无 per-workflow 错误隔离**：`ApprovalCenterController.pending()` 裸 for 循环遍历全部工作流 `findPendingApprovals`，任一 wf 的审批行 `request_payload/context_snapshot` 解密抛异常（key 轮换 / 截断 `AESGCM:` / GCM auth 失败）→ 整个 `/api/approvals/pending` 500。admin `listByCreatedBy(null)` 遍历全部时，一个无关用户的坏行让 admin 看不见其它所有待批审批。
- **+ UI 掩盖**：`withMockFallback` 把该 500 当「backend down」fallback 到静态 mock 审批列表——用户点「批准」→ 打真实端点 400 `APPROVAL_NOT_FOUND`，真实 pending 不可见、500 被隐藏。
- **修复**：① 聚合端点 per-wf try/catch 跳过坏行（记 warn，返回部分结果）；② `withMockFallback` 新增 `preventServerErrorMock`——HTTP 5xx 不降级 mock，抛 `ApiError` 由 UI 显 error toast；③ 补测试。

**两个「系统性方案隐藏落点」P2（跨 reviewer 佐证）**：
- **`workflowName` 恒 null**——投影字段 `ApprovalCenterView.of(r)` 硬编码 null，外层循环已有 `wf.workflowName()` 却没用；mock 有值、真实恒 null，mock/真实漂移。**7 个 reviewer 独立命中**（correctness/api-contract/maintainability/project-standards/security/agent-native/learnings）——本批最一致 finding。修复：`of(r, wf.workflowName())`。
- **写路径加密无门禁测试**——定义存储 `save→toEncryptedJson` 的写加密分支只被 env 门控真 PG IT 覆盖（`ON CONFLICT` H2 跑不了），本地 `mvn verify` 无 PG 时写路径密文形态 0 覆盖（正是「测试绿 ≠ 生产生效」翻版）。修复：`VersionTest` 用 FakeJdbcTemplate + 真实 `AesGcmColumnEncryptor` 补 always-on 写密文断言。

**记坑**：
- **V8 迁移漏 `USING x::text`**——照抄 V7 先例却漏了显式 USING 子句，靠 PG 隐式 assignment cast（PG jsonb→text 有隐式 cast 大概率能跑，但「照抄已验模式」前提是逐字一致）。补 `USING decisions::text`。
- **`assertThatCode(...doesNotThrowAnyException())` 包 catch-all try = 恒绿**——starter 装配测试把唯一可抛语句吞进 try/catch 再断言不抛，即便 `fromEnvStrict()` 缺 key 抛错也照样绿，测不出声称验证的 fail-closed 构造。诚心修法：断言「mock DataSource 下确实抛连库错」（证明走真库路径）。
- **hashKeys/admin-key 三处逐字重复**——`ApprovalCenterController`/`ApprovalController`/`ToolGrantController` 各一份「CSV→split→sha256→LinkedHashSet」。抽 `AdminApiKeys` 单一真相源。

**复盘**：「我给已合 main 的功能也补跑了完整 code review——11 个 persona reviewer。最重的 P1 是 adversarial 用故障注入找到的：审批聚合端点遍历所有工作流时，一个损坏的审批行（比如 key 轮换后解不开）会让整个聚合 500，还剩过 mock fallback 掩盖成假数据。我加了 per-workflow 错误隔离 + 让 5xx 不降级 mock。另一个七人一致命中的是投影字段 workflowName 恒为 null——写代码时循环里明明有值却没用，这类『看着有字段其实没填』的契约陷阱，正是多视角 review 能扫出来的。」
