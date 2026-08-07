# AgentFlow — 接手指南（给 Claude Code）

> 本文件让接手本项目的 Claude Code 会话快速读懂现状并继续工作。读完这一份 + `docs/plans/agentflow/` 就能动手。最后更新：2026-08-07（U1–U14 + 后续任务 #9–#12 + U8 版本管理 + UI + Grafana 全交付，补 Postgres listByCreatedBy 收尾 + UI Vitest 单测，8 模块 `mvn verify` 绿 + `npm run build` 绿 + `npm test` 20 绿）。
>
> **状态/路线文档**：`docs/ROADMAP.md`（v1 交付盘点 · 剩余工作 · v2 路线图）+ `docs/GRAFANA.md`（可观测/Grafana 部署与验证）——接手或规划下一步先看这两份。

## 这是什么项目

AgentFlow = **Java 原生轻量级 Multi-Agent 编排引擎**。YAML DSL 声明工作流，BSP（Bulk Synchronous Parallel）执行模型驱动，两级 Checkpoint + Recovery Protocol，多 Agent 像微服务一样协作。

- **定位**：简历三项目之一（ToyRush 高并发基础 / InterviewCoach AI Agent+RAG / **AgentFlow 后端工程化 70% + Agent 30%**）。**从0复现展示后端工程深度**，不填补生态空白——LangGraph4j / Spring AI Alibaba 已存在，价值在工程深度而非空白。
- **v1 范围**：静态 DAG（串行+并行+混合）+ PostgreSQL 持久化 Checkpoint + 调试体验，10 周交付，15 个实现单元（U0–U14）。
- **栈**：Java 21（Virtual Threads）/ Spring Boot 4.1.0（U3 起 bump：Spring AI 2.0 需 Spring Framework 7 + Jackson 3）/ Spring AI 2.0.0 GA / PostgreSQL / Redis / Maven 多模块。

## 当前进度

**计划文档**：`docs/plans/agentflow/`（8 个分片，`00-overview.md` 是索引+导航）。两轮 ce-doc-review 闭环 + 5 条动工前卡点拍板，**0 动工阻塞**。

