# AgentFlow — 接手指南（给 Claude Code）

> 本文件让接手本项目的 Claude Code 会话快速读懂现状并继续工作。读完这一份 + `docs/plans/agentflow/` 就能动手。最后更新：2026-08-21（HITL 审批 U1–U8 + R22 列加密 + RAG demo 全落地，`feat/hitl-r22-rag` 待合 main；13 模块 `mvn verify` 绿 + JaCoCo 达标）。
>
> **状态/路线文档**：`docs/ROADMAP.md`（v1 交付盘点 · 剩余工作 · v2 路线图）+ `docs/GRAFANA.md`（可观测/Grafana 部署与验证）——接手或规划下一步先看这两份。

## 这是什么项目

AgentFlow = **Java 原生轻量级 Multi-Agent 编排引擎**。YAML DSL 声明工作流，BSP（Bulk Synchronous Parallel）执行模型驱动，两级 Checkpoint + Recovery Protocol，多 Agent 像微服务一样协作。

- **定位**：简历三项目之一（ToyRush 高并发基础 / InterviewCoach AI Agent+RAG / **AgentFlow 后端工程化 70% + Agent 30%**）。**从0复现展示后端工程深度**，不填补生态空白——LangGraph4j / Spring AI Alibaba 已存在，价值在工程深度而非空白。
- **v1 范围**：静态 DAG（串行+并行+混合）+ PostgreSQL 持久化 Checkpoint + 调试体验，10 周交付，15 个实现单元（U0–U14）。
- **栈**：Java 21（Virtual Threads）/ Spring Boot 4.1.0（U3 起 bump：Spring AI 2.0 需 Spring Framework 7 + Jackson 3）/ Spring AI 2.0.0 GA / PostgreSQL / Redis / Maven 多模块。

## 当前进度

**计划文档**：`docs/plans/agentflow/`（8 个分片，`00-overview.md` 是索引+导航）。两轮 ce-doc-review 闭环 + 5 条动工前卡点拍板，**0 动工阻塞**。

> **当前状态（2026-08-20/21）——HITL 审批 + R22 列加密 + RAG demo（feat/hitl-r22-rag，U1–U8 全落地 + 文档收尾 U9）**：
> - **U1–U3** 审批核心类型（`ApprovalRequiredException`/`NodeResult.ApprovalRequired`/`AgentInput.approvalDecision` + checkpoint 审批 SPI + V7 迁移/PG 审批/TEXT 列型）；**U3 的 H2/真 PG 测试仍待补**（`PostgresCheckpointManagerTest`/`IT`）
> - **U4 引擎暂停（pause-on-approval）**：`applyBarrier` 识别 `ApprovalRequired` → 兄弟 Success 输出读入 context 作**上下文快照** → `ApprovalRequest.pending` 落库 + `AWAITING_APPROVAL` → paused 提前退出（finally 不兜底 FAILED）；`runStep`/`runRounds` 透出 paused；`recoverAndExecute` 对 AWAITING_APPROVAL 抛错拒绝误恢复
> - **U5 引擎恢复（approveAndResume）**：从审批单恢复（不依赖 RecoveryProtocol，那是崩溃路径）：REJECT→FAILED；APPROVE→重建 context→重跑待批节点（注入 `approvalDecision=APPROVE`，多级审批链可再次暂停）→merge→`runRounds` 从审批层续跑（firstExcluded=审批层全集防双跑）→takenEdges 预置防覆盖丢边→超时重定基线
> - **U6 审批 REST + 服务层 HITL 感知**：`WorkflowExecutionService.run()` U4 paused 后不误标 SUCCESS + `resumeAfterApproval`；`ApprovalController`（GET pending 精简投影 + POST 决策），**decidedBy 服务端推导防伪造**、创建者/admin 门控、approval 归属校验；demo-api 注册 ApprovalGateAgent
> - **U7 R22 列级静态加密**：`com.agentflow.security`（ColumnEncryptor/Noop/AesGcm AES-256-GCM `AESGCM:` 自描述+legacy 明文兼容/ColumnEncryptors 工厂 fromEnv 宽松 vs fromEnvStrict fail-closed）；`PostgresCheckpointManager` 条件加解密 + AutoConfiguration 生产注入 strict
> - **U8 demo-rag（KTD-6）**：`InMemoryVectorStore`（确定性 token 集合 embedder + 余弦 top-k）+ `RagAgentFunction`（检索→增强→委托）；`RagEngineZeroChangeTest` 用 BspEngine 直跑 `agent: rag` 零改动证明扩展点
> - **分支状态**：`feat/hitl-r22-rag` 已推远程（U4–U8 + 文档），**未合 main**；`mvn verify` 13 模块绿（skip IT）+ JaCoCo met；docker IT（PG 加密密文形态/Kafka）待有环境复测
> - **Residual Deferred**：Kafka 跨节点审批恢复；R22 加密扩到 routing_decisions；RagAgentFunction 真实模型接入（`agentflow.rag.real.*`）；审批 Web UI
> - 面试叙事：HITL = 「中断→外部审批→恢复」全链路（暂停快照买回确定 + approveAndResume 复用 runRounds）；RAG = 「引擎零改动、Agent 扩展点成立」（KTD-6）

