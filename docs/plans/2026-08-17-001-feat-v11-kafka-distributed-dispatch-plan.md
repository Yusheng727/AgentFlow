---
title: "v1.1 Kafka 异步分发（KTD-8）
date: 2026-08-17
type: feat
---

# v1.1 Kafka 异步分发（KTD-8）实现计划

**Date:** 2026-08-17
**Type:** feat
**Origin:** docs/plans/agentflow/03-key-technical-decisions.md（KTD-8：Kafka 异步任务分发，v1.1 增强型可选模块）+ 02-requirements.md（R18③ 分布式模式、R19 Kafka）+ docs/ROADMAP.md §3 v1.1 路线图
**Scope:** 用户已确认——Kafka 分发**整个工作流**（submit → Kafka topic → 消费者运行 BspEngine），节点级执行仍用同一引擎。**单 JVM 语义**（生产者+消费者同一应用，Kafka 作传输与解耦）；真跨进程/多节点分发**明确延后**（见 Scope Boundaries）。

> **自审修订（ce-doc-review 5 视角）**：① 多节点 read-after-write 竞态 → 本计划明确单 JVM 语义，跨节点延后，definition-not-found 改短暂重试；② retry 路径一并走 dispatcher（避免 submit 走 Kafka / retry 走本地的双路径漂移）；③ KTD-C 改属性门控 `agentflow.kafka.enabled`（对齐 `agentflow.real.enabled`，避免 classpath 即静默换执行路径）；④ 显式 serde bean 锁定 wire 格式；⑤ 消费幂等（终态跳过）+ 消费者错误→FAILED（防 orphan PENDING）+ topic 信任边界（ACL 是非本地部署前提）；⑥ U4 并入 U3（消除近重复），live 运行命令入文档。

> **执行状态（checkpoint 2026-08-18，未 commit/push——交接用）**：
> - ✅ **U1 完成**：`WorkflowDispatcher`/`WorkflowDispatchRequest`/`WorkflowExecutionService`/`LocalVirtualThreadDispatcher` + `WorkflowController` 重构（9-arg @Autowired 唯一，submit+retry 走 dispatcher）+ demo-api `ApiConfig` dispatcher bean（`agentflow.kafka.enabled` 门控）。`WorkflowExecutionServiceTest` 4 + `WorkflowControllerTest` 12 绿。
> - ✅ **U2 完成（单测）**：`agentflow-kafka-starter` 模块（pom + WorkflowExecutionMessage + KafkaWorkflowDispatcher + KafkaWorkflowConsumer + KafkaAgentFlowAutoConfiguration + imports）。**实现注**：spring-kafka 4.1 的 `JsonSerializer/JsonDeserializer` 已废弃（KTD-E 冒烟抓到）→ 改 **String 承载 JSON**（StringSerializer + 项目 ObjectMapper），避开废弃类。JaCoCo skip（wiring 型 starter，对齐 demo 模块）。`KafkaWorkflowDispatcherTest` + `KafkaWorkflowConsumerTest` 5 绿。
> - ⏳ **U3 待做（接手第一件事）**：`KafkaDispatchE2eIT`（Failsafe，真 Kafka `localhost:9092` 门控，`store.save` → dispatch → 轮询 SUCCESS；重放不重跑；双工作流）+ live 运行命令入 ROADMAP/CLAUDE.md。**注意**：E2E 需 `@SpringBootTest` 装配 BspEngine/NodeRegistry/CheckpointManager/WorkflowExecutionService/InMemoryWorkflowDefinitionStore + `agentflow.kafka.enabled=true` + `spring.kafka.bootstrap-servers`；用 `-am` 跑（新 api 类需 reactor 内依赖）。之后按目标走 `ce-code-review`（不 commit/push）。

---

## Summary

v1 的异步执行是 `WorkflowController` 里 `Executors.newVirtualThreadPerTaskExecutor()` + `executor.submit()` 直接跑 `engine.execute()`（R19 的"内存 @Async 轻量替代"）。本计划把它升级为 **Kafka 异步任务分发**（KTD-8）：