**已落地（main 分支，feat/u3-agent-adapter 已合）**：
| Unit | 内容 | 验证 |
|:---|:---|:---|
| — | 计划文档纳管 | — |
| 脚手架 | Maven 多模块（parent + core/adapters-spring-ai/api/starter） | `mvn validate` 5 模块 SUCCESS |
| U0 | CI/CD：`.github/workflows/ci.yml` + `performance-benchmark.yml` + `sonar-project.properties` + `docker-compose.test.yml` + JaCoCo/Failsafe 插件 | `mvn verify` SUCCESS |
| U1 | YAML DSL：`com.agentflow.dsl` 包，8 POJO + 解析器 + 三层校验 + 最长路径分层 + JSON Schema + 13 测试 | 13 tests pass，JaCoCo 80% 达标 |
| U2 | BSP 执行引擎：`com.agentflow.agent`（AgentFunction/AgentInput/AgentOutput + 异常合约）+ `com.agentflow.engine`（BspEngine/NodeExecutor/WorkflowContext/ChannelReducer/DAGraph/SuperStep/NodeResult）+ `engine.checkpoint` seam（CheckpointManager + Noop，U5 提供 PG 实现）。Virtual Threads 并行 + CompletableFuture.allOf barrier + 只读快照 + Reducer 确定性合并 + 异常隔离。经 8 人 ce-code-review + 11 修复（null output/inputs 透传/catch-all/cancel 守卫/parseTimeout 零负值/CONCAT 扁平/MAX 精度/失败层不写 barrier） | 57 tests pass，JaCoCo 80% 达标，`mvn verify` 5 模块 SUCCESS |
| U3 | Agent 适配器层：bump Spring Boot 3.4→4.1.0（Spring AI 2.0 需 Spring Framework 7 + Jackson 3）+ spring-ai-bom 2.0.0 + spring-ai-starter-model-openai。`SpringAiAgentAdapter`（AgentFunction 实现：SpEL 解析 `${...}` + ChatClient.call + 异常 Transient/Fatal 映射 + cancel best-effort）+ `SpelPromptResolver`（SimpleEvaluationContext forPropertyAccessors + DataBindingPropertyAccessor + MapAccessor，禁 T()）+ `TokenCountingAdvisor`/`LoggingAdvisor`（BaseAdvisor）+ `OutputSchemaValidator`（networknt 3.x，带反馈重试 ≤2）+ `NodeRegistry` + `ExecutionTrace`/`NodeTrace`。AgentInput 加 tools/outputSchema、AgentOutput 加 structuredOutput/metadata。经 ce-code-review + 3 correctness 修复（LoggingAdvisor NPE / schema 耗尽 NodeTrace 永留 RUNNING / inFlight cancel race） | 110 tests pass（71 core + 39 adapter），JaCoCo 80% 达标，`mvn verify` 5 模块 SUCCESS |
| U4 | 容错机制：`com.agentflow.engine.fault`（ErrorClassifier/RetryPolicy/TimeoutPolicy/ErrorHandler，全 record）。三层链路 Timeout→ErrorClassifier→Retry（指数退避 1s→2s→4s，max 3，仅 transient）→ErrorHandler（abort 前 context 补偿）。BspEngine 新构造注入 RetryPolicy/ErrorHandler/TimeoutPolicy；runSuperStep 用 retryPolicy 包 NodeExecutor + allOf.get(remaining) 工作流总超时；applyBarrier 抛前调 ErrorHandler。失败传播：节点耗尽/fatal → 工作流 FAILED abort。retry 预算组合式（3 attempt × 内含 schema-retry ≤2 = 9 上限）。adapter.mapException 委托 ErrorClassifier（单一真相源）。经 ce-code-review + 1 correctness（backoffFor 小数倍率截断）+ 4 cleanup 修复 | 136 tests pass（97 core + 39 adapter），JaCoCo 80% 达标，`mvn verify` 5 模块 SUCCESS |
| U5 | 两级 Checkpoint 持久化 + Recovery：`com.agentflow.engine.checkpoint` 包。CheckpointManager 接口扩展（U2 seam 落地：查询方法 + 工作流生命周期 + U14 加 findCreatedBy 所有权查询）+ `InMemoryCheckpointManager`（ConcurrentHashMap，开发测试）+ `PostgresCheckpointManager`（JdbcTemplate + Flyway 迁移 + Semaphore(20) 限流 + ON CONFLICT 幂等 upsert）+ `RecoveryProtocol`（崩溃恢复，off-by-one 修复：查 nextSuperStep）+ `BspEngine.recoverAndExecute`（U5 P0 修复新增：replayOutputs 重放崩溃层输出 + stray COMPLETED 防护）。数据 record：NodeOutputStore / BarrierCheckpoint / ExecutionState + NodeStatus / WorkflowStatus 枚举。DB 迁移 V1__checkpoint_schema.sql（3 表 + 2 索引 + barrier UNIQUE 约束）。经 ce-code-review 10 reviewer + 2 个 P0 修复（ADV-1 崩溃层 channel 丢失 / ADV-2 stray 防护未实现，4 reviewer 独立确认） | 131 core tests pass，JaCoCo 80% 达标，`mvn verify` 5 模块 SUCCESS，已合 main |
| U14 | API 鉴权 + 凭证管理：`ApiKeyAuthFilter`（OncePerRequestFilter，SHA-256 hash）+ `WorkflowOwnershipChecker`（防 IDOR）+ `CallerToolAllowlist`（per-caller tool 授权）+ `CredentialManager`（LLM 凭证从 env 读取，禁止 yml 硬编码）+ `PromptRedactionFilter`（正则脱敏 API Key/手机号/身份证）+ `WorkflowController`（POST /api/workflows → 202 异步执行 + GET status + POST retry）+ DB migration V2（workflow_executions.created_by）。接口对齐：U14 弱类型 Optional<?> 改 U5 强类型 + WorkflowStatus 枚举 + initWorkflow 4 参(createdBy) + findCreatedBy | api 测试 8 个（含补的 WorkflowControllerTest）+ core 22，JaCoCo 80% 达标，`mvn verify` 5 模块 SUCCESS，`feat/u14-api-security` 分支（已 rebase 到含 U5 的 main） |
| U6 | 调试体验：`DryRunEngine`（不调 LLM 的干跑，验证拓扑/SpEL/channel）+ `DiagnosisService`（分析 5 类异常：超时/成本/token 异常/失败层/孤儿节点）+ `StructuredLogger`（结构化执行日志）。经 ce-code-review 修复（token 异常测试数据：3 正常节点 + 1 极端离群点过 3x 阈值） | core 测试，JaCoCo 80% 达标，已合 main |
| U7 | 可观测性：`AgentFlowMetrics`（5 Micrometer 指标：workflow.executed / node.duration / tokens.consumed / workflow.cost.estimated / workflow.cost.budget_exceeded）+ `CostCalculator`（token×模型单价表，单价表放 `agentflow-cost-pricings.json` 配置文件启动期加载，R4 规避）+ `ExecutionTraceRegistry`（workflowId→trace 集中存放，ConcurrentHashMap）+ `TraceController`（GET /api/workflows/{id}/trace 返回 ExecutionTrace.Snapshot）。**Trace 穿线**（KTD-2 难题 R1）：BspEngine 5-arg 构造器注入 registry → execute() 注册 trace → AgentInput 第 9 字段穿线 → MockAgentFunction/SpringAiAgentAdapter 从 AgentInput.trace() 取 trace 写 NodeTrace（mock 模式补齐，OQ-3 决议扩展）→ TraceController 从 registry 取 snapshot。traceRegistry=null 整条链路 no-op，旧构造器保留向后兼容。TokenCountingAdvisor 新增 3-arg 构造器委托 AgentFlowMetrics 记成本（2-arg 旧构造器保留）。**经 ce-code-review 11 reviewer 审查**：2 P0（TraceController IDOR / recoverAndExecute 不接 trace，后者 4 票确认）+ 4 P2 全修（trace 兜底/死代码清理/CostCalculator 畸形 JSON/U12 命名连字符）| 305→306 tests，JaCoCo 80% 达标，`mvn verify` 8 模块 SUCCESS，`feat/u7-observability` 分支（含 U11/U12 + review 修复） |
| U11 | 合同审核串行 Demo（对比 U10 并行拓扑）：新 `demo-contract-review` 模块，4 节点串行链（合同解析→法律风险→合规建议→最终报告），每步 mock_response 用 `${previousStep}` 占位符引用上一步输出，验证 BSP 串行依赖链 + 上下文逐级传递。4 super-step 各 1 节点。**附带修复**：MockAgentFunction PLACEHOLDER 正则 `[\\w.]` → `[\\w.-]` 支持连字符 channel 名（`${contract-parse}` 之前不解析，channel=nodeId 用连字符是项目约定），加 `hyphenatedChannelResolves` 测试锁定 | 5 tests，`mvn verify` SUCCESS |
| U12 | 投资分析双层 fork-join Demo：新 `demo-investment-analysis` 模块，6 节点 4 super-step 双层 fork-join（step0 公司财报+市场数据并行 → step1 可行性分析串行 → step2 风险评估+收益预测并行 → step3 投资裁决汇总），验证 BSP 最长路径分层泛用性 + channel 隔离。用 `DAGLayerer.computeSuperSteps` 断言 4 层分层。node id 用下划线（`company_finance`）避开连字符占位符问题（U11 已修连字符正则，但 U12 保持自包含） | 6 tests，`mvn verify` SUCCESS |
| U1/U2 | React UI 5 Tab（看板/提交/工作流定义/执行轨迹/诊断报告）：`agentflow-ui` 按 prototype-final.html 转 React 18 + Vite 5 + Tailwind 3 + TypeScript strict + lucide-react。Layout 深色侧边栏 5 菜单；**KTD-1 真实 API 优先 + mock fallback**（api.ts fetch 5s 超时失败降级 mockData，后端未起不白屏，UI 显示 mock 徽标）；Vite proxy `/api`→localhost:8080 规避 CORS。Dashboard 三列看板按状态分组 + 最近执行表；SubmitForm YAML 编辑器（contenteditable 手写高亮+行号+实时校验 nodes/agentflow 段，零新依赖）→ spinner→toast→跳轨迹页（真实 API 失败走 mock 模拟全流程）；WorkflowDefinitions 卡片选中高亮跳转提交页预填；PipelineView BSP super-step 分组渲染（并行→barrier→汇总）；DiagnosisPanel KPI+5 类问题卡片。React 单测（Vitest）2026-08-07 补齐（api.ts + Dashboard） | `npm run build` 绿（tsc strict）+ dev HTTP 200 + `npm test` 16 绿 |
| U4 | Grafana Dashboard JSON（R9）：`agentflow-starter/src/main/resources/grafana/agentflow-dashboard.json`，6 面板（工作流执行趋势按 status / 各 Agent P50-P95-P99 / token 消耗 Top10 / LLM 成本按 model / 预算超限+窗口总成本 / 失败率），Prometheus 数据源 + 模板变量，指标名对齐 AgentFlowMetrics 常量。配套：`AgentFlowMetrics.recordNodeDuration` 开 `publishPercentileHistogram`（暴露 `_bucket` 序列供 histogram_quantile 算分位，count/sum/max 语义不变） | JSON 合法（node 解析）+ 指标名对齐 + `mvn verify` 全绿 |
| U8 | Workflow 版本管理（R14）：`com.agentflow.version` 包（`WorkflowDefinitionStore` 接口 + `InMemory`/`Postgres` 实现 + `WorkflowVersionManager` + `VersionConflictDetector`）。按 `(workflow_name, version)` 把解析后的定义存 `workflow_definitions` JSONB 表（V3 迁移）；提交时 `submit` 记录定义（恢复/retry 不再从 classpath 读）→ 版本 bump 后旧实例仍按旧 DAG 执行；`VersionConflictDetector` 仅 WARN 不阻断；`GET /workflows/{id}/version-check` 报执行版本 vs 最新定义版本冲突。`CheckpointManager` 增 `findWorkflowName/findVersion`（InMemory+Postgres）。demo-api ApiConfig 注册 InMemory 存储 | core VersionTest 7 + api WorkflowControllerTest 10 + demo-api 6；core+api JaCoCo 门禁 verify 绿 |

