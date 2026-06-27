## Requirements

### 编排引擎核心（4 项）

- **R1.** 支持 YAML DSL 定义工作流。三种拓扑：串行（A→B→C）、并行（A→{B,C,D}→E）、混合拓扑（A→{B,C}→D→{E,F}→G），所有场景用同一套 YAML 语法。
- **R2.** BSP 执行模型：DAG 按最长路径分层为 super-steps，同一 super-step 内并行节点同时执行（Java 21 Virtual Threads），执行完毕后 barrier 同步，统一合并结果。并行写入冲突通过 channel 绑定的 Reducer 自动处理。
- **R3.** 每个 super-step 结束后自动 checkpoint 到 PostgreSQL。**节点级 checkpoint**（每个 Agent 完成后立即保存输出，避免 LLM 重复调用）+ **super-step barrier checkpoint**（统一合并所有输出）。引擎重启后从最新 checkpoint 恢复，已完成的节点跳过不重执行。
- **R4.** 每步独立容错：超时（全局默认 120s，每节点可覆盖）、失败自动重试（指数退避，初始 1s，最多 3 次）、所有重试耗尽后走 ErrorHandler 补偿逻辑。区分 transient error（可重试：网络超时、429限流）和 fatal error（不可重试：400 内容违规、参数错误）。

### Agent 集成（3 项）

- **R5.** 每个节点是一个 Agent，通过 `AgentFunction` 接口接入（`execute(AgentInput) → AgentOutput throws AgentExecutionException`）。v1 提供 `SpringAiAgentAdapter`（封装 ChatClient + Advisor Chain + @Tool 注册），v1.1 提供 `LangChain4jAgentAdapter`。
- **R6.** 节点间通过 `WorkflowContext` 传递数据。上游 Agent 输出按 channel 写入 context，下游 Agent 通过 SpEL 表达式 `${channelName.nested.field}` 引用。context 支持**类型声明**（`channels` 配置段）+ **Reducer 策略声明**（overwrite / concat / max / custom）。
- **R7.** 轻量 Supervisor 模式（v1 实现）：中央协调 Agent 通过 SpEL 引用多个上游 channel 输出进行汇总（本质是特殊化的 AgentFunction，不是引擎级能力）。v1 仅支持**静态 DAG + Super-step 层级**，不做运行时条件分支（v2 支持 `on_error: goto cleanupNode` 这样的动态跳转）。
  - **v1 实现细节**：Supervisor Agent 就是一个普通 `AgentFunction`，其 `prompt_template` 中通过 `${financeAnalysis}`, `${complianceCheck}`, `${reputation}` 引用上游节点输出。引擎本身不识别"Supervisor"概念，汇总逻辑完全由 Agent 的 prompt 驱动。

### 可观测性与调试（5 项）

- **R8.** 每个工作流执行产出一棵 `ExecutionTrace` 树：根节点=工作流级（workflowId, workflowName, startTime, endTime, status），子节点=每个 Agent 执行详情（nodeId, agentName, startTime, endTime, status, retryCount, tokensConsumed, outputSummary, errorMessage）。
- **R9.** HTTP trace dump 端点：`GET /api/workflows/{id}/trace` 返回完整执行追踪 JSON。
- **R10.** Micrometer 指标：工作流完成数/失败数（Counter）、各 Agent 步耗时分布（Timer, P50/P95/P99）、token 消耗按工作流/Agent 维度汇总（Counter）、**Workflow 级成本核算**（基于 token 数 + 模型单价，Counter + budget 告警）。
- **R11.** **Dry-run 模式**（新增）：`engine.dryRun(workflowName, inputs)` 不真正调用 LLM，走完整个 DAG 拓扑返回每步预期的 input/output schema。帮助开发者在不调 API 的情况下验证工作流结构。
- **R12.** **Diagnosis 端点**（新增）：`GET /api/workflows/{id}/diagnosis` 自动诊断常见问题（"Agent X 连续超时 3 次"、"SpEL 表达式解析失败"等）并给出修复建议。

### Mock 与版本管理（2 项，新增）

- **R13.** **Mock LLM 模式**：`@EnableAgentFlow(mockLlm=true)` 或 `agentflow.mock.enabled=true`，自动用 YAML 中配置的 mock_response 替代真实 LLM 调用。mock_response 支持按 channel 指定，让开发者本地零成本调试工作流。
- **R14.** **Workflow 版本管理**：YAML 顶层 `agentflow: {version: "1.0"}` 字段（SemVer）。Checkpoint 关联 workflow version。运行时如果 YAML 版本变更，**警告但不影响已运行实例**（旧版本实例按旧版本定义执行到结束）。

