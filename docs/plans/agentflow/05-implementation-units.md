## Implementation Units

### Unit Priority 矩阵（v4.2 新增，落地 P0/P1/P2 分配）

Go/No-Go Gate 触发时，砍单只含 P1/P2 单元；**P0 不可砍**（U14 安全已从砍单清单移除）。

| Unit | 名称 | Priority | 说明 |
|:---|:---|:---:|:---|
| U0 | CI/CD 基础设施 | P1 | 工程化基础设施，非核心 |
| U1 | YAML DSL 解析 | P0 | 核心入口 |
| U2 | BSP 执行引擎 | P0 | 核心难点 |
| U3 | Agent 适配器层 | P0 | 核心集成 |
| U4 | 容错机制 | P0 | 核心可靠性 |
| U5 | 两级 Checkpoint | P0 | 核心持久化 + Recovery |
| U6 | 调试体验 Dry-run/Diagnosis | P1 | 可后置 |
| U7 | 可观测性 | P1 | 可后置 |
| U8 | Workflow 版本管理 | P2 | 价值薄，可后置 |
| U9 | Mock LLM 模式 | P1 | 控成本，建议保留 |
| U10 | 主 Demo 供应商风险 | P0 | 验证场景 |
| U11 | 辅 Demo 合同审核 | P1 | 验证场景 |
| U12 | 辅 Demo 投资分析 | P1 | 验证场景 |
| U13 | Starter+Docker+文档 | P0 | 交付 |
| U14 | API 鉴权+凭证管理 | P0 | R21/R22 安全，不可砍 |

### Phase 1: 核心编排引擎（Week 1-5）

#### U0. CI/CD 基础设施搭建（Week 1 Day 1）— v3 新增，解决 I4

- **Goal:** 搭建 GitHub Actions + SonarQube + Docker Compose 测试环境，确保每次提交触发单元测试、集成测试、代码质量检查
- **Requirements:** 工程化基础设施
- **Dependencies:** 无
- **Files:**
  - `.github/workflows/ci.yml`（GitHub Actions 工作流）
  - `.github/workflows/performance-benchmark.yml`（性能基准测试工作流，Week 1 和 Week 7 各触发一次）
  - `sonar-project.properties`（SonarQube 配置）
  - `docker-compose.test.yml`（测试环境：PostgreSQL + Redis，供集成测试使用）
- **Approach:**
  - **GitHub Actions CI 触发条件**：push to main、pull request、每天凌晨 2 点定时触发（性能回归检测）
  - **CI 步骤**：检出代码 → 启动 PostgreSQL + Redis（Testcontainers 或 docker-compose）→ Maven 编译 → 单元测试（JaCoCo 覆盖率 >80%）→ 集成测试 → SonarQube 静态分析 → 构建 Docker 镜像
  - **性能基准测试**：Week 1 建立基线，Week 7 对比回归（JMH 框架）
  - **代码质量门禁**：SonarQube 质量门禁（代码重复率 <3%、覆盖率 >80%、无 Major 级别漏洞）
- **Test scenarios:**
  - 提交代码 → GitHub Actions 自动触发 → 所有步骤通过 → 生成测试报告
  - 测试覆盖率低于 80% → CI 失败，阻止合并
  - 性能回归 >10% → CI 警告，标注性能对比报告链接
  - Docker 镜像构建成功 → 推送到 GitHub Container Registry
- **Verification:** CI/CD 流程端到端跑通，集成测试全部通过，Docker 镜像可正常启动

---

#### U1. YAML DSL 定义与解析（Week 1）

- **Goal:** 定义 AgentFlow DSL 语法规范，实现 YAML → POJO 解析 + 三层校验 + DAG 构建 + 最长路径分层
- **Requirements:** R1, R6（类型预留）, R14（版本字段）
- **Dependencies:** 无
- **Files:**
  - `agentflow-core/src/main/java/com/agentflow/dsl/WorkflowDefinition.java`
  - `agentflow-core/src/main/java/com/agentflow/dsl/NodeDefinition.java`
  - `agentflow-core/src/main/java/com/agentflow/dsl/EdgeDefinition.java`
  - `agentflow-core/src/main/java/com/agentflow/dsl/WorkflowDSLParser.java`
  - `agentflow-core/src/main/java/com/agentflow/dsl/SemanticValidator.java`
  - `agentflow-core/src/main/resources/agentflow-workflow-schema.json`（JSON Schema）
  - `agentflow-core/src/test/java/com/agentflow/dsl/WorkflowDSLParserTest.java`
- **Approach:** Jackson YAML 反序列化到 POJO；三层校验（Jackson 类型错误 → 语义层校验 → DAG 完整性）；生成 `agentflow-workflow-schema.json` 供 IDE 校验；DSL 支持 `agentflow.version: "1.0"` 顶层字段 + `channels` 段（channel 类型 + Reducer 策略）+ `nodes` 段（Agent + prompt + tools + timeout + retry 配置）+ `edges` 段（依赖关系）。
- **Patterns to follow:** Spring Boot `YamlPropertySourceLoader` 模式
- **Test scenarios:**
  - 合法串行 YAML 解析 → DAG 3 节点，边 A→B→C
  - 合法并行 YAML 解析（fork-join）→ DAG 含分支汇聚
  - 合法混合拓扑 YAML 解析（双层 fork-join） → DAG 5 层 super-step
  - 缺少必填字段 → WorkflowValidationException 带精确字段信息
  - 引用不存在节点 → 校验失败
  - 检测环路 → 校验失败
  - agentflow.version 缺失 → 默认 "1.0" 警告
- **Verification:** 单元测试全过；三个 Demo YAML 均能解析

**性能基准测试（v3 新增，解决 I3）：**

在 Week 1 Day 1，完成性能基准测试，建立性能基线：
1. **测试矩阵**：串行（1-10 节点）、并行（2-8 节点）、混合（5-20 节点 fork-join）、带状态传递 vs 无状态
2. **维度**：吞吐（workflows/sec）、延迟（P50/P95/P99）、内存占用（RSS/Heap）、并发连接数
3. **工具**：JMH（Java Microbenchmark Harness）
4. **输出**：Week 1 基线报告，Week 7 对比报告