**OQ-2 决议**：plan 当初猜 Spring AI 2.0 要 Boot 3.5——实际不够。3.5 仍带 Jackson 2.19，而 Spring AI 2.0.0 的 @Tool schema 路径用 Jackson 3（`tools.jackson.core`，需 `JsonSerializeAs`）。**正确版本是 Spring Boot 4.1.0 GA**（自带 Jackson 3.1.4 + Spring Framework 7）。U3 起全仓升 Boot 4.1。

**U3 实现时关键发现（记此备查，非 plan 偏离）**：
- **Spring AI 2.0 mutable deque**：`.chatResponse()` 与 `.content()` 各自触发一次 advisor 链执行，`DefaultAroundAdvisorChain.callAdvisors` deque 是 mutable（nextCall pop），第二次执行撞空 deque → "No CallAdvisors"。适配器只调一次 `.chatResponse()`，content + usage 都从同一 ChatResponse 取。
- **Spring 7 SpEL**：`forReadOnlyDataBinding()` 不再注册 MapAccessor（Map 属性访问移出 ReflectivePropertyAccessor）。用 `forPropertyAccessors(DataBindingPropertyAccessor.forReadOnlyAccess(), new MapAccessor())`——同时支持 record 组件 + 嵌套 Map 键访问；Builder 不设 TypeLocator/MethodResolver，T() 与方法调用均抛 SpelEvaluationException（KTD-2 安全）。
- **Spring AI 2.0 无 Transient/NonTransientAiException 标记类**（1.0 移除），异常按 cause 粗分类（IOException/Timeout/网络 → Transient；SpEL + 其余 → Fatal），U4 ErrorClassifier 细化。
- **OQ-3 决议（metadata 回传）**：适配器在 `.call()` 后直接读 `chatResponse().getMetadata().getUsage()` 写 AgentOutput.metadata——不经 ThreadLocal/advisor-context 传值（VT 脆弱）。NodeTrace 由适配器拥有（持 nodeId + chatResponse），LoggingAdvisor 只做结构化日志、TokenCountingAdvisor 只接 Micrometer。
- **cancel() 降级（KTD-6 v4.3）**：Spring AI 2.0 ChatClient 同步阻塞、无公共 HTTP abort 钩子；适配器 cancel() 为 best-effort no-op（记 warn），实际中止由 NodeExecutor 的 `future.cancel(true)` 中断 VT（per-execution Future，无 race）。

