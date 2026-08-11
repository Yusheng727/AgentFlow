# Code Review 发现与修复

> 记录 ce-code-review 多 agent 审查的高价值发现 + 修复思路。
> **面试用途**：「怎么保证代码质量」「有没有做 code review」「工程 rigor」类问题的核心证据。
> ce-code-review 是 compound-engineering 插件的审查 skill：派 10 个 persona reviewer 并行审，交叉验证后 merge/dedup。

---

## U5 Review 概览

- **审查方式**：ce-code-review 多 agent 流程，10 个 persona reviewer 并行
- **diff 规模**：17 文件，~1580 行
- **reviewer 清单**：correctness / testing / maintainability / project-standards / performance / api-contract / data-migration / reliability / adversarial / agent-native / learnings-researcher
- **关键机制**：cross-reviewer agreement——多个 reviewer 独立报告同一问题，confidence 提权。U5 最严重的两个 P0 被 **4 个 reviewer 独立确认**（adversarial + correctness + reliability + testing），可信度极高。

**面试讲法**：「我不只写测试，还跑了多 agent code review——10 个不同视角的 reviewer（correctness、security、adversarial 等）并行审我的 diff，交叉验证发现的问题。最严重的两个 P0 是 adversarial reviewer 用混沌工程思路构造的崩溃时序攻击场景，另外 3 个 reviewer 独立佐证了同一根因，我才确信这是真问题不是误报。」

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

## Review 方法论的面试价值

ce-code-review 的核心不是「找 bug」，是「用不同视角的 reviewer 交叉验证」。这套机制的价值：

1. **Persona 分工避免盲区**：correctness 看「逻辑对不对」，adversarial 看「怎么构造场景打破它」，reliability 看「依赖挂了怎么办」，data-migration 看「schema 对不对」。一个 reviewer 看不出的问题，另一个视角能发现。
2. **Cross-reviewer agreement 提权**：同一问题被 N 个独立 reviewer 报告，confidence 提升。U5 的两个 P0 被 4 个 reviewer 独立确认——如果只有 adversarial 报，可能是它构造的场景太极端；但 correctness/reliability/testing 都从各自视角独立得出同样结论，可信度就极高。
3. **Confidence 门控**：anchor 50 以下的 finding 被 suppress（除非 P0），避免低信号噪音淹没真问题。

**面试讲法**：「我的 code review 不是人肉 review，是用多 agent 编排做的——10 个 persona reviewer 并行，每个有专属视角（逻辑正确性、安全、混沌攻击、数据迁移等），独立报告后交叉验证。这模仿了真实团队里不同角色（SRE、DBA、安全工程师）的 review 视角，但能并行跑、且每个 reviewer 都是深度专家。」

---

## U7/U11/U12 Review 概览（2026-08-02）

- **审查方式**：ce-code-review 多 agent 流程，11 个 persona reviewer 并行
- **diff 规模**：34 文件，~2338 行（U7 可观测性 + U11 合同审核 Demo + U12 投资分析 Demo）
- **reviewer 清单**：correctness / testing / maintainability / project-standards / agent-native / learnings-researcher（always-on 6）+ security / performance / api-contract / reliability / adversarial（cross-cutting 5）
- **关键机制**：cross-reviewer agreement——同一问题被多个 reviewer 独立报告，confidence 提权。本次最严重的 P0（recoverAndExecute 不接 trace）被 **4 个 reviewer 独立确认**（adversarial + correctness + reliability + agent-native），与 U5 的两个 P0 同等可信度。

**面试讲法**：「U7 这次 review 又复现了 U5 的模式——最严重的 P0 被 4 个 reviewer 从不同视角独立确认：adversarial 构造了 checkpoint=SUCCESS 但 trace=FAILED 的状态分裂场景，correctness 从代码路径看出 recoverAndExecute 不引用 traceRegistry，reliability 指出 trace 终态与恢复路径不一致，agent-native 指出恢复工作流对 TraceController 不可见。四个视角独立得出同一根因，我才确信这是真问题——和 U5 的 ADV-1/ADV-2 一模一样的 cross-reviewer 交叉验证模式。」

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
**面试讲法**：「5 个 reviewer 都指出了 registry 无清理会 OOM。但我没盲改——简单 remove 会破坏 TraceController 的核心用例（工作流跑完查 trace）。正确解法是 TTL eviction，记为 v1.1。这体现 review 修复要懂设计权衡，不是机械执行 reviewer 建议。」

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

**面试讲法（被问「还有什么没做好」时）**：「我清楚知道哪些是 known risk：比如 PostgresCheckpointManager 还没有集成测试（只有 InMemory 覆盖），Recovery 的版本检查依赖 U8 的 workflow_definitions 表还没建。这些不是 bug，是 v1 范围外的 stretch，我记在 handoff 文档里，演示前补。」——主动暴露 known gap 比假装完美更可信。

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
- **ErrorClassifier 不识别 `dev.langchain4j.*` 异常（M2+REL-2，P2/manual）**：core 只判 `java.net.*`/`org.springframework.web.client.*`，LC4j 网络瞬时异常误判 Fatal 不重试；且 springframework 前缀属框架知识泄漏进 core。需圈定 LC4j 具体异常类型（防宽前缀把致命误判临时）。
- **继承/接口 `@Tool` 不注册（ADV-2，P2/manual）**：`collectTools` 只扫 `getDeclaredMethods()`。修法（`getMethods()`/层级遍历）可能丢非 public @Tool，需处理。
- **`mapException` 两适配器逐字重复（M1, P2/advisory）**：仅 2 消费者，暂不收敛（避免过早共享抽象）。

### 共享限制（非 LC4j 引入，两真实适配器共有）

- **预算未在真实 LLM 路径强制执行**：R10 `WorkflowBudget` 经 `AgentInput.budget()` 穿线，但只有 `MockAgentFunction` 消费；Spring 与 LangChain4j 两真实适配器都不读——YAML `budget_*` 在真实路径不生效。
- **`redactor` 默认 `Function.identity()`** 不脱敏；生产应注入 PromptRedactionFilter。

**面试讲法**：「这个 P1 bug 是两个 reviewer 独立抓到同一处（交叉确认提到 100）——我把有界循环的『终止态表达』拎出来讲：上限不能只防死循环，还要决定超限时以什么状态结束。这正好体现 review 交叉验证的价值。」