**验证：** 性能测试通过，输出性能报告

#### U2. BSP 执行引擎核心（Week 2-3，核心难点）

- **Goal:** 实现 BSP 执行循环：super-step 分层、barrier 同步、channel 合并、Reducer 冲突处理；支持串行/并行/混合拓扑
- **Requirements:** R2
- **Dependencies:** U1（DAG 解析产出）
- **Files:**
  - `agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java`（超步循环）
  - `agentflow-core/src/main/java/com/agentflow/engine/SuperStep.java`（执行单元）
  - `agentflow-core/src/main/java/com/agentflow/engine/DAGraph.java`（DAG 数据结构 + 拓扑分层）
  - `agentflow-core/src/main/java/com/agentflow/engine/WorkflowContext.java`（channel values + versions）
  - `agentflow-core/src/main/java/com/agentflow/engine/ChannelReducer.java`（overwrite/concat/max/custom）
  - `agentflow-core/src/main/java/com/agentflow/engine/NodeExecutor.java`（单节点执行 + 超时/取消）
  - `agentflow-core/src/test/java/com/agentflow/engine/BspEngineTest.java`
- **Approach:** BSP 循环：`Plan（分层算法）→ Execute（Virtual Threads 并行执行当前 super-step 节点，互不可见，写入线程本地 buffer）→ Barrier（合并所有 buffer 到全局 context，应用 Reducer）→ Checkpoint（两级：节点级 + barrier 级）→ 检查下一层`。**关键细节**：
  - super-step 开始时为每个节点创建 **only-read context 快照**（`Map.copyOf`）防止互相污染
  - 同一 super-step 内节点用 **CompletableFuture.allOf() 统一等待**，最快也要等最慢的
  - 任何节点抛异常 → 异常隔离（不影响其他节点完成）→ barrier 后统一进入 ErrorHandler
  - `WorkflowContext.put()` 时做 Reducer 合并（默认 overwrite，支持 concat/max/custom）
  - 用 Java 21 Virtual Threads 而不是传统线程池（无大小限制，自动调度）
- **Patterns to follow:** LangGraph 的 BSP 执行循环 + channel versioning 思想
- **Test scenarios:**
  - 串行（A→B→C）：3 super-step 顺序执行，A 输出在 B input 可见
  - 并行（A→{B,C,D}→E）：super-step 1 三节点并行，三者完成才进 super-step 2；E 能看到三路输出
  - 混合（A→{B,C}→D→{E,F}→G）：5 super-step 正确分层
  - 同时写同一 channel：Reducer 按确定顺序合并
  - 节点 A 执行慢（500ms），B/C 各 10ms：barrier 要等 A 500ms 才进下一步
  - 并行节点 1 个抛异常：其他节点正常完成，barrier 后异常进入 ErrorHandler
  - context 快照不可变：节点 A 尝试修改 context 失败（抛出 UnsupportedOperationException）
- **Verification:** BSP 单元测试全过；3 种拓扑都能正确执行；并发度正确（3 节点并行跑）

#### U3. Agent 适配器层（Week 3-4，拆分：仅 Spring AI）

- **Goal:** 实现 `AgentFunction` 接口 + `SpringAiAgentAdapter`（**v1 仅做 Spring AI 适配器，LangChain4j 推迟到 v1.1**）+ **LLM 输出 schema 校验（v3 新增）**
- **Requirements:** R5, R7
- **Dependencies:** U2（BSP 引擎需 AgentFunction）
- **Files:**
  - `agentflow-core/src/main/java/com/agentflow/agent/AgentFunction.java`（接口，含异常合约+cancel+流式预留）
  - `agentflow-core/src/main/java/com/agentflow/agent/AgentInput.java`
  - `agentflow-core/src/main/java/com/agentflow/agent/AgentOutput.java`（**v3 新增 structuredOutput 字段**）
  - `agentflow-core/src/main/java/com/agentflow/agent/AgentExecutionException.java`（基类）+ `TransientException` + `FatalException`（子类）
  - `agentflow-core/src/main/java/com/agentflow/agent/NodeRegistry.java`
  - `agentflow-core/src/main/java/com/agentflow/observability/ExecutionTrace.java`（**v4.2 移自 U7**，首个消费者——LoggingAdvisor 在此写入、DiagnosisService 在此分析；U7 只做 tree-population/metrics 接入）
  - `agentflow-adapters/spring-ai/src/main/java/com/agentflow/adapters/springai/SpringAiAgentAdapter.java`
  - `agentflow-adapters/spring-ai/src/main/java/com/agentflow/adapters/springai/TokenCountingAdvisor.java`（自定义）
  - `agentflow-adapters/spring-ai/src/main/java/com/agentflow/adapters/springai/LoggingAdvisor.java`（**v4.2 新增**，自动写入 ExecutionTrace；PromptRedactionFilter 注入其之前做脱敏）
  - `agentflow-adapters/spring-ai/src/main/java/com/agentflow/adapters/springai/OutputSchemaValidator.java`（**v3 新增**）
  - `agentflow-adapters/spring-ai/src/test/java/com/agentflow/adapters/springai/SpringAiAgentAdapterTest.java`
  - `agentflow-adapters/spring-ai/src/test/java/com/agentflow/adapters/springai/OutputSchemaValidatorTest.java`（**v3 新增**）