> **U3 详细接手清单**：[`docs/handoff/u3-spring-ai-adapter.md`](docs/handoff/u3-spring-ai-adapter.md) — 已完成，留作 U3 实现决策的历史记录。

**后续顺序**（按 `05-implementation-units.md` 的 Unit Priority 矩阵 P0 先行）：
U3 ✅ → U4 ✅ → U5 ✅ → U14 ✅ → U9 ✅ → U10 ✅ → U13 ✅（P0 全部交付）。P1/P2：U6 ✅ → U7 ✅ → U11 ✅ → U12 ✅ → **U8（版本管理）→ UI（React 5 Tab）→ Grafana Dashboard**。详见 `docs/plans/2026-07-31-001-feat-ui-observability-aux-demos-plan.md`。

> **当前状态（2026-08-03）**：
> - U7/U11/U12 已合 main（远程 `55125f1` 起，含 ce-code-review 2 P0 + 4 P2 修复 + developer-notes 文档）
> - **本窗口新增**：本地并行实现同批任务后发现与远程分叉 → 以远程为基整合。cherry-pick 本地独家 **U1/U2 React UI**（`84f8ff6`/`1a7188c`）+ 提交 **U4 Grafana Dashboard**（`e062c0f`，含 `AgentFlowMetrics.recordNodeDuration` 开 percentile histogram）。本地后端重复实现已弃用（备份分支 `backup/2026-08-local` + `backup/2026-08-local-backend`，可逆）
> - `mvn verify` **8 模块全绿** + `npm run build` 绿（UI tsc strict）
> - **已 push origin main**（`55125f1..57d8057`，U1/U2/obs+Grafana/CLAUDE.md/review-fix 6 commit）
> - 下一批（后续任务 + plan Deferred）：可运行 API server wiring（真实 API 路径可验证）+ PipelineView 真实 super-step 分组 + /diagnosis 反序列化 + 看板列表端点；UI React 单测（Vitest）+ Grafana 真实部署验证 + U8 版本管理

> **当前状态（2026-08-07）——mock token/成本记账（Token/成本 Grafana 面板，feat/mock-token-cost-metrics）**：
> - 补齐 Token/成本面板数据源：MockAgentFunction 注入可空 AgentFlowMetrics + model + 预算阈值，按 prompt/响应长度模拟确定性 token，经 recordTokens 记 `tokens.consumed{agent,model}` + `cost.estimated{model}`，可选 checkBudget 触发 `budget_exceeded`（recordTokens 本设计供 mock 模式用；null 兼容旧行为）
> - demo-api 接 gpt-4o-mini + 演示阈值；MockAgentFunctionTest +3
> - **端到端验证**：demoda-api 起服提交工作流 → `/actuator/prometheus` 现含全部 5 类指标族（executed/node.duration/tokens.consumed/cost.estimated/budget_exceeded）
> - 全仓 verify 绿 + JaCoCo met；6 Grafana 面板现均有数据
> - 仍遗留：真 PG/Grafana 部署验证依赖 CI/有 Docker 环境；真实 LLM 路径出真实 token（mock 出模拟值）