### 验证场景（3 项，差异化设计）

- **R15.** **主 Demo：供应商风险评估**（并行→汇总拓扑）。三个专家 Agent 并行分析财务/合规/声誉 → 综合评级 Agent 汇总。展示引擎的**并行执行 + 结果合并**能力。
- **R16.** **辅 Demo 1：合同审核流水线**（4 步链式串行拓扑）。合同解析 Agent → 法律风险识别 Agent → 合规建议 Agent → 最终报告 Agent。每步依赖上一步输出。展示引擎的**串行依赖链 + 上下文传递**能力。
- **R17.** **辅 Demo 2：投资分析决策**（双层 fork-join 混合拓扑）。2 个 Agent 并行收集基础信息（公司财报 + 市场数据）→ 1 个 Agent 串行可行性分析 → 2 个 Agent 并行（风险评估 + 收益预测）→ 最终投资裁决 Agent 汇总。展示引擎的**复杂拓扑组合**能力。

### 交付与部署（3 项）

- **R18.** 提供 `agentflow-spring-boot-starter`。接入路径：① 零基础设施模式（内存 Checkpoint，适合开发测试，pom + @EnableAgentFlow + 跑通 ② 生产部署模式（+ PostgreSQL 持久化）③ 分布式模式（+ Redis + Kafka，v1.1 路线图，v1 不支持））。
- **R19.** Docker Compose 一键拉起 v1 生产环境：编排引擎 + PostgreSQL + Redis。Kafka 延迟到 v1.1（v1 用内存 `@Async` + 数据库任务表做轻量替代）。
- **R20.** 提供 `agentflow-archetypes` 快速生成标准 Agent 骨架（含 prompt 管理、输出 schema、错误处理模板），降低 Agent 开发门槛。

### 安全（2 项，v4 新增）

- **R21.** **API 鉴权 + 工具级授权**：所有 REST 端点（`POST /workflows`、`GET /workflows/{id}/status`、`POST /workflows/{id}/retry`、`GET /trace`、`GET /diagnosis`、`GET /version-check`）必须经过鉴权。v1 采用简单 API Key + per-workflow ownership 校验（workflow_id 关联创建者，仅创建者可查 trace/诊断）。防止 IDOR——任意调用方枚举 workflow_id 读取他人 trace（含 LLM prompt、输出、业务数据）。**工具级授权（v4.3 新增）**：YAML 节点引用的 `@Tool` 必须在调用方的 tool allowlist 内（per-caller，v1 从 config/env 硬编码 `Map<ApiKeyHash, Set<ToolName>>`，v1.1 升级 DB 表 + 管理 API），YAML 解析期校验，引用未授权工具 → 403。防止持 key 者借工作流结果外泄敏感工具（财务数据库查询、合规 API 查询）数据。
- **R22.** **LLM 凭证与敏感数据管理**：① LLM API Key（OpenAI/DeepSeek/通义千问）通过环境变量/Spring Boot configprops 注入，**禁止写入 application.yml 或 Docker Compose 文件**；② LoggingAdvisor/StructuredLogger 对 prompt/response 做正则脱敏（Key 模式、手机号、身份证）；③ Checkpoint 的 `channel_values`、`input_params`、`result` 等 JSONB 字段若存敏感业务数据（供应商财务、合同），v1 文档标注"明文存储 + 访问受 R21 鉴权保护"，v1.1 评估列级加密。

### Deferred to Later Work（6 项）

- 运行时条件分支（v2）：Agent 输出动态决定下一个执行哪个 Agent
- 完整 Human-in-the-Loop 审批中间件（v2）：中断→外部审批→恢复执行
- Web 可视化工作流编辑器（v2）
- 多租户 SaaS 平台（v2+）
- LangChain4j Agent 适配器（v1.1）
- Kafka 异步任务分发（v1.1，用 @Async 替代）

### Outside This Product's Identity

- 不做模型训练/微调（编排已有 Agent，不训练新模型）
- 不做通用 BPM 工作流引擎（不是 Camunda/Flowable 的替代品）
- 不做 Agent 能力开发（Spring AI/LangChain4j 是 Agent 能力层）
- 不做 LLM API 网关/代理（不是 LiteLLM/Portkey 的替代品）

← 返回 [`00-overview.md`](./00-overview.md)
