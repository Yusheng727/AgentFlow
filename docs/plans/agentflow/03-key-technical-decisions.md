## Key Technical Decisions

- **KTD-1. 执行模型选 BSP + 最长路径分层**。Spring Statemachine 于 2025.4 终止开源维护，不用。BSP 模型：并行节点在同一 super-step 内执行、执行完毕后统一切换到下一 super-step。
  - **Rationale:** 拓扑排序 + CompletableFuture 也能并行，但并行写入冲突需要手动加锁；**BSP 的 super-step barrier 天然避免竞态**。
  - **算法补充**：DAG → super-step 分组采用最长路径分层：`level[v] = max(level[u] for u in predecessors(v)) + 1`；`superStep_k = { v | level[v] = k }`。测试用例覆盖单节点、全并行链、双独立并行路径等边界。

- **KTD-2. DSL 表达式引擎选 SpEL + 类型系统预留**。SpEL 是 Spring 生态原生表达式引擎。v1 支持 SpEL 基础功能（属性导航、集合操作、条件），但不允许反射/方法调用（使用 `SimpleEvaluationContext` 禁用安全风险）。v1.1 引入完整的 channel 类型 schema（当前 context 是 `Map<String, Object>`，运行时校验）。
  - **Rationale:** 简单 `${}` 替换不够；SpEL 功能足够但**默认是图灵完备的**，用 `SimpleEvaluationContext` 禁用 `Class.forName()` 等危险方法防注入。

- **KTD-3. Checkpoint 采用"节点级 + super-step barrier"双层策略**：
  - **节点级 checkpoint**：每个 Agent 执行完毕，立即将 output 持久化到 `workflow_node_outputs` 表。避免 super-step 中途崩溃导致 LLM 重复调用（LLM 调用成本高、时间长）。
  - **super-step barrier checkpoint**：super-step 结束时，统一合并所有节点输出到 `channel_values`，写入主 checkpoint 表。保证状态一致性。
  - **恢复逻辑（v4 修正语义）**：barrier checkpoint 的 `super_step` 记录**已完成**的 super-step 编号（0-based）。崩溃发生在 super-step N 执行中时，最新 barrier 是 N-1，恢复时 `nextSuperStep = N`，查询 **`super_step = nextSuperStep`（崩溃层本身，不是 nextSuperStep-1）** 且 `status = 'COMPLETED' AND output IS NOT NULL` 的节点输出记录，复用时跳过重执行；`status = 'IN_PROGRESS'`/`'FAILED'`/`output IS NULL` 的节点必须重执行。完整伪代码见 U5 的 Recovery Protocol。**DAG 定义按 `workflow_version` 从 `workflow_definitions` 表取（v4.3 新增），不从 classpath 读**——保证版本 bump 后旧实例按旧定义恢复，兑现 R14。
  - **Rationale:** LangGraph 单节点 checkpoint 已被生产验证有效；完全 super-step 级 checkpoint 会因 LLM 成本高被用户诟病。

- **KTD-4. YAML 解析选 Jackson YAML + 三层校验 + JSON Schema**。三层校验：Jackson 类型映射（字段缺失/类型错误）→ 语义校验（所有边引用节点存在、节点 type 合法）→ DAG 完整性（无环、无孤立节点）。v1 提供 `agentflow-workflow-schema.json`，IDE 提供自动补全和实时校验。

- **KTD-5. 分布式锁选 Redisson（v1 简化为本地锁）**：v1 在单节点部署下使用 `ConcurrentHashMap<String, Lock>` 本地锁（v1 不依赖 Redis 做**分布式锁**；但 Redis 在 v1 仍作为结果缓存与 Pub/Sub 通知存在，见 R19/U5——"无 Redis 依赖"仅指锁层面，非完全无 Redis）。v1.1 升级到 Redisson 分布式锁，支持多节点。
  - **Rationale:** v1 Demo 是单节点 Docker Compose，分布式锁在单节点场景下是过度设计；Redis 依赖也会提高接入门槛。

- **KTD-6. AgentFunction 接口采用"函数式 + 异常合约 + 取消 + 流式预留"**。接口定义：
  ```java
  public interface AgentFunction {
      AgentOutput execute(AgentInput input) throws AgentExecutionException;
      default void cancel(AgentInput input) { /* noop */ }
      default boolean supportsStreaming() { return false; }
  }
  ```
  - `AgentExecutionException` 分层：`TransientException`（触发重试）vs `FatalException`（直接走 ErrorHandler）
  - 引擎持有 `List<CompletableFuture<AgentOutput>>` 活跃任务集合，支持统一取消（超时触发 barrier 时调用 `cancel()`）
  - **cancel() 覆盖约束（v4.3 新增）**：`default cancel() { noop }` 保留（对非 LLM/测试 Agent 友好），但 `SpringAiAgentAdapter` 必须覆盖它——接到 ChatModel 底层 HTTP 中断（`Future.cancel(true)` 或 RestClient abort），超时触发时真正中止在飞的 LLM 调用、停止 token 计费。生产 profile 启动时校验 LLM-backed Agent 的 cancel 非 noop。
  - Rationale：LangGraph 与 LangChain Runnable 深度绑定有教训；函数式接口对 Agent 实现无侵入，但必须有**明确的异常合约和取消语义**。

- **KTD-7. Spring AI Agent 适配器深度整合 Advisor Chain**。`SpringAiAgentAdapter` 接入 Spring AI 2.0 的 Advisor Chain：
  - `TokenCountingAdvisor`：自动记录 token 消耗到 Micrometer
  - `LoggingAdvisor`：自动写入 ExecutionTrace
  - `ContentFilteringAdvisor`：LLM 安全检查（可选）
  - 支持通过 YAML 配置节点级 tools：`@Tool` 注解的方法自动注册为 ToolCallback
  - **可移植性约束（v4.2 新增，应对 OQ-2 的 2.0→2.1 churn）**：所有 Spring AI API 调用必须收敛在 `SpringAiAgentAdapter` 内的窄表面（ChatClient + Advisor + ToolCallback），经 `AgentFunction` SPI 对外暴露；2.0→2.1 迁移只触及适配器，不扩散到引擎。Week 1 Day 1-2 加一个 Advisor Chain + `@Tool` 注册冒烟测试（针对 2.0 GA），跑通才开建 U3。
  - Rationale：Spring AI 2.0 的 Advisor Chain 是其核心扩展机制，绕开会丢失 token 追踪、可观测性等关键能力。

- **KTD-8. v1 范围主动削减，Kafka 推 v1.1**。Kafka 异步分发在 v1 用 `@Async + 数据库任务表` 轻量替代。v1 Demo 全部同步执行，10 周内优先保证核心引擎稳定。Kafka 是 v1.1 的增强型可选模块（`agentflow-kafka-starter`）。
  - **Rationale:** 同步执行足以支撑 Demo；Kafka 部署门槛高（3 节点起），影响用户快速试用；时间线压力大的情况下，主动削范围优于延 deadline。

- **KTD-9. v1 仅支持静态 DAG，条件分支留给 v2**。条件分支技术复杂度高（动态拓扑、状态回滚、补偿逻辑），10 周内无法保质完成。三个差异化验证场景用静态 DAG + Super-step 分层完全可满足。
  - **Rationale:** 时间约束（10 周）+ 风险控制。

← 返回 [`00-overview.md`](./00-overview.md)