> **当前状态（2026-08-07）——U7 指标挂钩引擎（feat/u7-metrics-hookup）**：
> - 修复 Grafana 数据通路缺口：`recordWorkflowExecuted`/`recordNodeDuration` 此前仅测试调用、引擎从未记录
> - BspEngine 注入可空 `AgentFlowMetrics`（6-arg 新构造，旧构造委托 null 向后兼容）：完成路径记 `workflow.executed{status}`（outcomeRecorded 防漏记/防双记 + finally 兜底），每节点记 `node.duration{agent}`（含重试，PercentileHistogram 供 P50/P95/P99）
> - demo-api ApiConfig bspEngine 注入 metrics；BspEngineMetricsTest 3 个（成功/失败/noop）
> - **端到端验证**：demo-api 起服 + 提交工作流 → `/actuator/prometheus` 现返回 `agentflow_workflow_executed_total{status=...}` + `agentflow_node_duration_seconds_bucket/count/max/sum`
> - core 207 tests + 全仓 verify 绿 + JaCoCo met
> - 仍遗留：真 PG/Grafana 部署验证依赖 CI/有 Docker 环境；Token/成本面板已由 mock 记账补齐（见 2026-08-07 mock 状态）

> **当前状态（2026-08-07）——遗留收尾（chore/remaining-items）**：
> - **VITE_API_KEY 加固**：抽 `resolveApiKey(envKey, isProd)`；生产未配置 VITE_API_KEY → `console.error` 显式告警而非静默用公开 demo key；+4 测试（配置优先/dev demo/生产告警/空串回退）
> - **UI 入 CI**：`ci.yml` 加 `ui` job（setup-node 20 + npm ci + `npm run build` + `npm test`），与 Java build 并行
> - **Grafana 验证**：静态校验 JSON（6 面板 + DS_PROMETHEUS）+ `GrafanaDashboardMetricAlignmentTest` 通过；**exporter 已接线并本地起服验证**（demo-api 加 micrometer-registry-prometheus + actuator，移除手写 SimpleMeterRegistry，`/actuator/prometheus` HTTP 200 + JVM 指标）。<b>新发现缺口</b>：U7 的 `recordWorkflowExecuted`/`recordNodeDuration` 引擎从未调用（仅测试调用）→ mock 路径无 agentflow 指标，Grafana 面板仍空，需 U7 指标挂钩引擎 feature（见 docs/GRAFANA.md）
> - **Postgres 集成 IT**：`PostgresCheckpointManagerIT`（Failsafe，CI 有真 PG 全跑 / 本地无 PG 跳过），覆盖 listByCreatedBy 真 PG + checkpoint/元数据往返
> - 遗留仍待：~~U7 指标挂钩引擎~~（已补，见 feat/u7-metrics-hookup）；真 PG/Grafana 部署验证依赖 CI/有 Docker 环境

> **当前状态（2026-08-07）——UI React 单测（Vitest，Deferred 收尾）**：
> - `feat/ui-vitest`：给 `agentflow-ui` 配 Vitest 工具链 + 首批单元测试
> - 工具链：vitest/jsdom/@testing-library（react/jest-dom/user-event）；`vite.config.ts` 加 test 段（jsdom + globals + `pool:threads` 规避 Windows fork worker 超时）；`tsconfig` 排除 `*.test.*`/`src/test` 保持 build 的 tsc 只查生产代码；`src/test/setup.ts` 注册 jest-dom + cleanup；`npm test` / `npm run test:watch`
> - 测试：`api.test.ts` 12 个（mock fetch 验证请求契约/X-API-Key header/状态归一 UPPER→lower/mock fallback/ApiError）+ `Dashboard.test.tsx` 4 个（三列分桶 data-testid 计数/成功率 KPI/mock 徽标显隐）；Dashboard 分组计数加 data-testid
> - 验证：`npm test` 16 绿 + `npm run build` 绿（tsc strict）
> - 已合 main + push：`fe72420`/`a10fabe`（`c4a5308..a10fabe`）

> **当前状态（2026-08-07）——补 Postgres listByCreatedBy（#12 遗留收尾）**：
> - `PostgresCheckpointManager.listByCreatedBy`（`821f251`，feat/pg-list-by-created-by 分支）：从 `workflow_executions` 按 created_by 过滤（空则返回全部，兼容 U5 未设 created_by）+ created_at 倒序，对齐 InMemory 语义；抽 SELECT_EXECUTION_RECORDS/BY_CREATOR + EXECUTION_RECORD_MAPPER 为 package-private 单真相源
> - 测试 `PostgresCheckpointManagerTest`（4 个）：生产 Flyway 含 PG 专属类型（TIMESTAMPTZ/JSONB）无法跑 H2，故用 H2 建兼容 `workflow_executions` 表跑真实 SQL+mapper，验证过滤/倒序/status 归一/空表/Null createdBy
> - `mvn verify` 8 模块全绿（core 204 + api 47 + adapter 55 + demo 等），JaCoCo 全 met；`npm run build` 绿（基线 08-07 复核）
> - 本机无 Docker/PG，未能对真实 PG 做端到端验证（docker-compose.test.yml 留作本地/CI 集成验证路径）
> - 仍遗留：VITE_API_KEY 生产加固（ops 决策）；Postgres 版 diagnosis 未覆盖；Grafana 部署验证 Deferred；UI React 单测（Vitest）已补（见 2026-08-07 状态）

