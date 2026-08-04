# 后续任务 #9–#12：可运行 API Server + trace 分层 + 诊断往返 + 看板列表

> 覆盖 U7/U11/U12 合入后的四个 follow-up 修复（2026-08）。此前这套为"交付门面 + 可观测性 + 辅助 Demo"，验收（verify 绿 + build 绿）后暴露四个真实工程缺口：真实 API 路径跑不通、trace 拓扑被 UI 伪造、诊断真路径绑空、看板永远 mock。逐个修复，全部 `mvn verify` 9 模块绿 + UI `npm run build` 绿。

---

## 0. 背景：为什么会有这四个遗留问题（Situation）

远程合入的 U7/U11/U12 把引擎核心（BSP/可观测/Demo）做完了并经 review 闭环，但**只有库、没有可运行的门面**：
- 仓库无任何可启动的 REST server wiring → `SpringAiAgentAdapter`/`WorkflowController` 从未真正被装配起来跑通
- `NodeRegistry` 是 Map-based、未知名直接抛错，mock 模式"任意 agent 名→MockAgentFunction"的约定实现不了
- `ExecutionTrace.Snapshot` 无超级步层级信息 → UI `PipelineView.groupIntoSteps` 只能用"末节点=汇总"启发式**伪造**拓扑
- `NodeTrace` 只有 `@JsonProperty` getter、无 `@JsonCreator` → `POST /api/diagnosis` 提交真实 trace 时 Jackson 绑不上节点 → 诊断真路径落 mock
- 后端没有 `GET /api/workflows` 列表端点 → 看板永远 404→mock

---

## 1. #9 可运行 REST API Server（root cause：wiring 缺失）+ 修 3 个 Boot 4.1 坑（Task/Action/Result）

**S**：UI 的"真实 API 优先"（KTD-1）在仓库里从没真正跑通过——`/api` 没有可启动的应用，`TraceController` 永远 404，`NodeRegistry` 空导致提交执行失败（未知名抛 `IllegalStateException`）。
**T**：给出一条可零成本（mock）验证真实 REST 链路的可启动 server，并用 HTTP 测试 + 真 boot 锁定。
**A**：
1. **`NodeRegistry` 加 fallback resolver**：`resolve()` 未知名先委托 `fallback`（null 才抛错）——把"mock 任意名复用"从不可行的 Map 语义里解出来，`new NodeRegistry(name -> mock)` 即可。U2 的 seam 第一次真正落地。
2. **新模块 `demo-api`**：`AgentFlowApiApplication` + `ApiConfig` 显式接 Bean（`BspEngine` 带 `ExecutionTraceRegistry`、`NodeRegistry` mock fallback、`ApiKeyAuthFilter` 含 UI 默认 demo key）+ `application.yml`（port 8080）。
3. **踩掉 3 个 Spring Boot 4.1 的包重构坑**（这条最有面试价值）：
   - `spring-boot-test-autoconfigure` 4.1 只含 jdbc/json/package——**MockMvc/web 测试自动配置被移出核心 test 模块**；`TestRestTemplate` 也从 `spring-boot-test` 消失 → `@SpringBootTest(RANDOM_PORT)+java.net.http.HttpClient`（Java 21 内置）或 MockMvc standalone 更稳。
   - spring-ai OpenAI 自动配置要求"至少一个 credential"，否则 `OpenAiAudioSpeechModel` 启动即炸 → mock-only 应用在**注解级 `@SpringBootApplication(exclude=...)`** 排除 6 个 OpenAi 自动配置。
   - JDBC `DataSourceAutoConfiguration` 在 4.1 挪到 `org.springframework.boot.jdbc.autoconfigure`（`spring-boot-jdbc` 模块），不在 demo-api 编译类路径——注解引用编译不过，改**字符串 `spring.autoconfigure.exclude`**（runtime 生效，编译不需要类路径）。
4. 多模块运行：`spring-boot:run` 从 `~/.m2` 解析依赖（非 reactor），需先 `mvn install` 依赖模块——否则 `ClassNotFoundException: ExecutionTraceRegistry`。

**R**：MockMvc standalone 3/3（提交 202→状态 SUCCESS→trace 200 非空含 `durationMs`→401/403→400）；真实 `spring-boot:run` 起服（Tomcat:8080，18s），curl 全链路跑通，trace 返回完整 `NodeTrace` JSON。顺带修前置 bug：提交无 nodes 的 YAML 从 NPE→500 改为 400。

---

## 2. #10 ExecutionTrace 带超级步层号（根因：Snapshot 无层级）