- **Approach:** `AgentFunction` 接口（函数式）+ 异常合约（`throws AgentExecutionException`, 区分 Transient vs Fatal）+ cancel() 默认空实现 + supportsStreaming() 返回 false（v1）。`SpringAiAgentAdapter` 内部聚合 `ChatClient`（单例线程安全），**深度整合 Advisor Chain**：构造时注入 `TokenCountingAdvisor`（记录 token 消耗到 Micrometer）+ `LoggingAdvisor`（自动写入 ExecutionTrace）。执行时从 `AgentInput.context()` 用 SpEL 解析 prompt 模板中的 `${...}` 变量（用 `SimpleEvaluationContext` 禁止反射）。支持 YAML 节点级 tools 列表声明（从 Spring 容器中查找 `@Tool` 注解的 Bean，自动注册为 ToolCallback）。
  - **cancel() 真取消（v4.3 新增）**：`SpringAiAgentAdapter` 覆盖 `cancel()`，接到 `ChatClient`/ChatModel 底层 HTTP 调用的 `Future.cancel(true)` 或 RestClient abort——超时触发时真正中止在飞 LLM 调用、停止 token 流式与计费（呼应风险表 LLM 成本失控 55%）。`AgentFunction` 的 default noop 仅用于非 LLM/测试 Agent。

- **LLM 输出 Schema 校验（v3 新增，解决 I1）:**
  - `AgentOutput` 新增 `structuredOutput: Map<String, Object>` 字段（除 `content` 外）
  - YAML 节点配置支持 `output_schema` 字段（JSON Schema 格式）：
    ```yaml
    nodes:
      - id: financial-analysis
        agent: finance-agent
        output_schema:
          type: object
          properties:
            riskLevel: { type: string, enum: [LOW, MEDIUM, HIGH] }
            debtRatio: { type: number, minimum: 0, maximum: 1 }
          required: [riskLevel]
    ```
  - `OutputSchemaValidator` 工作流：① 尝试从 LLM 文本响应中提取 JSON（使用正则匹配 ````json ... ```` 或纯 JSON）；② 用 Jackson + JSON Schema 库（如 `com.networknt:json-schema-validator`）校验；③ 校验失败时构造"带反馈的重试 prompt"（附上期望的 schema 和错误信息），让模型重新生成；④ 最多重试 2 次，仍失败则触发 `FatalException`（避免无限循环）
  - **retry 预算组合（v4.2 新增）**：schema-retry 与 U4 RetryPolicy 共享 per-node LLM 调用总预算（max 3 次），schema-retry 嵌进 RetryPolicy 的一次 attempt 内，封顶 3×(1+2)=9x 失控；RetryPolicy 触发的重试复用同一计数器，不各自独立计 3 次。
  - SpEL 引用优先读取 `structuredOutput`（如果校验通过），降级到 `content`
  - **Benefits：** 下游 Agent 拿到的是结构化的 Map 数据，而不是不稳定的自然语言文本

- **Test scenarios:**
  - `SpringAiAgentAdapter` 调用：给定含 prompt 模板的 Input → 输出 AgentOutput 含 content
  - SpEL 解析：`${context.financeAnalysis.riskScore}` 从嵌套 map 正确提取
  - SpEL 安全性：`${T(java.lang.System).exit(0)}` 被禁止（抛异常）——定界符用 `${}`（与 demo 引用同一路径），不是 Spring 标准的 `#{}`
  - NodeRegistry 按 name 查找：注册 + lookup 正确
  - `@Tool` 注解方法自动注册：Agent 调用时模型能识别并调用
  - TransientException 触发重试 vs FatalException 直接进 ErrorHandler
  - **OutputSchemaValidator 校验成功**：LLM 返回 `{"riskLevel": "HIGH", "debtRatio": 0.75}` → `structuredOutput` 正确填充
  - **OutputSchemaValidator 校验失败 + 重试成功**：LLM 返回无 JSON → 重试 prompt 带 schema 提示 → 第二次成功
  - **OutputSchemaValidator 校验失败 2 次**：触发 FatalException，进入 ErrorHandler
- **Patterns to follow:** Spring AI 2.0 ChatClient + Advisor 模式
- **Verification:** 适配器测试全过；能用 mock ChatClient 执行 Agent

#### U4. 容错机制（Week 4）

- **Goal:** 三层容错链路：Timeout → Retry（区分 transient/fatal）→ ErrorHandler（补偿逻辑）
- **Requirements:** R4
- **Dependencies:** U2（BSP 引擎调用容错层）
- **Files:**
  - `agentflow-core/src/main/java/com/agentflow/engine/fault/RetryPolicy.java`（指数退避，区分 transient/fatal）
  - `agentflow-core/src/main/java/com/agentflow/engine/fault/TimeoutPolicy.java`（节点级 + 工作流级）
  - `agentflow-core/src/main/java/com/agentflow/engine/fault/ErrorHandler.java`（函数式接口）
  - `agentflow-core/src/main/java/com/agentflow/engine/fault/ErrorClassifier.java`（transient vs fatal 分类）
  - `agentflow-core/src/test/java/com/agentflow/engine/fault/FaultToleranceTest.java`