> **当前状态（2026-08-04）——后续任务 #9–#12 全部交付**：
> - **#9 可运行 REST API server**（`f7bfdb4`，demo-api 模块）：把 controllers + ApiKeyAuthFilter（含 UI 默认 demo key）接成可启动 Spring Boot app；NodeRegistry 加 mock fallback；Boot 4.1 注解级 exclude（starter + spring-ai OpenAi + JDBC DataSource 已移包）。真实 `spring-boot:run` 起服（Tomcat:8080）+ curl 全链路跑通
> - **#10 ExecutionTrace 带 super-step 层号**（`cebe9e9`）：NodeTrace.step + BspEngine 记层，UI PipelineView 按真实拓扑分组（串行/双层 fork-join 不再错扁平），mock 回退启发式
> - **#11 /diagnosis 真实 trace 反序列化**（`53317cb`）：NodeTrace @JsonCreator + 派生 getter READ_ONLY，诊断真实路径不再绑空/400
> - **#12 看板列表端点 + 状态归一 + 指标防漂移**（`88a3d86`）：`GET /api/workflows`（CheckpointManager.listByCreatedBy + InMemory），UI status 归一（enum UPPER→lower），starter GrafanaDashboardMetricAlignmentTest 防指标名漂移
> - **U8 Workflow 版本管理**（`f32582d`，R14）：version 包（定义存储 InMemory/Postgres `workflow_definitions` + Manager + 冲突检测），提交记录定义、恢复/retry 取旧 DAG、`GET /version-check` 报冲突，WARN 不阻断
> - 遗留（记录）：~~Postgres listByCreatedBy SQL 待补~~（已补，见 2026-08-07 状态）；VITE_API_KEY 生产加固为 ops 决策；Postgres 版 diagnosis 未覆盖
> - UI React 单测（Vitest）已补（见 2026-08-07 状态）；Grafana 真实部署验证仍 Deferred

> **当前状态（2026-08-02）**：
> - P0 全交付 + U6/U7/U11/U12 落地，`mvn verify` **8 模块全绿（305 tests pass）**，JaCoCo 80% 达标
> - 三个并行单元在 `feat/u7-observability` 分支：U7（`c888473`，可观测性）+ U11（`e869444`，合同审核串行 Demo）+ U12（`1b72166`，投资分析 fork-join Demo）
> - **未 push、未合 main**（待 ce-code-review + 用户确认外向操作）
> - 下一批：UI（React 5 Tab，串行先行）+ Grafana Dashboard JSON + U8 版本管理
> - 历史：U5+U14+U9+U10+U13 已合 main；U6 已合 main。main 本地领先 origin 多个 commit，待 push

> **当前状态（2026-07-30）**：
> - **P0 全部交付**：U1-U5 + U14 + U9 + U10 + U13 已合 main
> - `mvn verify` 6 模块全绿（157 tests pass），JaCoCo 80% 达标
> - U13 落地情况：
>   - `AgentFlowAutoConfiguration` 完整版（注册 WorkflowDSLParser / BspEngine / ChannelReducer / NodeRegistry / CredentialManager + InMemoryCheckpointManager mock 模式 / PostgresCheckpointManager 生产模式 @ConditionalOnBean(DataSource)）
>   - 根目录 `docker-compose.yml`（mock/production profiles）
>   - `README.md`（架构图 + Quick Start + Tutorial 三步 + 3 接入模式 + 生产部署清单 + FAQ）
>   - `docs/TROUBLESHOOTING.md`（Top 10 FAQ）
>   - `StarterIntegrationTest`（@EnableAgentFlow 自动注入 3 tests）
>   - `@EnableAgentFlow` 注解 + `AgentFlowProperties`（mock.enabled 配置）
> - main 本地领先 origin 9 commit（U5×3 + U14 + U9 + U10 + U13×3），**待 push**