- `agentflow-api` 抽出共享执行逻辑 `WorkflowExecutionService`，并引入 `WorkflowDispatcher` 接口（默认本地 VT 调度 = 原行为，向后兼容）。
- 新模块 `agentflow-kafka-starter`：`KafkaWorkflowDispatcher`（producer）把提交消息发到 topic，`KafkaWorkflowConsumer`（消费者）复用 `WorkflowExecutionService` 从 `WorkflowDefinitionStore` 重取定义后执行。
- 端到端集成测试证明 `submit → Kafka → 执行 → SUCCESS` 的分布式派发闭环，另附 live 运行命令。

**价值定位（如实陈述）**：Kafka 分发把「提交」与「执行」通过消息队列解耦——REST 提交立即返回、执行在消费者线程/worker 完成、为基础设施层面的分布式传输做演示。**不宣称**吞吐提升或多节点横向扩展（消费组调优延后）。

## Problem Frame

目前在 demo-api/API 层，提交工作流后由**本进程的虚拟线程池**直接执行：提交与执行强耦合在同一 JVM、同一线程生命周期内。v1.1 的意图（KTD-8）是让「提交」与「执行」通过 Kafka **解耦**：

- 提交方只把任务推进消息队列（`WorkflowExecutionMessage`），执行方（消费者）拉取后跑 BSP 引擎；
- 提交 REST 请求立即返回，不因引擎执行占用提交线程；
- 消费者可作为独立执行入口，为未来跨进程 worker 留出接缝。

**范围边界（诚实陈述）**：本计划实现的是**单 JVM 内**的 Kafka 解耦（生产者+消费者在同一 Spring 应用）；真正把消费者放到独立进程/节点、依赖共享 Postgres store 的多节点部署**明确延后**（存在定义写入与消息消费之间的 read-after-write 时序问题，需事务性 outbox 或 freshness 重试，见 Risks）。

**非目标**：不做节点级分布式执行（每节点一条 Kafka 消息 + Redis 屏障同步）；不做消费组横向扩展调优。

## Requirements

- **R18③**（分布式模式，v1.1 路线图）：提交/执行经 Redis + Kafka 解耦。
- **R19**（Kafka 延迟 v1.1，v1 用内存 @Async 轻量替代）：Kafka 异步任务分发。
- **KTD-8**：Kafka 异步分发作为独立可选模块（`agentflow-kafka-starter`），不侵入 v1 无 Kafka 默认路径（**属性门控 opt-in**，非 classpath 即换）。
- 继承既有约束：`CheckpointManager` 仍是执行状态单一真相源（KTD-5）；`WorkflowDefinitionStore` 按 `(name, version)` 存定义（U8）；U14 X-API-Key 鉴权覆盖 REST 路径；PromptRedactionFilter 对 LLM prompt/trace 脱敏。

## Key Technical Decisions

- **KTD-A. 消息只带身份，定义由消费者重取**：`WorkflowExecutionMessage` 只含 `(workflowId, workflowName, version, inputs)`，不携带整个 WorkflowDefinition。消费者从 `WorkflowDefinitionStore.find(workflowName, version)` 重取。理由：消息小、复用 U8 单一真相源、"恢复按 name+version 取定义"的既有语义。**单 JVM 内**提交方 `recordWorkflowDefinition` 后才 dispatch，happens-before 成立；definition-not-found 视为**瞬时**（消费者短暂重试 ≤3 次 / 1s 退避后再判失败），不立即 Fatal——为未来跨节点留出容忍窗口。
- **KTD-B. 共享 `WorkflowExecutionService.run()`**：把「`updateStatus(RUNNING)` → `engine.execute(...)` → `updateStatus(SUCCESS/FAILED)`」收敛到一处，本地 VT 调度、Kafka 消费者、**retry 路径**复用同一语义，消除双路径漂移。`ChannelReducer` 确认无状态（当前实现为 stateless，重复 `new` 与共享单例等价，取共享实例）。
- **KTD-C. `WorkflowDispatcher` 接口在 agentflow-api，默认本地实现；Kafka 实现**属性门控 opt-in**：接口 + `LocalVirtualThreadDispatcher` 放 agentflow-api（默认）。`KafkaWorkflowDispatcher` 放 kafka-starter，其装配由 `@ConditionalOnProperty(agentflow.kafka.enabled)` 门控（对齐 demo-api 既有 `agentflow.real.enabled` 模式）——**不是**"starter 进 classpath 即无条件替换"，避免静默切换生产执行路径。未启用时回落本地，向后兼容。
- **KTD-D. JSON 序列化用显式 bean**：spring-kafka `JsonSerializer/JsonDeserializer` **默认自建 ObjectMapper，不会自动采用项目 SNAKE_CASE 配置**——必须显式注册配置了项目 ObjectMapper（对齐 DSL POJO 约定）的 serializer/deserializer bean；`WorkflowExecutionMessage` 字段 camelCase（workflowId/workflowName），wire 格式在 U2 serde 单测锁定。
- **KTD-E. 兼容性冒烟（KTD-7 纪律）**：spring-kafka 与 Boot 4.1/Spring 7/Jackson 3 的版本兼容是**未验证前提**——本项目被版本错配坑过（OQ-2）。U2 必须先做 library 兼容冒烟（固定 Boot 4.1 managed spring-kafka 版本 + 不依赖真实 broker 的 context-load 测试），再做功能。
- **KTD-F. 消费幂等 + 信任边界**：Kafka 是**第二条执行入口**，`@KafkaListener` at-least-once 投递下重放/重复消息会重跑引擎、重复计费。消费者必须幂等：`run()` 前查 checkpoint，workflow 已是终态则跳过。信任边界：topic 仅限受信生产者（ACL 限制 PRODUCE）是非本地部署前提；凭证走 `spring.kafka.*` + env 注入，不硬编码。