> **当前状态（2026-08-18）——档 C 面试口径 + Kafka review 残留闭环 + R21 工具级授权（feat/review-residual-r21，已合 main）**：
> - **档 C 面试口径 ✅**：`07-sources-revision-interview.md` 附 30s/5min 自述稿（主线「静态 DAG → 动态路由 → 迭代收敛 → 分布式解耦」），收口 13 条叙事追问（buy-vs-build / 七三开 / BSP vs Actor / @Tool 边界 / Spring AI 差异化 / KTD-3 防重复计费 / v1.1 Kafka 解耦）。ROADMAP 档 C ✅
> - **Kafka review 残留闭环（三段）**：① `KafkaCompatContextLoadTest`（KTD-E 无 broker 冒烟——U2 计划要求但此前未交付：context 起 + 自持 mapper 往返含 null version/中文/java.time + auto.offset 默认 earliest）② `tryClaim` 原子幂等（CheckpointManager 接口 default + InMemory `compute` + Postgres 条件 UPDATE `WHERE status='PENDING'`，消费者从 check-then-act 升级为原子条件转移，防并发重复投递双跑双计费；并发恰一胜出测试）③ mapper 补 JavaTimeModule + 禁用 WRITE_DATES_AS_TIMESTAMPS（裸 mapper 遇 java.time inputs 序列化失败/写成数组）
> - **R21 工具级授权 DB 表 + 管理 API（v1.1 档）**：`CallerToolAllowlist` 从 config 硬编码升级为 config ∪ DB 相加、总空才 allow-all 保 v1。新增 `ToolGrantRepository`（InMemory/Jdbc 两实现 + V6 迁移 `caller_tool_grants`）+ `ToolGrantController`（`/api/tools/grants`：GET 自读/admin 查任意、POST grant/DELETE revoke **admin-only**——env `AGENTFLOW_ADMIN_API_KEYS` 门控，防自授特权工具）。提交强制点仍在 `WorkflowController.submit` 的 `toolAllowList.isAllowed(caller, tool)`。**live 验证**：admin grant risk-calculator → 提交已授权 202 / 未授权 finance-db-query → **403 FORBIDDEN**
- 测试：core +16（tryClaim）· api +18（R21 repo/allowlist/controller + 提交强制集成）· kafka-starter +2（compat）；全仓 `mvn verify` 12 模块绿 + JaCoCo 达标
- **env 名坑（记此）**：`agentflow.api.api-keys` 的宽松绑定 env 是 **`AGENTFLOW_API_API_KEYS`**（不是 `AGENTFLOW_API_KEYS`）；`agentflow.admin.api-keys` → `AGENTFLOW_ADMIN_API_KEYS`——live 起服配置授权 key 时用错名会静默 401