> **当前状态（2026-07-28）**：
> - U5 + U14 已合 main（fast-forward）；U9 在 `feat/u9-mock-llm` 分支，实现+测试完成，`mvn verify` 5 模块全绿（150 tests pass，JaCoCo 80% 达标），待 ce-code-review + commit
> - main 本地领先 origin 4 commit（U5×3 + U14×1），**待 push**（外向操作需用户确认；远程旧 feat/u5、feat/u14 分支 rebase 重写了历史，push 后需 force-push 或删旧远程分支）
> - 工具链：Maven 3.9.16 已装 `C:\Users\YushengWang\apache-maven-3.9.16`，已加进 User PATH（新终端有 mvn）
>
> **U9 落地情况**：
> - `MockAgentFunction`（agentflow-adapters/spring-ai/mock）：从 `AgentInput.mockResponse()` 读预设响应，支持 `${channel}` 占位符替换（含嵌套点路径 `${a.b}`），缺失抛 `MissingMockResponseException`（Fatal）。无状态单例，任意 agent name 复用
> - `MissingMockResponseException`（core/agent）：FatalException 子类
> - `AgentInput` 加 `mockResponse` 字段（透传自 NodeDefinition.mockResponse，U1 已预留）——**接口扩展**，所有构造点已同步改（BspEngine + adapter/api 测试）
> - `AgentFlowAutoConfiguration` + `AgentFlowProperties` + `EnableAgentFlow`（agentflow-starter）：`agentflow.mock.enabled=true` 时注册 `mockAgentResolver` Bean（任意 name → MockAgentFunction 单例）+ `mockBspEngine`。AutoConfiguration.imports 已注册
> - **跳过 MockAdvisor**（plan 提及但 v1 非必要）：mock 模式不走 ChatClient/advisor 链，MockAgentFunction 直接返回。记为设计决策（避免过度设计）
> - **完整 BSP 端到端跑通**（parser+registry+engine 全套）留给 U13 Starter 完整封装；U9 MockModeTest 聚焦 AutoConfiguration Bean 切换契约（3 tests）
> - 测试：MockAgentFunctionTest 8 个（含占位符边界）+ MockModeTest 3 个
> - 缺 `mock_response` → 抛 `MissingMockResponseException`
> - 验收：本地跑通，不发任何 LLM API 调用

> **历史状态（U5/U14 合并前，留档备查）**：U5 和 U14 的 CheckpointManager 接口曾有不兼容设计冲突（U14 弱类型 `Optional<?>` vs U5 强类型），已通过对齐修复——以 U5 强类型为基底融入 U14 的 createdBy/findCreatedBy。详见 `docs/developer-notes/02-bugs-and-fixes.md` Bug-6。

**U2 留给后续单元的 seam（实现时已决策，非 plan 偏离，记此备查）**：
- `AgentFunction`/`AgentInput`/`AgentOutput`/异常类型由 U2 引入最小合约 → U3 已落地。
- `CheckpointManager` 接口 + `NoopCheckpointManager` 由 U2 引入 → U5 已落地：接口扩展查询+生命周期方法，提供 InMemory/Postgres 实现 + RecoveryProtocol + DB migration。
- `NodeExecutor` 已做节点级超时 + `cancel(true)` 中断 → U4 已落地容错包裹。

**仍开放**：13 条叙事/范围类 Open Questions（`06-open-questions-risks-metrics.md` 的 `### From 2026-06-28 review`），演示前定即可，不挡动工。

## 仓库结构

```
AgentFlow/
├── pom.xml                      # parent（Spring Boot 4.1.0 + Java 21 + JaCoCo/Failsafe；spring-ai-bom 2.0.0 import）
├── settings.xml                 # 本地 Maven 镜像（HTTPS alimaven，gitignored，非项目配置）
├── CLAUDE.md                    # 本文件
├── README.md                    # GitHub 门面
├── .github/workflows/           # U0 CI
├── docker-compose.test.yml      # 本地集成测试 PG+Redis
├── sonar-project.properties
├── docs/plans/agentflow/        # 计划文档（8 分片，权威 source of truth）
│   ├── 00-overview.md           # 索引+导航
│   ├── 01-problem-frame.md      # Problem Frame / 简历定位
│   ├── 02-requirements.md       # R1–R22
│   ├── 03-key-technical-decisions.md  # KTD-1~9
│   ├── 04-high-level-design.md  # 架构图 / BSP / ER / 状态机
│   ├── 05-implementation-units.md    # U0–U14 + Priority 矩阵（执行用）
│   ├── 06-open-questions-risks-metrics.md
│   └── 07-sources-revision-interview.md
├── agentflow-core/              # BSP/DSL/Checkpoint/容错/可观测/安全
│   └── src/main/java/com/agentflow/dsl/   # U1 已落地
├── agentflow-adapters/spring-ai/# SpringAiAgentAdapter（U3 引入 Spring AI 2.0）
├── agentflow-api/               # REST 端点 + 鉴权（U6/U14）
├── agentflow-starter/           # @EnableAgentFlow + AutoConfiguration（U9/U13）
└── agentflow-ui/                # React 18 + Vite + Tailwind，5 Tab 交付门面（U1/U2）
```

## 怎么构建 / 测试

```bash
# 工具链：JDK 21 + Maven 3.9+（确认 java -version / mvn -version）
# 国内网络：本仓 settings.xml 把 central 镜像到 HTTPS alimaven（gitignored，CI 不需要）
mvn -s settings.xml -B -ntp validate          # 仅校验结构
mvn -s settings.xml -B -ntp compile           # 编译（首次拉依赖较慢）
mvn -s settings.xml -B -ntp test              # 单元测试
mvn -s settings.xml -B -ntp verify            # 单元+集成+JaCoCo 80% 门禁（完整 verify）
mvn -s settings.xml -B -ntp -pl agentflow-core test   # 只跑 core 测试

# 集成测试（需 PG+Redis）：先起服务再 verify
docker compose -f docker-compose.test.yml up -d
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/agentflow mvn -s settings.xml verify
```