- **Approach:** 执行顺序：`Timeout（Future.get 超时 120s，可按节点覆盖）→ 抛出异常 → ErrorClassifier 判断 transient 还是 fatal（TransientException 含：网络超时、429限流；FatalException 含：400内容违规、参数错误）→ 仅 transient 触发 Retry（指数退避 1s→2s→4s，最多 3 次）→ 所有重试耗尽或 fatal 错误 → ErrorHandler（接收 state + exception，写入补偿数据到 context 如 `context.put("errorHandled", true)`）**注意：v1 ErrorHandler 只能修改 context，不能跳转路径**（跳转路径是 v2 动态路由的能力）
  - **失败传播语义（v4.2 新增）**：v1 无条件路由，非终态 super-step 中某节点重试耗尽或 fatal 后，引擎将工作流转为 **FAILED（abort）**，不继续推进下游 super-step——下游 SpEL 引用的 channel 可能因上游失败而缺失，继续会触发 `SpelEvaluationException` 并递归进 ErrorHandler。ErrorHandler 仅在 abort 前做 context 补偿（如 `errorHandled=true`）。状态机 `RUNNING --> FAILED` 因此可由节点级失败触发（不仅工作流级错误）。
- **Patterns to follow:** LangGraph 三层容错体系
- **Test scenarios:**
  - Agent 调用超时 120s → 触发 TimeoutException → 重试
  - Agent 抛 TransientException → 指数退避重试
  - Agent 抛 FatalException → 立即进 ErrorHandler
  - 重试 3 次全失败 → ErrorHandler 触发，context 写入 errorHandled=true
  - 工作流级总超时 → 取消所有活跃 CompletableFuture
  - **超时触发 cancel() 真中止 LLM 调用（v4.3 新增）**：mock ChatModel 验证超时后 `cancel()` 被调、底层 HTTP 请求被 abort、token 计费停止
- **Verification:** 全过；区分 transient/fatal 正确

#### U5. 两级 Checkpoint 持久化（Week 4-5）

- **Goal:** 节点级 checkpoint + super-step barrier checkpoint 双层；引擎重启后从最新 checkpoint 恢复，已完成的节点不重执行
- **Requirements:** R3
- **Dependencies:** U2（BSP 引擎调用）
- **Files:**
  - `agentflow-core/src/main/java/com/agentflow/engine/checkpoint/CheckpointManager.java`（接口）
  - `agentflow-core/src/main/java/com/agentflow/engine/checkpoint/NodeOutputStore.java`（节点级存储）
  - `agentflow-core/src/main/java/com/agentflow/engine/checkpoint/PostgresCheckpointManager.java`
  - `agentflow-core/src/main/java/com/agentflow/engine/checkpoint/InMemoryCheckpointManager.java`（v1 默认，开发测试用）
  - `agentflow-core/src/main/java/com/agentflow/engine/checkpoint/RecoveryProtocol.java`（恢复逻辑 v3 v3新增）
  - `agentflow-core/src/main/resources/db/migration/V1__checkpoint_schema.sql`
  - `agentflow-core/src/test/java/com/agentflow/engine/checkpoint/CheckpointManagerTest.java`
  - `agentflow-core/src/test/java/com/agentflow/engine/checkpoint/RecoveryProtocolTest.java`（v3 新增）
- **虚拟线程与数据库并发访问控制（v3 新增）：** Java 21 的虚拟线程可能让多个节点同时写库和 Redis。使用 HikariCP 连接池限制并发（最大连接数 20）；节点输出写入数据库后，通过 Redis Pub/Sub 通知引擎层。节点执行结果（包括异常）写入 `workflow_node_outputs` 表后，引擎才执行屏障检查和失败处理逻辑。
- **Recovery Protocol（v4 修复 off-by-one bug + 编号统一为 0-based）:**

  **编号约定（v4 统一）**：super-step 从 0 开始编号（`level 0` = 入度为 0 的节点）。barrier checkpoint 的 `super_step` 字段记录**已完成的 super-step 编号**（step=k 表示 super-step k 已 barrier 合并完成）。崩溃发生在 super-step N 执行中时，最新 barrier checkpoint 是 N-1，崩溃 super-step 是 N。

  ```java
  public class RecoveryProtocol {
      /**
       * 从数据库恢复工作流到可执行状态
       * @param workflowId 工作流 ID
       * @return ExecutionState 包含：已完成节点集合、channel 快照、下一个待执行 super-step
       */
      public ExecutionState recover(String workflowId) {
          // Step 1: 查找最新已完成的 barrier checkpoint
          // latest.superStep = 最近完成 barrier 的 super-step 编号（0-based）
          Checkpoint latest = checkpointRepository
              .findLatestByWorkflowId(workflowId);

          int nextSuperStep;          // 下一个待执行的 super-step
          Map<String, ChannelValue> channelSnapshot;

          if (latest != null) {
              // barrier 到 step=k → 下一个待执行是 step=k+1
              nextSuperStep = latest.getSuperStep() + 1;
              channelSnapshot = deserialize(latest.getChannelValues());
          } else {
              // 从未 barrier 过 → 从 step=0 开始
              nextSuperStep = 0;
              channelSnapshot = new HashMap<>();
          }

          // Step 2: 查询崩溃 super-step（= nextSuperStep，即未 barrier 的那一层）
          //         中已完成的节点级 checkpoint，复用其 output 避免重复调用 LLM
          // 🔑 v4 修复：查询的是 nextSuperStep 本身，不是 nextSuperStep-1
          List<NodeOutput> completedNodes = nodeOutputRepository
              .findByWorkflowIdAndSuperStepAndStatus(
                  workflowId,
                  nextSuperStep,            // 🔑 崩溃的那一层
                  NodeStatus.COMPLETED      // 🔑 严格过滤
              );

          // Step 3: 构建已完成节点集合（引擎跳过这些节点，仅执行未完成的）
          Set<NodeId> completedNodeIds = completedNodes.stream()
              .filter(n -> n.getOutput() != null)  // 二次保护：output 非空
              .map(NodeOutput::getNodeId)
              .collect(Collectors.toSet());

          return ExecutionState.builder()
              .nextSuperStep(nextSuperStep)
              .channelSnapshot(channelSnapshot)
              .completedNodeIds(completedNodeIds)   // 崩溃层中已完成的节点，跳过
              .workflowId(workflowId)
              .build();
      }
  }
  ```
  **关键约束：** `status = 'COMPLETED'` AND `output != null` 双重保护。**幂等保证：** `workflow_node_outputs` UNIQUE 约束 (workflow_id, super_step, node_id) 防重写入。**v4 修复要点：** 查询目标是 `nextSuperStep`（崩溃层本身），不是 `nextSuperStep - 1`（已 barrier 的层）——后者会导致崩溃层中已完成的节点被重复执行、LLM 重复计费，违背 R3 与第 338 行时序图承诺。**DAG 定义加载（v4.3 新增）：** Recovery 与 `POST /retry` 按 `(workflow_name, workflow_version)` 从 `workflow_definitions` 表取定义，不从 classpath 读——版本 bump 后旧实例仍按旧 DAG 恢复，兑现 R14。
- **Virtual Threads + PG 并发写入策略（v3 新增，v4.3 改为同步写）:**
  - **COMPLETED 节点输出同步写（v4.3）**：节点执行完成 → 立即同步 INSERT 到 `workflow_node_outputs`，不再走异步批量队列——R3"节点完成立即保存"字面成立，崩溃恢复无丢数据窗口（消除 pre-barrier flush 窗口）。
  - **Semaphore 限流（v4.3）**：用 `Semaphore(max=20)` 限制同时在飞的单 super-step 节点执行数 ≤ HikariCP 连接数（20），避免 VT 并发 50+ 耗尽连接池。v1 Demo 最大并行度 3，完全不构成瓶颈。
  - 批量写入使用 `INSERT ... ON CONFLICT (workflow_id, super_step, node_id) DO UPDATE SET status=EXCLUDED.status, output=EXCLUDED.output, tokens_consumed=EXCLUDED.tokens_consumed, completed_at=EXCLUDED.completed_at WHERE workflow_node_outputs.status <> 'COMPLETED'` 保证幂等（**v4.2 修正**：COMPLETED 终态不覆盖；FAILED/IN_PROGRESS 可升级为 COMPLETED，避免 retry 成功后 DO NOTHING 丢弃、形成重试/重复计费死循环）
  - **异步批量队列仅用于 telemetry/指标字段**（token 计数、duration 等 non-recovery 字段），可容忍丢失；recovery 关键路径（output/status）一律同步写。
  - **flush-then-barrier 不变式随同步写简化（v4.3）**：COMPLETED 已同步落盘，barrier merge 直接读 `workflow_node_outputs` 即可，无需 `drainTo` 排空队列；barrier checkpoint 仍按原语义写 `channel_values`。
- **Patterns to follow:** LangGraph 的 PostgresSaver + Spring Batch 的异步写入
- **Test scenarios:**
  - 工作流执行到 super-step 2 崩溃 → 重启后从 super-step 2 恢复 → 之前节点已完成的不重执行（LLM 不重复调用）
  - **节点 status=IN_PROGRESS 的记录被正确忽略（v3 新增）**：手动写入 status=IN_PROGRESS + output=null → 恢复时该节点不跳过
  - **节点 status=FAILED 的记录被正确重执行（v3 新增）**
  - 节点级 checkpoint 写入后、barrier 前崩溃 → 恢复时节点级数据存在，直接进入 barrier
  - JSONB 序列化往返无损：复杂嵌套结构 + BigDecimal + Instant
  - InMemoryCheckpointManager 开发测试模式：不需要 PG，重启丢失
  - 幂等写入同一 key：第二次静默跳过（不报错、不覆盖）
  - **VT+PG 并发写入压力测试（v3 新增，v4.3 更新）**：50 个 Virtual Thread 提交，`Semaphore(max=20)` 限流 → 全部 COMPLETED 同步落盘 → 无丢失、无连接池耗尽
- **Verification:** 全过；崩溃恢复可复现；LLM 不重复调用可验证（mock Agent 计数）

### Phase 2: 调试体验、可观测性、交付与安全（Week 5-7）

#### U6. 调试体验（Dry-run + Diagnosis）（Week 5-6）—— v2 修订新增

- **Goal:** 开发调试能力：Dry-run 模式 + Diagnosis 端点 + 结构化日志
- **Requirements:** R11, R12
- **Dependencies:** U2, U5（需要引擎和 checkpoint）
- **Files:**
  - `agentflow-core/src/main/java/com/agentflow/debug/DryRunEngine.java`（不发真实请求）
  - `agentflow-api/src/main/java/com/agentflow/api/DiagnosisController.java`
  - `agentflow-api/src/main/java/com/agentflow/api/DiagnosisService.java`
  - `agentflow-core/src/main/java/com/agentflow/observability/StructuredLogger.java`
  - `agentflow-core/src/test/java/com/agentflow/debug/DryRunTest.java`
- **Approach:** `DryRunEngine` 复用 `BspEngine` 拓扑逻辑，但所有 `AgentFunction` 替换为 **core 本地的 `DryRunMockAgentFunction`**（直接从 YAML 配置或默认值生成 output，**不引用 U9 的 `MockAgentFunction`**——消除 core→adapter 反向依赖；通过 DI 注入或 core 内置实现），走完整个 DAG 返回每步的预期输入输出 schema。`DiagnosisService` 分析 `ExecutionTrace` 树，识别 5 类常见问题：连续超时、token 异常消耗、SpEL 解析失败、channel 缺失、死循环（节点重复执行），输出诊断报告 + 修复建议。结构化日志接入 SLF4J + JSON format，每条 Agent 执行输出一行 JSON（workflowId, nodeId, duration, token, status）。
- **Test scenarios:**
  - Dry-run: 不发 LLM 请求 → 返回完整 DAG 执行拓扑 + 各步 input/output schema
  - Diagnosis: 工作流连续超时 3 次 → 诊断报告含 "Agent X 频繁超时建议延长 timeout 或检查 LLM 连通性"
  - 结构化日志输出格式符合 JSON schema
- **Verification:** Dry-run 全过；Diagnosis 端点能识别 5 类问题

#### U7. 可观测性（ExecutionTrace + Micrometer + 成本核算 + Grafana）（Week 6）

- **Goal:** 生成结构化 ExecutionTrace、暴露 Prometheus 指标、成本核算、Grafana Dashboard
- **Requirements:** R8, R9, R10（含成本核算）
- **Dependencies:** U2, U3
- **Files:**
  - `agentflow-core/src/main/java/com/agentflow/observability/AgentFlowMetrics.java`（ExecutionTrace.java 已移至 U3 创建——首个消费者）
  - `agentflow-adapters/spring-ai/src/main/java/com/agentflow/adapters/springai/TokenCountingAdvisor.java`（U3 已创建，这里接入）
  - `agentflow-api/src/main/java/com/agentflow/api/TraceController.java`
  - `agentflow-starter/src/main/resources/grafana/agentflow-dashboard.json`
  - `agentflow-core/src/test/java/com/agentflow/observability/MetricsTest.java`
- **Approach:** `ExecutionTrace` 树：根节点（workflow 级）+ 子节点（每个 Agent 执行）；每个节点记录 token 消耗、耗时、状态。Micrometer 指标：`agentflow.workflow.execution.count{status}`（Counter）、`agentflow.step.duration{node}`（Timer）、`agentflow.tokens.consumed{agent,model}`（Counter）、**`agentflow.workflow.cost.estimated{workflow}`**（Counter，基于 token × 模型单价）+ **`agentflow.workflow.cost.budget_exceeded{workflow}`**（Counter）。`TokenCountingAdvisor` 拦截每次 LLM 调用自动记录 token 并换算成本。Dashboard 模板：工作流执行趋势、各 Agent P50/P95/P99 延迟、token 消耗 Top10、每次工作流成本、失败率时间序列。
- **Test scenarios:**
  - 完整工作流执行后，TraceController 返回完整轨迹树
  - Micrometer 指标正确注册（Counter + Timer + 成本 Counter）
  - 每次工作流成本能被自动计算（基于 LLM token 单价）
  - Grafana JSON import 成功
- **Verification:** 端到端跑通

#### U8. Workflow 版本管理（Week 7）—— v2 修订新增

- **Goal:** YAML 支持 `agentflow.version` 字段；Checkpoint 关联 version；运行时版本变更警告但不影响已运行实例
- **Requirements:** R14
- **Dependencies:** U1, U5
- **Files:**
  - `agentflow-core/src/main/java/com/agentflow/version/WorkflowVersionManager.java`
  - `agentflow-core/src/main/java/com/agentflow/version/VersionConflictDetector.java`
  - `agentflow-core/src/main/java/com/agentflow/version/WorkflowDefinitionStore.java`（**v4.3 新增**，按 (name, version) 存取解析后的 DAG/YAML）
  - `agentflow-core/src/main/resources/db/migration/V3__workflow_definitions.sql`（**v4.3 新增**，`CREATE TABLE workflow_definitions (workflow_name TEXT, version TEXT, definition JSONB, created_at TIMESTAMP, PRIMARY KEY (workflow_name, version))`）
  - `agentflow-core/src/test/java/com/agentflow/version/VersionTest.java`
- **Approach:** DSL 解析时提取 `agentflow.version` 字段存入 `WorkflowDefinition`。Checkpoints 表增加 `workflow_version` 列。**`POST /workflows` 提交时把解析后的 DAG/YAML 写入 `workflow_definitions(workflow_name, version)` 表（v4.3 新增）**——恢复/retry 时按 checkpoint 的 `workflow_version` + `workflow_name` 从该表取定义，不再从 classpath 读，保证版本 bump 后旧实例仍按旧定义执行到结束（R14 字面成立）。恢复时检查：YAML 当前 version 与 checkpoint 记录 version 不一致 → 输出 WARN 日志但不阻断执行（已运行实例按旧 version 执行到结束）。`VersionConflictDetector` 提供 REST API `GET /workflows/{id}/version-check` 报告冲突。
- **Test scenarios:**
  - YAML version 缺失 → 默认 "1.0" + WARN
  - 运行中 YAML 升级 → 已运行实例按旧 version 完成，新实例用新 version
  - 版本 bump 后旧实例崩溃恢复 → 从 `workflow_definitions` 取到旧版本定义，按旧 DAG 执行（v4.3）
  - REST API 报告 version 冲突
- **Verification:** 全过

#### U9. Mock LLM 模式（Week 7）—— v2 修订新增

- **Goal:** `@EnableAgentFlow(mockLlm=true)` 自动用 YAML 配置的 mock_response 替代真实 LLM 调用
- **Requirements:** R13
- **Dependencies:** U3
- **Files:**
  - `agentflow-adapters/spring-ai/src/main/java/com/agentflow/adapters/mock/MockAgentFunction.java`
  - `agentflow-adapters/spring-ai/src/main/java/com/agentflow/adapters/mock/MockAdvisor.java`
  - `agentflow-starter/src/main/java/com/agentflow/starter/AgentFlowAutoConfiguration.java`（自动切换）
  - `agentflow-starter/src/test/java/com/agentflow/starter/MockModeTest.java`
- **Approach:** `MockAgentFunction` 从 YAML 节点配置的 `mock_response` 字段读取预设响应（支持 channel 配置不同 mock），直接返回 AgentOutput。`AgentFlowAutoConfiguration` 检测 `agentflow.mock.enabled=true` 时，自动替换所有 Agent 注册为 MockAgentFunction。
- **YAML mock 配置示例**：
  ```yaml
  nodes:
    - id: financial-analysis
      agent: finance-agent
      mock_response: |
        供应商财务风险：低
        资产负债率：35%（健康）
        现金流：稳定
  ```
- **Test scenarios:**
  - mock 模式开启 → 不发真实 LLM 请求，使用 mock_response
  - mock_response 缺失 → 抛出 `MissingMockResponseException`
  - mock 与真实模式切换：同一 YAML 在不同环境跑通
- **Verification:** 本地跑通，不发任何 LLM API 调用

#### U14. API 鉴权 + 凭证管理 + 提交 Controller（Week 5-6）— v4.1 新增，落地 R21/R22

- **Goal:** 落地 R21（API 鉴权防 IDOR）+ R22（LLM 凭证 + 敏感数据脱敏）+ 补齐缺失的 POST /workflows 提交入口 Controller
- **Requirements:** R21, R22
- **Dependencies:** U2, U5, U6, U7（端点和 trace 已存在）
- **Files:**
  - `agentflow-api/src/main/java/com/agentflow/api/WorkflowController.java`（**v4.1 补齐**：POST /workflows 提交入口 + GET /workflows/{id}/status 状态查询 + POST /workflows/{id}/retry 重试）
  - `agentflow-api/src/main/java/com/agentflow/api/security/ApiKeyAuthFilter.java`（API Key 鉴权过滤器）
  - `agentflow-api/src/main/java/com/agentflow/api/security/WorkflowOwnershipChecker.java`（per-workflow ownership 校验：workflow_id 关联创建者，仅创建者可查 trace/diagnosis）
  - `agentflow-api/src/main/java/com/agentflow/api/security/CallerToolAllowlist.java`（**v4.3 新增**，per-caller tool 授权：从 config/env 读 `Map<ApiKeyHash, Set<ToolName>>`，YAML 解析期校验节点引用的 `@Tool`）
  - `agentflow-core/src/main/java/com/agentflow/security/CredentialManager.java`（LLM API Key 注入：从环境变量/Spring configprops 读取，禁止入 application.yml）
  - `agentflow-core/src/main/java/com/agentflow/security/PromptRedactionFilter.java`（prompt/response 正则脱敏：API Key 模式、手机号、身份证）
  - `agentflow-core/src/main/resources/db/migration/V2__add_created_by.sql`（**v4.2 新增**，`ALTER TABLE workflow_executions ADD COLUMN created_by TEXT`）
  - `agentflow-api/src/test/java/com/agentflow/api/security/ApiKeyAuthFilterTest.java`
  - `agentflow-api/src/test/java/com/agentflow/api/security/WorkflowOwnershipCheckerTest.java`
- **Approach:**
  - **R21 鉴权**：`ApiKeyAuthFilter`（servlet 原生 `OncePerRequestFilter`，或 Spring Security `SecurityFilterChain`——**不是** WebFlux `WebFilter`，本栈为 servlet + Virtual Threads）拦截所有 `/api/**` 请求，校验 `X-API-Key` header；`WorkflowOwnershipChecker` 在 TraceController/DiagnosisController/WorkflowController 调用前校验 `workflow.createdBy == currentCaller`，不匹配返回 403。`workflow_executions` 表增 `created_by` 列（caller API Key hash）。
  - **R22 凭证**：`CredentialManager` 从 `SPRING_AI_OPENAI_API_KEY` 等环境变量读取，启动时校验非空、未硬编码于 yml/compose；`PromptRedactionFilter` 作为 Advisor 注入 LoggingAdvisor 之前，对 prompt/response 做正则脱敏后再写 ExecutionTrace/结构化日志。
  - **POST /workflows**（v4.2 真异步化）：`WorkflowController` 接收 YAML/inputs → 生成 workflow_id 并持久化 → 派发 `BspEngine.execute` 到 Virtual Thread executor → **立即**返回 `202 + Location: /api/workflows/{id}/status`（不阻塞在同步执行上，避免 30-120s+ 超时；状态走 status 端点）。
  - **工具级授权（v4.3 新增）**：`CallerToolAllowlist` 在 U1 `SemanticValidator` 阶段校验 YAML 节点声明的 `@Tool` 是否在调用方（按 `X-API-Key` hash）的 allowlist 内，不在则提交期返回 403。v1 allowlist 从 `agentflow.security.tool-allowlist` config/env 读取（硬编码映射，无管理 UI）；v1.1 升级为 `caller_tool_grants` 表 + 管理 API。
- **Test scenarios:**
  - 无 X-API-Key 请求 `/api/workflows` → 401
  - API Key A 创建 workflow → API Key B 请求该 workflow 的 `/trace` → 403（IDOR 防护）
  - API Key B 请求该 workflow 的 `/status` → 403（v4.2 补全，防他人读状态/结果）
  - API Key B 请求该 workflow 的 `/retry` → 403（v4.2 补全，防他人触发重执行 + LLM 成本）
  - API Key B 提交的 YAML 引用了仅 A 授权的 `@Tool`（如财务数据库查询）→ 403（v4.3 工具级授权）
  - API Key A 请求自己的 `/trace` → 200
  - application.yml 含明文 `api-key` → 启动失败（CredentialManager 检测硬编码）
  - Prompt 含 `sk-xxxxx` 模式 → ExecutionTrace 中显示为 `sk-***`（脱敏生效）
  - POST /workflows 提交合法 YAML → 202 + workflow_id + status 端点可查
- **Verification:** 鉴权/ownership/脱敏/提交入口端到端跑通；IDOR 场景返回 403；硬编码 Key 启动失败

### Phase 3: 验证 Demo + Starter 封装（Week 8-10）

#### U10. 主 Demo：供应商风险评估（并行→汇总）（Week 8）

- **Goal:** 实现主验证场景：3 个专家 Agent 并行分析 → Supervisor 汇总。精做 Demo
- **Requirements:** R15
- **Dependencies:** U2-U9 全套 + U14（R21 端点鉴权，demo 端点需鉴权后才算交付）
- **Files:**
  - `demo-supplier-risk/src/main/java/com/agentflow/demo/supplier/SupplierRiskApplication.java`
  - `demo-supplier-risk/src/main/java/com/agentflow/demo/supplier/agents/FinancialAnalysisAgent.java`
  - `demo-supplier-risk/src/main/java/com/agentflow/demo/supplier/agents/ComplianceCheckAgent.java`
  - `demo-supplier-risk/src/main/java/com/agentflow/demo/supplier/agents/ReputationAgent.java`
  - `demo-supplier-risk/src/main/java/com/agentflow/demo/supplier/agents/AggregateRatingAgent.java`
  - `demo-supplier-risk/src/main/resources/workflows/supplier-risk.yml`（含 `agentflow.version`）
  - `demo-supplier-risk/src/main/resources/mock-responses.yml`
  - `demo-supplier-risk/docker-compose.yml`
- **Approach:** 4 个 Spring AI `ChatClient`（不同 specialization），YAML 配置 4 节点（3 并行 + 1 汇总）。每个 Agent 的 output 写入独立 channel（financeAnalysis/complianceCheck/reputation）→ Supervisor 汇总时通过 SpEL 读三路输出。Demo 包含真实 mock 数据（财务数据、合规记录、行业评价）便于演示。`docker-compose.yml` 启动 AgentFlow + PG + Redis 全套。
- **Test scenarios:**
  - 完整流程跑通：3 并行 → 1 汇总 → 输出风险等级
  - 三路 agent 并行执行（ExecutionTrace 显示 startTime 接近）
  - 汇总 Agent 正确引用三路输出（SpEL 解析）
  - mock 模式下完整跑通
- **验收标准（v3 新增，解决 I6）：**
  - 输出 JSON 格式：`{riskLevel: "HIGH" | "MEDIUM" | "LOW", confidence: 0.0-1.0, evidence: List<String>, recommendation: String}`
  - 端到端执行时间 < 30 秒（mock 模式）
  - 并行验证：3 个并行 Agent 启动时间差 < 100ms（证明 BSP 并行执行）
  - Recovery 测试：中断后从 checkpoint 恢复，< 5 分钟内完成剩余步骤

#### U11. 辅 Demo 1：合同审核流水线（4 步链式串行）（Week 8-9）—— v2 修订场景差异化

- **Goal:** 实现辅 Demo：4 步串行链式工作流（合同解析 → 法律风险 → 合规建议 → 最终报告）。每步依赖上一步输出
- **Requirements:** R16
- **Dependencies:** U10
- **Files:**
  - `demo-contract-review/src/main/resources/workflows/contract-review.yml`
  - `demo-contract-review/src/main/resources/mock-responses.yml`
  - `demo-contract-review/src/main/java/com/agentflow/demo/contract/ContractReviewApplication.java`
- **Approach:** 4 个 Agent 串行：合同解析 → 法律风险识别 → 合规建议 → 最终报告。每步 Agent output 写入 context 后，下一步通过 SpEL `${previousStep.field}` 引用。YAML 配置 4 串行节点。演示引擎的**串行依赖链 + 上下文传递**能力，与主 Demo（并行）形成对比。
- **Test scenarios:**
  - 4 步串行执行：每步输出能正确传递给下一步
  - ExecutionTrace 展示 4 节点串行依赖拓扑
  - 任一步失败不影响前置已完成步骤的 checkpoint
- **验收标准（v3 新增，解决 I6）：**
  - 输出 JSON 格式：每步必须有 `{step: String, output: Object, nextStep: String}` 结构
  - 端到端执行时间 < 20 秒（mock 模式）
  - 串行验证：每步启动必须等待前一步完成（时间差 > 100ms）
  - Context 传递：验证 `${previousStep.field}` SpEL 表达式正确解析

#### U12. 辅 Demo 2：投资分析决策（双层 fork-join 混合）（Week 9）—— v2 修订场景差异化

- **Goal:** 实现辅 Demo：双层 fork-join 混合拓扑（2 并行 → 1 串行 → 2 并行 → 1 汇总）。证明引擎支持复杂拓扑
- **Requirements:** R17
- **Dependencies:** U11
- **Files:**
  - `demo-investment-analysis/src/main/resources/workflows/investment-analysis.yml`
  - `demo-investment-analysis/src/main/resources/mock-responses.yml`
  - `demo-investment-analysis/src/main/java/com/agentflow/demo/investment/InvestmentAnalysisApplication.java`
- **Approach:** 4 super-step 的复杂拓扑（6 节点）：① super-step 0：两个 Agent 并行收集基础信息（公司财报 + 市场数据）→ ② super-step 1：串行可行性分析 Agent → ③ super-step 2：两个 Agent 并行（风险评估 + 收益预测）→ ④ super-step 3：最终投资裁决 Agent 汇总。YAML 配置 6 节点（含 4 并行节点分两次执行）。演示引擎的**复杂拓扑组合 + super-step 分层**能力，这是 U10、U11 单拓扑覆盖不到的。
- **Test scenarios:**
  - 6 节点、4 super-step 正确分层执行（0-based：step 0/1/2/3）
  - 上层并行结果正确传递给下层串行
  - 最终汇总 Agent 正确引用前 3 步所有输出
  - ExecutionTrace 展示完整的 4 层结构
- **验收标准（v3 新增，解决 I6）：**
  - 输出 JSON 格式：`{riskLevel: String, confidence: Double, evidence: List<String>, recommendation: String}`
  - 端到端执行时间 < 40 秒（mock 模式，复杂拓扑）
  - 拓扑验证：4 个 super-step 必须正确分层，每层执行时间 < 8 秒
  - Channel 隔离：验证并行执行时 channel 不互相污染（每个 super-step 独立的 channel scope）

#### U13. Starter 封装 + Docker 交付 + 文档（Week 10）

- **Goal:** `agentflow-spring-boot-starter` 封装；Docker Compose 一键拉起；README 完善（架构图 + Quick Start + Tutorial + Production Guide）
- **Requirements:** R18, R19, R20
- **Dependencies:** U2, U3, U5, U14（引擎核心 + 安全；**v4.3 解耦**——不再依赖 U11/U12 demo，Starter/Docker/docs 对引擎核心交付，demo 是验证不是构建依赖）
- **Files:**
  - `agentflow-starter/src/main/java/com/agentflow/starter/AgentFlowAutoConfiguration.java`
  - `agentflow-starter/src/main/java/com/agentflow/starter/EnableAgentFlow.java`
  - `agentflow-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  - `docker-compose.yml`（根目录，含 mock profile / production profile）
  - `README.md`（Living README，包含架构图 + Quick Start + Tutorial + Production Guide + FAQ）
  - `docs/TROUBLESHOOTING.md`（Top10 常见问题解答）
  - `agentflow-starter/src/test/java/com/agentflow/starter/StarterIntegrationTest.java`
- **Approach:** `@EnableAgentFlow` 触发 AutoConfiguration：注册 `BspEngine`、`WorkflowDSLParser`、`NodeRegistry`、`PostgresCheckpointManager` 等核心 Bean。`agentflow.workflows.location=classpath:workflows/` 配置扫描 YAML。3 种接入模式（零基础设施 / 生产部署 / 分布式）文档化。Docker Compose 支持 profiles（`docker compose --profile mock up` 启动 mock 模式，`--profile production` 生产模式）。README 包含：架构图、快速开始（5 分钟跑通 mock demo）、三个 Demo 的 Tutorial、生产部署清单、Top10 Troubleshooting。
- **Test scenarios:**
  - 新建空 Spring Boot 项目 → 加 Starter 依赖 + @EnableAgentFlow → 自动注入成功
  - mock profile + **≥1 个 Demo（U10）跑通**（v4.3 解耦：3-Demo 全跑通降为 stretch，不阻塞 P0 交付）
  - production profile（含 PG + Redis）跑通
  - Docker Compose 一键启动
- **Verification:** 完整交付

← 返回 [`00-overview.md`](./00-overview.md)