> **当前状态（2026-08-10）——WorkflowSubmissionGuard 提交守卫（ROADMAP 档 B 真安全缺口 #2，feat/submission-guard）**：
> - 新增 `WorkflowSubmissionGuard`（api/security）：提交时预防性校验（06 OQ `POST /workflows 无 DAG/token 预算上界`）——节点数 > maxNodes 或预估成本 > maxCostUsd → **422 SUBMISSION_LIMIT**（跑完才报的 post-hoc `budget_exceeded` 之外加了提交前拦截，防恶意/失控提交起无界 VT + 烧成本）。reprised：model/maxNodes/maxCostUsd 任一 null 即禁用对应检查；成本估算复用 CostCalculator 单价表，prompt 长度估 token（4 字符/token）+ 每节点基准 500 in/out。
> - WorkflowController.submit 在工具授权后调 guard.check（拒绝后不 initWorkflow，不产生执行记录）；新增 8-arg 构造 + @Autowired（多构造需显式），7-arg 保留委托默认守卫（仅启节点数上界 500）
> - demo-api ApiConfig 注册 guard bean：`agentflow.guard.max-nodes`（默认 500）/`max-cost-usd`（配了才启成本检查）/`mock.model`
> - 测试：WorkflowSubmissionGuardTest 12 + WorkflowControllerTest 补 422 两例，api 61 全绿；**全仓 `mvn verify` 9 模块全绿 + JaCoCo 达标**，已合 main + push
> - 遗留：ROADMAP 档 B 其余 R20 archetypes / per-workflow `budget_tokens`/`budget_cost` 字段 / Reducer 冲突演练；档 A Grafana/PG 部署验证等环境

> **当前状态（2026-08-10）——R10 per-workflow 预算字段（feat/per-workflow-budget）**：
> - 把 R10 `budget_exceeded` 从"全局 mock 阈值"升级为 **per-workflow YAML 预算**（ROADMAP 档 B）：
>   - DSL `AgentflowMeta` 加 `budget_tokens`(Long)/`budget_cost`(Double)（可空）；SemanticValidator 拒绝负数/非有限
>   - 新增 `WorkflowBudget`（observability）：per-workflow 累加器，synchronized 线程安全，**edge-triggered** 超限（首次 true 之后 false，`budget_exceeded` 不再按节点数重复自增）
>   - `AgentFlowMetrics.recordBudgetExceeded()`（checkBudget 委托之）；`BspEngine.execute/recoverAndExecute` 从 `def.agentflow()` 建 budget 经 `AgentInput` 第 10 字段穿线 → MockAgentFunction 逐节点记账触发
>   - 向后兼容：`AgentInput` 加 9-arg 便捷构造；mock 无 budget 时回落全局 `budgetThresholdUsd`；无预算 YAML 不查预算
> - 测试：WorkflowBudgetTest 12（含并发 edge-triggered）+ DSL 解析/校验 4 + MockAgentFunctionTest per-workflow 2 + **MockBudgetIntegrationTest 4**（engine→mock 全链路）；全仓 `mvn verify` 9 模块绿 + JaCoCo 达标
> - 已合 main + push（`4072b3e`）；ROADMAP 档 B 该项勾 ✅
> - 遗留：ROADMAP 档 B 剩 R20 archetypes / Reducer 冲突演练；档 A Grafana/PG 部署验证等环境；成本估算低估面仍待 per-workflow（见提交守卫 review 注）