**重要**：
- **JaCoCo 80% 门禁**从 U1 起强制——每个有代码的模块覆盖率 < 80% → `mvn verify` 失败。空模块自动跳过。
- **CI（GitHub Actions）**：push/PR/每日 2 点触发，跑 `mvn verify` + Sonar（有 `SONAR_TOKEN` 才跑）+ Docker build（Dockerfile 在 U13 落地前跳过）。
- **本地 settings.xml 已 gitignore**——公司那边若网络直连 Central 可不传 `-s settings.xml`；若国内网络慢，自建一份镜像到 HTTPS alimaven。

## 约定（必须遵守）

- **包名**：`com.agentflow.<module-feature>`（dsl / engine / agent / observability / security / api / version / debug / checkpoint）
- **POJO**：Java records，不可变。YAML 字段用 SNAKE_CASE（`prompt_template` ↔ `promptTemplate`），ObjectMapper 配 `PropertyNamingStrategies.SNAKE_CASE` + `ACCEPT_CASE_INSENSITIVE_ENUMS`。
- **测试**：JUnit 5 + AssertJ（spring-boot-starter-test 已引入）。每个实现单元的测试覆盖 plan 的 Test scenarios（happy/edge/error/integration）。
- **覆盖率**：≥ 80%（JaCoCo INSTRUCTION，BUNDLE 维度）。
- **commit**：conventional `feat(scope): desc` / `fix(scope): desc` / `docs:` / `test:` / `refactor:`。中文描述 OK。
- **分支**：feature 分支 `feat/<unit-or-feature>`，绿了合 main（fast-forward 或 PR）。
- **plan 是决策件，不要改 plan 正文当进度**——进度靠 git commit + 本 CLAUDE.md 的"当前进度"段。计划要改走 `/ce-doc-review`。

## 怎么继续工作（接手后第一件事）

1. **读计划**：`docs/plans/agentflow/00-overview.md`（导航）→ `05-implementation-units.md`（U0–U14 全部单元的 Goal/Files/Approach/Test scenarios/Verification + Priority 矩阵）→ `03-key-technical-decisions.md`（KTD-1~9，含 v4.3 的 5 卡点决议）。
2. **确认起点**：本文件的"当前进度"段 + `git log --oneline` 看做到哪了。下一个是 **U5 两级 Checkpoint + Recovery**（PostgresCheckpointManager/InMemoryCheckpointManager/RecoveryProtocol + DB migration）。
3. **执行**：`/ce-work docs/plans/agentflow/00-overview.md`，按 Priority 矩阵 P0 顺序。U4/U5 强依赖、都碰核心引擎，建议 serial 派发（前一个 verify 绿了再派下一个）；U6/U9 等独立单元可并行。
4. **每完成一个单元**：`mvn -s settings.xml verify` 绿 → commit → 更新本 CLAUDE.md 的"当前进度"段 → 下一个。

## 关键技术决策摘要（详见 03 / 05）

- **KTD-1 BSP + 最长路径分层**：`level[v]=max(level[u])+1`，`DAGLayerer.computeSuperSteps` 已实现（U1）。
- **KTD-3 两级 Checkpoint + Recovery**：节点级（防 LLM 重复计费）+ barrier 级。Recovery 查 `nextSuperStep`（崩溃层本身）的 COMPLETED 节点。**v4.3：COMPLETED 同步写 + Semaphore(20) 限流**（U5），异步批量仅 telemetry。
- **KTD-6 AgentFunction**：`cancel()` default noop，但 **v4.3：`SpringAiAgentAdapter` 必须覆盖 cancel() 接底层 HTTP 中断**（U3）。
- **KTD-7 Spring AI 适配器**：可移植性约束——所有 Spring AI 调用收敛在适配器窄表面，2.0→2.1 迁移只碰适配器。Week-1 冒烟测试。
- **v4.3 五卡点**（已拍板，落地在各分片）：① flush 同步写+Semaphore ② 工具级授权 CallerToolAllowlist ③ `workflow_definitions` 表存旧版本 DAG ④ cancel 覆盖 ⑤ U13 解耦 demo 验收。
- **静态 DAG only**（v1）：无条件分支，留给 v2。

## GitHub

- 远程：https://github.com/Yusheng727/AgentFlow
- 默认分支：`main`
- CI secrets（按需配）：`SONAR_TOKEN`（SonarCloud）、`GITHUB_TOKEN`（自动有，GHCR push 用）

## 给接手 Claude 的提醒

- 这是**简历项目**，工程深度比功能完备更重要——每个单元要做"对"做"深"，不留半成品、不偷懒推 v1.1。
- 计划已经过两轮 6-persona 审查，**信任 plan 的决策**，不要重新质疑前提（七三开、从0复现、BSP 选型）——这些是 v4 定稿的。
- 遇到 plan 里没覆盖的实现细节，按 KTD 精神决策，记进 commit message 或本 CLAUDE.md，不要回头改 plan。
- 国内 Maven 镜像偶发"Remote host terminated the handshake"——用 HTTPS alimaven（本仓 settings.xml）即可，重试即可。