**S**：`ExecutionTrace.Snapshot` 是扁平 `List<NodeTrace>`，无 super-step 信息；U11 串行（4 层各 1 节点）与 U12 双层 fork-join（2/1/2/1）会被 `groupIntoSteps` 的"末节点=汇总"启发式**错扁平成"并行 N + 汇总 1"**——把串行/并行标签印错了（adversarial + agent-native 双票）。
**T**：让后端把真实 BSP 分层交给 UI，UI 不再伪造。
**A**：
- `NodeTrace` 加 `step`（volatile，默认 0）+ `@JsonProperty`；`ExecutionTrace.recordStep(nodeId, step)`；`BspEngine.execute`/`recoverAndExecute` 每超步骤后用 `SuperStep.index()` 记该 step 的 `nodeIds()`。
- 前端 `PipelineView.groupIntoSteps`：后端带 `step` → 按 step 逐段切分（同层并行节点连续追加、step 单调不减）；mock/旧数据无 step → 回退启发式（仅示意）。
- 测试：串行 A→B→C step=0/1/2；fork-join A/B=0 并行、C=1 汇总。
**R**：任意拓扑（串行/双层 fork-join）按引擎真实分层渲染，UI 显示与引擎一致。

---

## 3. #11 /diagnosis 真实 trace 反序列化（根因：NodeTrace 只读模型）

**S**：`POST /api/diagnosis` 提交的 `Snapshot.nodes` 是 `List<NodeTrace>`，但 NodeTrace 是 plain class（private 字段 + record 风格 getter、无 `@JsonCreator`），Jackson 无法还原 → 真实诊断路径要么 400、要么节点绑空 → `DiagnosisService` 读到空/默认字段 → 假阴性"无异常发现"（adversarial 遗留）。
**T**：让 NodeTrace 可 JSON 反序列化还原结构字段。
**A**：
- 加 `@JsonCreator` 静态工厂：还原 nodeId/agent/status/prompt/completion/outputSummary/error/step（诊断 5 规则基于 status/error/token/nodeId）；`startNanos=0` → `duration()/durationMs()` 返回 0，诊断不依赖耗时精度（`DiagnosisService.findTimeoutFailures` 虽调 `duration()` 但仅用于展示 "0ms"，不抛）。
- `totalTokens()`/`durationMs()` 标 `@JsonProperty(Access.READ_ONLY)`——**关键**：否则 Jackson 严格模式（`FAIL_ON_UNKNOWN_PROPERTIES` 默认 true）把只读派生 getter 当"未知属性"报错。READ_ONLY 序列化输出、反序列化优雅忽略。
**R**：core round-trip + demo-api 诊断 HTTP round-trip（GET trace→POST /diagnosis→200，totalNodes=1）。诊断真路径从"落 mock"变为真实分析。

---

## 4. #12 看板列表端点 + 状态大小写归一 + 指标名防漂移

**S**：后端无 `GET /api/workflows` 列表端点 → `listWorkflows()` 永远 404→mock，看板永不显示真实数据；且后端 `WorkflowStatus` 是 UPPER 枚举（PENDING/RUNNING/SUCCESS/FAILED），UI 看板按 lowercase `running/success/failed` 分桶——未来真数据会静默错分列/成功率读 0（adversarial P2）。
**T**：补列表端点 + 在单一消费点做状态归一 + 加指标名防漂移护栏。
**A**：
- `CheckpointManager.listByCreatedBy`（接口 default 返回空 + TODO Postgres；`InMemoryCheckpointManager` 用 `workflowCreatedAt` 记录 + 创建倒序）→ `WorkflowController GET /api/workflows`（按 API Key hash 返回本人工作流，401/只见自己的）。
- 前端 `api.ts` 归一映射：后端 record → `WorkflowSummary`（status `toLowerCase`、`pending` 并入 `running` 与看板三列对齐；nodes/steps/time/desc 给缺省）。**只在 `listWorkflows` 一处归一**，Dashboard 零改动即兼容真实/ mock。
- `agentflow-starter/GrafanaDashboardMetricAlignmentTest`：看板 PromQL 引用的每个指标族 ⊆ `AgentFlowMetrics` 常量族（点号→下划线、Counter+`_total`、Timer+`_seconds_bucket`）——将来改常量名面板不会静默空。
**R**：列表端点有数据（2 提交倒序 + 401 + 只见自己的测试）；指标改名漂移即时红。

---

## 5. 面试可讲的故事（Result 量化）

- **"真实 API 路径为什么之前跑不通"**：不是引擎 bug，是**装配缺失**（无 wiring、NodeRegistry 空）——找出"接口存在但从未被接起来"的工程判断力。
- **Spring Boot 4.1 激进重构**：MockMvc/web 测试自动配置和 JDBC autoconfig 都**移出了原包**——用 diagnostic 思路（`ClassNotFoundException` → 找真实类在哪个 jar）定位，而非盲目 +依赖。
- **只读派生字段的反序列化**：`@JsonCreator` + `@JsonProperty(READ_ONLY)`，理解 Jackson 属性访问模型。
- 全部改动 `mvn verify` 9 模块绿 + UI `npm run build`（tsc strict）绿，配 HTTP 端到端 + 真 boot 双实证。

## 6. 遗留（诚实交代）

- Postgres 版 `listByCreatedBy` SQL 待补（U8，现 default 返回空）。
- Postgres 版诊断 round-trip 未覆盖（仅 InMemory/mock 路径有测试）。
- `VITE_API_KEY` 生产加固（服务端注入 key）是部署/ops 决策，未动构建语义。
- UI 仍无 Vitest 单测（plan 明确 Deferred），靠手动验证 + build 门禁。