> **当前状态（2026-08-10）——v1.1 LangChain4jAgentAdapter（R5/KTD-7 可移植性验证，feat/langchain4j-adapter，已合本地 main，2026-08-11 推远程）**：
> - 第二个框架适配器实证 KTD-7"所有框架调用收敛在适配器窄表面"：新模块 `agentflow-adapters/langchain4j`（dev.langchain4j 1.0.0 GA），**依赖面仅 core + langchain4j、无 Spring AI**（构建级可替换证明）
> - `LangChain4jAgentAdapter` 对齐 Spring 适配器窄表面契约：SpEL（core 复用件）→ ChatModel.chat(ChatRequest) → content + TokenUsage → AgentOutput；@Tool bean 反射→ToolSpecification+DefaultToolExecutor + **裸 ChatModel 工具执行循环**（≤5 轮防死循环，usage 跨轮累加；未知工具/工具异常转 error JSON 回填）；异常走 core ErrorClassifier；trace 同 OQ-3 优先级；cancel best-effort（同 KTD-6 v4.3）
> - **顺带重构**：SpelPromptResolver 下沉 core（`com.agentflow.prompt`，public）+ 新增 WorkflowContext 重载（channel 扁平化收敛），Spring 适配器删私有 flatten——框架无关件单一真相源
> - 测试：LangChain4jApiSmokeTest 2（KTD-7 gate：stub ChatModel 验 ChatModel+@Tool+TokenUsage API 面）+ LangChain4jAgentAdapterTest 11 + core SpelPromptResolverTest 7；**全仓 verify 10 模块绿 + JaCoCo 达标**
> - ⚠️ **本地合 main，未 push**（用户明确）；push 后远程才有该 feature
> - **ce-code-review（2026-08-11，10 评审）**：4 项已应用（工具截断→FatalException / 中断检查 / metrics 记账 / schema warn，见 `e86059e`）；延后/需人工决策项与共享限制见 `docs/residual-review-findings/langchain4j-adapter-review.md`
> - **C2（2026-08-11，`9ba7271`）**：`OutputSchemaValidator` 下沉 core（`com.agentflow.prompt`，networknt 3.0.1 加进 core/root pom）+ LangChain4j 接入 `validateWithRetry`——structuredOutput 不再恒空，补上 KTD-7 "相同 DSL 相同结果"对价；Spring 适配器切 import 零回归。residual 文档 C2 标 ✅
> - **B2+M2（2026-08-11，`18978e8`）**：core `ErrorClassifier` 加 `composed()` 组合分类器 + 移除 spring 前缀；LC4j 适配器注入 `RetriableException`（LC4j 网络/限流/超时可重试不再误判 Fatal）、Spring 适配器注入 spring 前缀——框架异常知识全部移出 core（M2 一并解），core 保持框架无关
> - **C1（2026-08-12，residual 收尾）**：per-workflow 预算真实路径闭环——core `AgentFlowMetrics.recordBudget` 单一真相源助手（`costCalculator.cost` 纯算成本不写 token/cost counter，防与 `TokenCountingAdvisor` 双计；edge-triggered 超限触发一次 `budget_exceeded`）；`LangChain4jAgentAdapter` 在 `recordTokens` 处并记、`SpringAiAgentAdapter` 新增 8-arg 构造注入 metrics+model（6-arg 委托 null 向后兼容）、`MockAgentFunction` 复用助手收敛。测试 +7、全仓 verify 10 模块绿 + JaCoCo 达标。文档已同步（residual C1 ✅ + 03-review 补 C1 节）；已 commit+push（`e2a9664`）+ `.codegraph/` 入库 ignore
> - **B1（2026-08-12，residual SEC-1 修复）**：`LangChain4jAgentAdapter` 工具异常防泄漏——新增 `SafeToolExecutor`（自持 bean+method 自行反射 invoke），因 `DefaultToolExecutor.execute()` 反编译确认是「吞异常返回原始消息字符串、不抛出」，外部包装 catch 拦不到，故在异常边界把真实 cause 只写服务端日志、返回泛化 `{"error":"tool execution failed"}` 给模型；参数处理对齐框架语义（@ToolMemoryId/primitive/record/void/null 渲染）。`collectTools` 换用。测试 +5（`SafeToolExecutor` 覆盖 37%→93.3%）；全仓 verify 10 模块绿 + JaCoCo 达标。文档已同步（residual B1 ✅ + 03-review 补 B1 节）
> - **B3（2026-08-12，residual ADV-2 修复）**：`LangChain4jAgentAdapter` 工具注册补继承/接口——新增 `collectToolMethods(Class)` 全层级遍历（本类 + 基类 + 接口含父接口）+ `walkHierarchy` + `signature()` 签名去重（`LinkedHashMap`/`putIfAbsent`，类实现优先于接口抽象）。**关键取舍**：不用 `getMethods()`（丢非 public @Tool，本项目工具多包私有），逐层 `getDeclaredMethods()` 保留非 public + 覆盖继承面。测试 +2（基类工具/接口工具驱动工具循环断言回填；原有包私有工具测试全绿）；全仓 verify 10 模块绿 + JaCoCo 达标。文档已同步（residual B3 ✅ + 03-review 补 B3 节）
> - **ce-code-review（2026-08-12，10 评审复审 C1+B1+B3）**：对已合 main 的 `e2a9664/2c6973f/8318d92` 做复审，抓 7 条 P2，应用 4 条高确信修复（`8157e4a`）——① 预算**逐轮记账**进 `chatWithTools` 每轮（schema 重试/工具多轮/随后失败轮次都计入，修 success-only+last-wins 低估≤3x）② `coerce` 删手写分支统一 `MAPPER.convertValue`（修字符串数字→CCE/越界静默/boolean 误判）③ 重名 `@Tool` 去重（防 B3 后重复 ToolSpecification 名）④ `SafeToolExecutor` 中断标志恢复（防 defeat REL-1）。补 6 测试；全仓 verify 绿。另补 **Spring 预算对齐**：`callLlm` 每真实调用 `recordBudgetSpend`（复刻 LC4j 语义，防 schema 重试低估；只用 recordBudget 防与 advisor 双计）。**#5 语义拍板：per-workflow budget 为「记账/告警（非阻断）」**——运行中不中止，硬性防护由提交前 `WorkflowSubmissionGuard`（422）承担，文档措辞已统一
> - **档 1 生产接线（2026-08-13，真实 LLM 端到端）**：评审残留「demo-api/starter 从不构造真实适配器 → C1/B1/B3 部署未生效」——demo-api 加 `agentflow-adapters-langchain4j` 依赖 + `ApiConfig.nodeRegistry` 加**真实 agent 条件装配**：`agentflow.real.enabled=true` 且 env `DEEPSEEK_API_KEY` 存在时，`OpenAiChatModel`（OpenAI 兼容 → DeepSeek，`deepseek-chat`）→ `LangChain4jAgentAdapter`（注入 metrics/model/schemaValidator 全配）→ `NodeRegistry.register(agentflow.real.agent:deepseek, real)`；**其他 agent 名仍回落 mock fallback**（向后兼容，mock 测试全绿）。**凭证只从 env 读，禁止硬编码**。新增 Failsafe IT `DeepSeekE2eIT`（demo-api）：DSL→BspEngine→真实适配器→DeepSeek 2 节点串行逐级传参，断言真实 content 非空 + `metrics.totalCost()>0`（真实 token）；`assumeTrue(DEEPSEEK_API_KEY)` 门控——**本地无 key 自动跳过 verify 不红**。**✅ 已用真实 DeepSeek key 跑通**（`Tests run: 1, Failures: 0`，log 真实 LLM 调用）
> - **C3+C4 收尾（2026-08-13，residual 全闭环）**：① **C3 默认脱敏**——两适配器不注入 redactor 时默认 `PromptRedactionFilter::redact`（原 identity 让 trace 摘要不脱敏），sk-/Bearer/手机号/身份证 默认脱敏，可显式覆盖 ② **C4 per-node 工具过滤**——两适配器都读 `input.tools()`：LC4j `collectTools` 方法级过滤注册、Spring `callLlm` bean 级过滤（Spring `spec.tools` 按对象注册的粒度差异，记此）；空=全部向后兼容。测试各 +2（LC4j 33 / Spring 21）；全仓 verify 10 模块绿 + JaCoCo 达标。**residual 全部闭环 ✅（B1/B2/B3/C1/C2/C3/C4/M2）**
> - **档 A 可观测/分布式环境落地（2026-08-14，`54e5415`，本机 Docker 实跑验证通过）**：`docker-compose.yml` 新增 3 服务——① `kafka`（`apache/kafka:3.9.2`，KRaft 免 Zookeeper，bitnami/kafka 已弃用 tags 空）走 `--profile distributed`（v1.1 分布式）② `prometheus`（`prom/prometheus:v2.53.0`）+ `grafana`（`grafana/grafana:11.1.0`）走 `--profile observability`（档 A 可观测）。配套 `deploy/prometheus/prometheus.yml`（抓 `host.docker.internal:8080/actuator/prometheus`，`extra_hosts: host-gateway` 回宿主机抓 demo-api）+ `deploy/grafana/provisioning/`（datasource uid=`DS_PROMETHEUS` 对齐 dashboard 模板变量 + `dashboards.yml` 从源文件挂载 `/var/lib/grafana/dashboards` 自动加载，**不复制不漂移**）。验证：5 容器健康（PG/Redis/Kafka/Prometheus/Grafana）+ Kafka 建 topic 成功 + Grafana datasource + AgentFlow dashboard 自动装配——**ROADMAP 档 A「Grafana 真实部署验证」✅ 闭环**，v1.1 分布式模式 Kafka 环境就位（引擎侧 R18③/R19 仍未动）
> - **两处 review 收尾（2026-08-14，`08ec928`）**：① `api.ts` `resolveApiKey` 返回值加 `.trim()`——VITE_API_KEY 带首尾空白时守卫 `envKey.trim()` 通过但返回未 trim key，后端 SHA-256 空白改变哈希 → spurious 401 ② `docs/GRAFANA.md` 失败率 PromQL `status="FAILED"` → `"failed"`（对齐 `AgentFlowMetrics.STATUS_FAILED` 小写；照抄大写匹配 0 条、面板为空）
> - **v2 条件分支（2026-08-14，feat/v2-conditional-branching，U1–U8 全落地，ce-brainstorm + ce-doc-review + ce-plan + ce-work 全流程）**：把 v1「静态 DAG + BSP 预分层」扩成「无环 + 运行时路由」，兑现 KTD-9 与 `02-requirements.md` 明列的 v2 能力——回应最尖锐面试追问「你 v1 只做静态 DAG，动态路由怎么办？」。核心机制：① `EdgeDefinition` 加可选 `when` 谓词、`NodeDefinition` 加可选 `on_error: goto`（U1）② 新增 `PredicateEvaluator`（复用 `SpelPromptResolver` 的 hardened `SimpleEvaluationContext`，表达式→boolean；求值错误/非 boolean 结果按 Fatal，只有成功求值为 false 才「不命中」，KTD-2 安全不放松）（U2）③ `SemanticValidator` 校验条件边/on_error + `WorkflowDefinition.allEdges()` 把 on_error 作为隐式边纳入环校验与分层（纯 cleanup 节点正确分层到触发节点之后，不误拒）（U3）④ **BSP 执行模型用「可达性剪枝」而非放弃 BSP**：静态图仍预分层，每层只跑「可达」节点，被路由切断的下游标 SKIPPED；`resolveTakenTargets` 按声明序求值 `when` 取第一条 true（或默认边），无命中且无默认边 → Fatal「无分支命中」（U4）⑤ `on_error` 逆转「任一失败即 abort」不变量——只对声明 on_error 的节点开例外，失败跳 cleanup、正常下游 SKIPPED、on_error 目标自身失败不二次跳转（U5）⑥ trace 记录路由决策 + SKIPPED 节点 + `STATUS_FALLBACK` 三终态（U6）⑦ **checkpoint 只持久化路由决策**（已走边 from→to，KTD-5 单一真相源），恢复期用「源节点 + 已走边」BFS 重算可达集（纯 fan-out 无条件边走、路由节点按已走边、on_error 按已走边），不复活 SKIPPED、不重复计费（U7）⑧ `demo-conditional` 端到端（路由+汇合+on_error 兜底）（U8）。**全仓 `mvn verify` 11 模块绿 + JaCoCo 达标**，core 279 tests。**ce-doc-review（2026-08-14，5 persona）13 条 findings 全部闭环**——关键修正：SpEL 复用声明纠错（`SpelPromptResolver` 是字符串模板替换器、非布尔求值器）、「最小增量」改为「两处深引擎改动」、谓词求值错误≠false、on_error「下游」=静态图可达性 + cleanup 节点不误拒、checkpoint 只存路由决策不存 SKIPPED 态、成功标准补 AE7/AE8/AE9（双 channel join / 恢复不复活 / 谓词错误可诊断）。**面试叙事**：动态路由牺牲执行路径确定性、checkpoint 路由决策重放把它买回来——「BSP 怎么在动态路由下保留」一句话讲清。
> - **谓词 context/inputs 引用（2026-08-14，跨节点路由）**：`PredicateEvaluator` 根对象从 `{output}` 扩到 `{output, context, inputs}`（对齐 `SpelPromptResolver` 的 channel/入参视图），`when` 谓词现可写 `context.<channel>`（引用上游节点输出）与 `inputs.<key>`（引用工作流入参）——闭合 agent-native review 标的「路由表达力 < prompt 表达力」context-parity 缺口。恢复期 `inputs` 不可得传空（原入参未持久化，记此）。测试 +4（PredicateEvaluator context/inputs + 引擎跨节点/入参路由）；全仓 verify 11 模块绿。
> - **ce-code-review 修复 + 3 收敛（2026-08-14，已 push）**：`db1eb99`（4 P1：Postgres 路由持久化 + 恢复期 on_error BFS/三终态/持久化顺序 + 谓词 catch 范围）+ `b648d1a`（边键 helper + execute/recover 循环抽共享 + fallback 指标断言）。
> - **档 A 真 PG verify 实跑绿（2026-08-17）**：本地起 `postgres:16-alpine` 容器，全量 `mvn verify` 11 模块绿 + JaCoCo 达标，`PostgresCheckpointManagerIT` **真 PG 实跑**（3 用例非跳过，Flyway 4 迁移 validated）。真 PG 暴露两处「测试环境 vs 生产环境」差异缺陷（mock/H2/CI 全新容器永远发现不了）：① IT 数据污染——真 PG 持久化（pg-data 卷）测试间数据残留互渗，`@BeforeEach` 按 `it-%` 前缀清理 4 张关联表（routing_decisions/checkpoints/node_outputs/executions，先子表后父表）② H2 排序 flaky——测试靠 `now()` 毫秒内碰撞运气排倒序，快速连续插入同时间戳 → `ORDER BY created_at DESC` 顺序不确定，`insert()` 显式传 `secondsAgo` 用 `DATEADD` 错开时间戳。**ROADMAP 档 A 全部闭环**（Grafana 部署 + 真 PG verify），剩档 C 面试口径。
> - **v2 循环/回边（2026-08-17，feat/v2-loop-backedge，U1–U7 全落地 + ce-code-review 10 评审闭环，本地未 push）**：把「无环 + 运行时路由」再扩成「有界有环」，兑现条件分支 brainstorm 明列的「循环/回边 = 更远的差异化能力」。核心：① DSL 回边 `loop: true` + `max_iterations`（U1）② 有界环校验（回边三件套 when+上限、回边方向 target≤source、喂回 channel 非 OVERWRITE、静态图去回边无环，U2）③ 回边豁免分层（U3）④ **BspEngine 外层迭代轮次循环 + 双 active 集合**（active 前向 / nextActive 回边目标累积）+ `resolveTakenEdges` 返回边限定目标按 loop 分派 + 终止双保险（per-loop max_iterations + 引擎 maxTotalRounds=1000 硬上界，U4）⑤ checkpoint round 维度（CheckpointManager 带 round 的 default 方法委托 round=0 向后兼容 + NodeOutputStore/BarrierCheckpoint 加 round + InMemory/Postgres 唯一约束加 round + V5 迁移，U5）⑥ 恢复 round 维度（ExecutionState 加 round + RecoveryProtocol 按 (round, superStep) 定位 + recoverAndExecute 轮次转换检测「该轮回边命中→round++ 从层 0 续跑」+ 恢复后继续迭代，U6）⑦ demo-loop 反思循环端到端（draft→critique 回边→finalize 退出，U7）。**核心洞察**：回边 = 条件边 + loop 标记 + 指向更早节点，`resolveTakenTargets` 求值逻辑零改动复用；「BSP 怎么在循环下保留」= 静态分层豁免回边 + barrier 不变，代价是环上节点每轮重执行、checkpoint 轮次重放买回确定性。**全仓 `mvn verify` 12 模块绿 + JaCoCo 达标**，core 310 tests。ce-brainstorm + ce-plan + ce-doc-review（4 reviewer 14 findings 全闭环，含 2 P1），**ce-code-review（10 reviewer，`15537dc`，2 P1 全在恢复路径：mid-round 崩溃丢 pending 迭代 + final-barrier 后整体重跑双计费）**。**面试叙事**：动态路由→迭代收敛追问链闭环，「循环需动态 ready-set，我用双 active 集合 + 迭代轮次表达，checkpoint 轮次重放买回确定性」。
> - **v1.1 Kafka 异步分发（2026-08-18，ce-plan + ce-doc-review 自审后 ce-work U1/U2 落地 + ✅ U3 真 Kafka E2E 落地）**：KTD-8 把 v1 的「本地 VT 异步执行」升级为 Kafka 提交/执行解耦。**单 JVM 语义**（生产者+消费者同应用，真跨节点 read-after-write 延后）。① `agentflow-api` 抽出 `WorkflowExecutionService`（RUNNING→execute→SUCCESS/FAILED + 定义瞬时重试）+ `WorkflowDispatcher` 接口（默认 `LocalVirtualThreadDispatcher` 等价 v1 行为）；`WorkflowController` 重构——新 9-arg @Autowired 为唯一注入点（旧 8-arg 降级委托，Spring 单 @Autowired 约束），submit **和 retry** 都走 dispatcher（消除 submit 走 Kafka / retry 走本地的双路径漂移）。② 新模块 `agentflow-kafka-starter`：`KafkaWorkflowDispatcher`（producer）+ `KafkaWorkflowConsumer`（@KafkaListener，**幂等终态跳过**防重放重复计费 + 执行错误标 FAILED 防 orphan PENDING）+ `KafkaAgentFlowAutoConfiguration`（`agentflow.kafka.enabled` 属性门控，对齐 `agentflow.real.enabled`，避免 classpath 静默替换执行路径）。**实现注**：spring-kafka 4.1 的 `JsonSerializer/JsonDeserializer` 已废弃（KTD-E 兼容冒烟抓到）→ 改 **String 承载 JSON**（StringSerializer + 项目 ObjectMapper，wire 格式可控）。JaCoCo skip（wiring 型 starter，对齐 demo 模块）。测试：api 65（含新 `WorkflowExecutionServiceTest` 4）+ kafka-starter 5 全绿。**✅ U3 已落地**：`KafkaDispatchE2eIT`（真 Kafka `localhost:9092` 门控 @SpringBootTest：dispatch→consumer→BspEngine→SUCCESS + 重放幂等不重跑 + 连续双工作流，3/3 实跑绿；无 Kafka 整类跳过不红）+ consumer factory 补 `auto.offset.reset` 装配面（KTD-D；**默认 `earliest`**——任务队列语义，新消费组订阅前 produce 的消息不丢；消费者幂等终态跳过故无双计费，reliability review P1）+ demo-api 接 kafka-starter（`agentflow.kafka.enabled` opt-in，本地 dispatcher 默认不变）。live 运行命令见 ROADMAP §3。**✅ ce-code-review 10 评审闭环（2026-08-18）**：应用 3 个 P1/P2 生产缺口——Kafka 模式 retry 复位 PENDING（原被终态跳过吞掉，3 reviewer conf 100）、消费端未 staged 任意 id 守卫、auto.offset 默认 earliest；E2E replay 改 barrier 确定性断言。全仓 verify 绿，review 细节见 `03-review-findings.md`。


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
- **文档随开发同步（强制）**：每个 feature / bug fix / ce-code-review 结果落地后，**同步更新** `docs/developer-notes/`（01 选型 / 02 坑 / 03 review 发现 / 04 术语 / 05-06 批次）+ `docs/residual-review-findings/`（review 残留项标 ✅）。尤其 review 抓到的 bug（哪怕小）都要进 03——「测试绿 ≠ 生产生效」这类教训是简历/面试核心弹药。不得留到以后补。
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