## Implementation Units

> U-IDs 为**计划局部**编号，不映射到 v1.0 的 U0–U14 时间线（与 v2 条件分支 plan 同约定）。

### U1. API 解耦：WorkflowExecutionService + WorkflowDispatcher 接口 + 默认本地调度

- **Goal:** 抽出共享执行逻辑，引入 dispatcher 接口，把 WorkflowController 的 **submit 与 retry 两条异步路径**都改为走注入的 dispatcher，默认等价原行为。
- **Requirements:** R19 语义保持（v1 无 Kafka 仍本地执行）；KTD-B
- **Dependencies:** 无
- **Files:**
  - `agentflow-api/src/main/java/com/agentflow/api/WorkflowDispatcher.java`（新建接口）
  - `agentflow-api/src/main/java/com/agentflow/api/WorkflowDispatchRequest.java`（新建 record：workflowId/workflowName/version/inputs）
  - `agentflow-api/src/main/java/com/agentflow/api/WorkflowExecutionService.java`（新建：run(...)，RUNNING→execute→SUCCESS/FAILED + 定义重取 + 瞬时重试）
  - `agentflow-api/src/main/java/com/agentflow/api/LocalVirtualThreadDispatcher.java`（新建，默认实现）
  - `agentflow-api/src/main/java/com/agentflow/api/WorkflowController.java`（改：注入 dispatcher；**新 dispatcher 构造是唯一 @Autowired**，旧 8-arg 变普通委托构造；submit 与 retry 都改 dispatch）
  - `agentflow-api/src/test/java/com/agentflow/api/WorkflowExecutionServiceTest.java`（新建）
  - `agentflow-api/src/test/java/com/agentflow/api/WorkflowControllerTest.java`（改：保持绿 + 补 dispatcher 注入路径 + retry 走 dispatcher）
- **Approach:** `WorkflowExecutionService` 持有 BspEngine/NodeRegistry/CheckpointManager/ChannelReducer(共享单例)/WorkflowDefinitionStore，`run(workflowId, name, version, inputs)`：`store.find` 缺失 → **短暂重试（≤3 次 / 1s 退避）** 后仍缺失抛 Fatal；`updateStatus(RUNNING)` → `engine.execute` → `updateStatus(SUCCESS)`，catch 时 `updateStatus(FAILED)` + rethrow。`WorkflowController` 的 submit 与 retry 都替换为 `dispatcher.dispatch(...)`；`initWorkflow`/`recordWorkflowDefinition` 仍在请求线程内同步做（提交时定义先入库，KTD-A）。**构造：新增含 dispatcher 的 @Autowired 构造（唯一）；旧 8-arg 改为普通委托构造（默认自建 LocalVirtualThreadDispatcher），7-arg 委托链不变——Spring 只允许一个 @Autowired 构造。**
- **Patterns to follow:** 现有 `WorkflowController` 构造兼容模式（7-arg 委托 8-arg）；`PostgresCheckpointManager` 的 run 语义；`WorkflowDefinitionStore.find` 签名。
- **Test scenarios:**
  1. `success`：run 后 checkpoint PENDING→RUNNING→SUCCESS；engine.execute 被调用（轻量 mock）。
  2. `failure`：engine.execute 抛异常 → checkpoint FAILED 且 rethrow。
  3. `definition-transient-not-found`：store 首查空、重试后命中 → 执行成功（验证瞬时重试）。
  4. `definition-permanent-not-found`：重试耗尽仍空 → run 抛 Fatal、不 updateStatus。
  5. Controller 连通：注入 mock dispatcher，submit 调用 `dispatch` 一次；`initWorkflow`/`recordWorkflowDefinition` 仍在请求线程。
  6. **retry 也走 dispatcher**：mock dispatcher 断言 retry 路径同样 `dispatch`。
  7. 向后兼容：旧 8-arg 构造仍可实例化（不传 dispatcher）→ 默认本地调度；**Spring context 装配：注入 dispatcher bean 时仅新构造被 @Autowired 选中，无歧义**（Spring 单 @Autowired 构造约束）。
- **Verification:** agentflow-api `mvn -pl agentflow-api test` 绿；WorkflowControllerTest 无回归（含 retry 路径改造）。

### U2. Kafka starter 模块：producer + consumer + AutoConfiguration（属性门控）

- **Goal:** 新模块 `agentflow-kafka-starter` 落地 Kafka 异步分发：`KafkaWorkflowDispatcher`（发消息）+ `KafkaWorkflowConsumer`（收消息跑 `WorkflowExecutionService.run`）+ 自动装配（`agentflow.kafka.enabled` 门控替换 dispatcher）+ 显式 serde bean。
- **Requirements:** R18③ R19 KTD-8 KTD-A/B/C/D/E/F
- **Dependencies:** U1
- **Files:**
  - `agentflow-kafka-starter/pom.xml`（新建；**spring-kafka 版本用 Boot 4.1 managed 依赖管理，U2 兼容冒烟先行**；依赖 core/api/spring-kafka/boot-autoconfigure）
  - `agentflow-kafka-starter/src/main/java/com/agentflow/kafka/WorkflowExecutionMessage.java`（新建 record）
  - `agentflow-kafka-starter/src/main/java/com/agentflow/kafka/KafkaWorkflowDispatcher.java`（新建，implements WorkflowDispatcher）
  - `agentflow-kafka-starter/src/main/java/com/agentflow/kafka/KafkaWorkflowConsumer.java`（新建，@KafkaListener + 幂等跳过）
  - `agentflow-kafka-starter/src/main/java/com/agentflow/kafka/KafkaAgentFlowAutoConfiguration.java`（新建，@EnableKafka + `@ConditionalOnProperty(agentflow.kafka.enabled)` + serde bean）
  - `agentflow-kafka-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（新建，注册自动配置）
  - `agentflow-kafka-starter/src/test/java/com/agentflow/kafka/KafkaCompatContextLoadTest.java`（新建，KTD-7 冒烟：起 context 验 serde/自动装配）
  - `agentflow-kafka-starter/src/test/java/com/agentflow/kafka/WorkflowExecutionMessageSerdeTest.java`（新建）
  - `agentflow-kafka-starter/src/test/java/com/agentflow/kafka/KafkaWorkflowDispatcherTest.java`（新建）
  - 根 `pom.xml`（改：注册 `<module>agentflow-kafka-starter</module>`）
- **Approach:** `WorkflowExecutionMessage(workflowId, workflowName, version, inputs)`；topic 常量 `agentflow.workflow.executions`。`KafkaWorkflowDispatcher.dispatch` → `kafkaTemplate.send(topic, workflowId, message)`。`KafkaWorkflowConsumer`（`@KafkaListener`）→ 先查 checkpoint 终态，非终态才 `executionService.run(...)`（KTD-F 幂等）；不可恢复消费错误 → `updateStatus(FAILED)` + 抛出（防 orphan PENDING）。AutoConfiguration `@ConditionalOnProperty(agentflow.kafka.enabled)` + `@ConditionalOnBean`（BspEngine/NodeRegistry/CheckpointManager/WorkflowExecutionService/WorkflowDefinitionStore）：注册 `KafkaWorkflowDispatcher`（覆盖默认）+ consumer factory + **显式配置项目 ObjectMapper 的 JsonSerializer/JsonDeserializer bean**（KTD-D）。**安全（KTD-F）**：信任边界写入注释——topic ACL 限制 PRODUCE 是非本地部署前提；传输 SASL_SSL 与 at-rest 加密标注为生产部署要求（v1.1 demo 环境本地 PLAINTEXT）；inputs 已最小化。凭证走 `spring.kafka.*` + env，不硬编码。
- **Patterns to follow:** `agentflow-starter` 的 AutoConfiguration + `META-INF/spring/...imports`；demo-api `ApiConfig` 的 `agentflow.real.enabled` 条件装配模式。
- **Test scenarios:**
  1. `compat-context-loads`（KTD-7 冒烟）：注入所需 bean 的 context 能起，serde bean 装配无版本冲突（不依赖真实 broker）。
  2. `message-serde-roundtrip`：`WorkflowExecutionMessage` 经配置的 serializer/deserializer 往返字段一致（含空 inputs/中文 inputs/**null version**）。
  3. `dispatcher-sends-correct-message`：mock `KafkaTemplate`，`dispatch` 断言 send(topic, key=workflowId, payload 字段正确)。
  4. `consumer-runs-service-when-not-terminal`：mock service + checkpoint 非终态 → `run` 被调用且参数透传。
  5. `consumer-skips-when-terminal`（幂等）：checkpoint 已是 SUCCESS → `run` 不被调用（重放防护）。
  6. `consumer-errors-mark-failed`：`run` 抛异常 → `updateStatus(FAILED)`（非终态时）。
  7. AutoConfiguration 门控：`agentflow.kafka.enabled=true` 时注册 Kafka dispatcher；缺 property/缺 bean 时不注册（不炸 context）。
- **Verification:** `mvn -pl agentflow-kafka-starter test` 绿（本地无 Kafka 也跑单测，纯 mock/context-load）；compat 冒烟先于功能单测。

### U3. Kafka 端到端集成测试 + live 运行命令

- **Goal:** 证明 `KafkaWorkflowDispatcher → Kafka topic → KafkaWorkflowConsumer → WorkflowExecutionService → SUCCESS` 全链路真实闭环（真 Kafka），并把 live 运行命令沉淀为文档。
- **Requirements:** R18③ R19 KTD-A/B/D/E/F（验收性证据：submit→Kafka→执行→SUCCESS）
- **Dependencies:** U1 U2
- **Files:**
  - `agentflow-kafka-starter/src/test/java/com/agentflow/kafka/KafkaDispatchE2eIT.java`（新建，Failsafe `*IT`）
  - 测试辅助：最小 @SpringBootTest context（Kafka dispatcher + consumer + InMemoryCheckpointManager + InMemoryWorkflowDefinitionStore + MockAgentFunction/NodeRegistry + `agentflow.kafka.enabled=true` + `spring.kafka.bootstrap-servers=localhost:9092`）
- **Approach:** 对齐 `PostgresCheckpointManagerIT`：`assumeTrue(kafkaReachable(localhost:9092))` 门控，无 Kafka 整类跳过。测试内：`store.save(name, version, def)`；`dispatcher.dispatch(request)` → 轮询 `checkpointManager.findStatus(workflowId)` 至终态（带超时）；断言 SUCCESS + 节点执行（channel/status 有值）。用**一条 2 节点串行 mock 工作流**（mock_response 填好；与 AE1 一致）。consumer 幂等断言：重复 dispatch 同一 workflowId 不重跑（终态跳过）。**live 运行命令**（写进 ROADMAP/CLAUDE.md）：起 `--profile distributed` Kafka 容器 → demo 或测试 app 设 `agentflow.kafka.enabled=true` + `spring.kafka.bootstrap-servers=localhost:9092` → curl POST 2 节点串行 workflow → 轮询 SUCCESS → `/actuator/prometheus` 出指标。
- **Patterns to follow:** `PostgresCheckpointManagerIT` 的 Failsafe + `assumeTrue` 门控；`RecoveryConditionalTest` 的引擎组装。
- **Test scenarios:**
  1. `dispatch-then-executes-to-success`：dispatch 后工作流终态 SUCCESS（真实 Kafka 传输 + 消费者执行）。
  2. `replay-does-not-re-run`（幂等）：终态后再次 dispatch 同一 workflowId，引擎不被重跑（checkpoint 跳过）。
  3. `two-workflows-sequentially`：连续 dispatch 2 个工作流，均到达终态（消费者多消息处理）。
- **Verification:** `mvn -pl agentflow-kafka-starter verify`（有 Kafka 容器时 IT 实跑绿；无 Kafka 跳过不红）；全仓 `mvn -s settings.xml -B -ntp verify` 绿 + JaCoCo 达标（kafka-starter 纯 wiring 类若拉低覆盖率，按 demo 模块先例跳过 JaCoCo 门禁）。

## Scope Boundaries

- **Deferred for later:** 真跨进程/多节点分发（消费者独立进程 + 共享 Postgres store + 事务性 outbox 保证 read-after-write）；节点级分布式执行（每节点 Kafka 消息 + Redis 屏障同步）；Redisson 分布式锁（KTD-5 升级）；Kafka 死信/重试拓扑、消费组横向扩展调优；topic ACL/SASL_SSL 生产强化。
- **Deferred to Follow-Up Work:** checkpoint 列级加密（R22）；工具级授权 DB 表（R21）。
- **Outside this plan:** 不改变单节点无 Kafka 的默认路径行为（向后兼容是硬约束）；不把 Kafka 作为吞吐/横向扩展卖点（价值 = 基础设施演示 + 提交/执行解耦）。

## Risks & Dependencies

- **依赖:** 真 Kafka 容器（本项目 `apache/kafka:3.9.2` 已就位，`--profile distributed`）；spring-kafka 依赖（国内网络走 settings.xml alimaven）；Boot 4.1 managed spring-kafka 版本（KTD-E 冒烟先行）。
- **风险①（版本兼容）:** spring-kafka 与 Jackson 3 / Spring 7 序列化组件树兼容未验证 → KTD-E context-load 冒烟先行，失败即停不深入。
- **风险②（测试环境）:** 端到端 IT 依赖真 Kafka，本地无容器跳过——接受（与 PG IT 同策略），CI/演示实跑。
- **风险③（向后兼容）:** WorkflowController 构造签名变更 → U1 明确唯一 @Autowired 构造 + 默认本地 dispatcher，旧测试零回归；Spring 单 @Autowired 约束是硬校验。
- **风险④（单 JVM 语义误读）:** 本计划只保证单 JVM 内正确；多节点 read-after-write 已延后，文档与 KTD-A 措辞须一致，防止误当跨节点方案。

## System-Wide Impact

- **api 模块:** WorkflowController 依赖 dispatcher（默认本地），无 Kafka 时行为不变；retry 路径改造为走 dispatcher（行为对齐 submit）。
- **新模块:** `agentflow-kafka-starter` 进根 pom module 列表，依赖 core+api+spring-kafka；JaCoCo 达 80%（纯 wiring 类可跳过，见 U3）。
- **安全:** Kafka 成为第二执行入口——消费幂等（防重放计费）、trust boundary 文档化（topic ACL 是非本地部署前提）、凭证 env 注入。
- **部署:** Kafka 配置走 `spring.kafka.*` 标准属性 + env 注入；`agentflow.kafka.enabled` 显式 opt-in。
- **文档:** ROADMAP §3 v1.1「分布式模式（引擎侧）」✅（含 live 运行命令）；CLAUDE.md 进度段补记；developer-notes 若遇坑同步。

## Open Questions

- （实现期）`WorkflowExecutionService` 放 agentflow-api 还是 core——定 agentflow-api（BspEngine/NodeRegistry 依赖在此层被 demo-api 使用）；若 starter 复用需要可移且不违反 KTD。
- （实现期）retry 现有实现是占位符（不真正重跑引擎）——本计划只做「对称走 dispatcher」，是否顺手接 U5 RecoveryProtocol 属后续决策。

## Acceptance Examples

- AE1: 提交一个 2 节点串行 mock 工作流，经 Kafka 派发后轮询到 SUCCESS（U3 IT）。
- AE2: 无 Kafka 环境跑 `mvn verify` 不红（U3 IT 跳过），有 Kafka 实跑绿。
- AE3: 未启用 `agentflow.kafka.enabled` 时，REST 提交仍本地执行并 SUCCESS（向后兼容，U1 覆盖）。
- AE4: 重放同一 workflowId 消息不重跑引擎、不重复计费（U2/U3 幂等覆盖）。
