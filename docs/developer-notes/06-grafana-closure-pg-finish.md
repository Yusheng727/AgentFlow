# Grafana 可观测全闭环 + PG 收尾（2026-08-07 批次）

> 覆盖 05 之后的另一批 follow-up：补齐「Grafana 数据通路」缺口 + Postgres 收尾。05 是 #9–#12（REST server 门面），本文件是**让 Grafana 面板真正有数据** + **真 PG 验证**的批次。全部 `mvn verify` 9 模块绿（含 UI `npm run build`/`npm test`）。

---

## 0. 背景：为什么会有这一批（Situation）

05 把 REST 门面跑通了，但新暴露一个更深的缺口：**Grafana 面板仍是空的**。拆开是三层没打通：

- **引擎从不记指标**：U7 的 `AgentFlowMetrics.recordWorkflowExecuted`/`recordNodeDuration` 只有测试调用，`BspEngine` 从未真正调用 → `/actuator/prometheus` 里根本没有 `workflow.executed`/`node.duration` 样本，Grafana 前两个面板（执行趋势 / 节点耗时）永远空。
- **mock 模式不出 token/成本**：mock 渐变不发真实 LLM，`tokens.consumed`/`cost.estimated` 无来源 → Grafana 的 Token/成本面板也空（真实 LLM 路径又依赖 API key）。
- **没有 Prometheus 出口**：demo-api 未接 micrometer-registry-prometheus + actuator，没有 `/actuator/prometheus` 端点可被 Prometheus 抓。
- 另有两项 Postgres 收尾挂着：`listByCreatedBy` 只有 InMemory 版（05 标注"PG 待补"）、`PostgresCheckpointManager` 群体（Semaphore/ON CONFLICT/JSONB/Flyway）零测试覆盖。

---

## 1. U7 指标挂钩引擎（84b7142）——修 Grafana 数据通路缺口

**Situation**：5 个指标活生生地定义在 `AgentFlowMetrics`，但引擎路径一个都不写。
**Task**：让 `BspEngine` 在执行时真实记录 `workflow.executed` + `node.duration`。
**Action**：
- `BspEngine` 注入可空 `AgentFlowMetrics`（6-arg 新构造，旧构造委托 null 向后兼容）
- 完成路径记 `workflow.executed{status=success/failed}`（`outcomeRecorded` 防漏记/防双记 + finally 兜底）
- 每节点记 `node.duration{agent}`（含重试；`publishPercentileHistogram` 暴露 `_bucket` 序列，供 P50/P95/P99）
- demo-api ApiConfig `bspEngine` 注入 metrics；`BspEngineMetricsTest` 3 个（成功/失败/noop）
**Result**：`/actuator/prometheus` 现返回 `agentflow_workflow_executed_total{status=...}` + `agentflow_node_duration_seconds_bucket/count/max/sum`。

**教训**：接口有了 ≠ 数据通路通了。指标/钩子只 define 不 hook，面板就是空的——验收"Grafana 有数据"要靠端到端起服验证，不是看测试里记没记。

## 2. mock 模式 token/成本记账（e56c4de）——Grafana Token/成本面板数据源

**Situation**：mock 不发 LLM，`tokens.consumed`/`cost.estimated` 无来源 → Token/成本面板空。
**Task**：让 mock 也出确定性的 token + 成本，供面板。
**Action**：
- `MockAgentFunction` 注入可空 `AgentFlowMetrics` + model + 预算阈值
- 按 prompt/响应长度模拟**确定性** token（~4 字符 ≈ 1 token），经 `recordTokens` 记 `tokens.consumed{agent,model}` + `cost.estimated{model}`
- 可选 `checkBudget` 触发 `budget_exceeded`（`budgetThresholdUsd`，mock 专用的全局阈值——R10 后升级为 per-workflow，见 `02.5` 设计决策）
- `recordTokens` 本设计供 mock 用；null 兼容旧行为。demo-api 接 gpt-4o-mini + 演示阈值；`MockAgentFunctionTest` +3
**Result**：6 个 Grafana 面板现均有数据（engine 通路 + mock 记账双补齐）。

## 3. Prometheus exporter 接线 + Grafana 验证文档（b266ec0 + cd96cf2 + 75fe04f）

**Situation**：无 `/actuator/prometheus` 端点，Prometheus 无从抓，Grafana 无数据源。
**Action**：
- demo-api 加 `micrometer-registry-prometheus` + actuator，**移除手写 SimpleMeterRegistry**（统一交给 Boot 自动装配的 PrometheusMeterRegistry）
- 本地起服验证 `/actuator/prometheus` HTTP 200 + JVM 指标
- `docs/GRAFANA.md`：部署与验证文档——已验证部分如实记录 + 标记 exporter 缺口及后续（**诚实记录缺口**是这篇的原则）
- `cd96cf2` 时仍标注 exporter 未接线 → `b266ec0` 接线后 `75fe04f`/`8839c81` 更新状态闭环
**Result**：exporter 端点可用，Grafana 6 面板可抓数据。

## 4. PostgresCheckpointManagerIT 真 PG 集成测试（6d655a1）

**Situation**：U5「PG 零测试」的 residual（03-review-findings 里明确记录）——Semaphore/ON CONFLICT/JSONB/Flyway 全未覆盖。
**Action**：Failsafe `*IT` 集成测试——CI 有真 PG 全跑 / 本地无 PG 跳过。覆盖 `listByCreatedBy` 真 PG + checkpoint/元数据往返。
**Result**：PG 路径有集成测试门槛，CI 把关。

## 5. Postgres listByCreatedBy（821f251）——兑现 05 的「待补」

**Situation**：05 明确标注"Postgres 版 `listByCreatedBy` SQL 待补，现 default 返回空"（画板只有 InMemory 版）。
**Task**：补全 PG 版，语义与 InMemory 对齐。
**Action**：
- `PostgresCheckpointManager.listByCreatedBy`：从 `workflow_executions` 按 `created_by` 过滤（空则返回全部，兼容 U5 未设 created_by）+ `created_at` 倒序
- 抽 `SELECT_EXECUTION_RECORDS`/`BY_CREATOR` + `EXECUTION_RECORD_MAPPER` 为 package-private 单真相源
- 测试 `PostgresCheckpointManagerTest` 4 个：生产 Flyway 含 PG 专属类型（TIMESTAMPTZ/JSONB）无法跑 H2 → 用 H2 建兼容 `workflow_executions` 表跑真实 SQL+mapper，验证过滤/倒序/status 归一/空表/Null createdBy
**Result**：看板在 PG 路径也能按创建者列本人工作流，与 InMemory 语义一致。
**教训**：标注"待补"的 TODO 要在兑现 commit 时同步勾掉——否则文档与交付脱节（这次就是）。

---

## 复盘（这块是「可观测性怎么做」的完整闭环）

**一句话**：「我把 Grafana 从『面板定义好了但全是空』推到『6 个面板全有数据』——拆出三层缺口：指标只在测试里记、mock 不出 token/成本、没有 Prometheus 出口。逐个闭环后 `/actuator/prometheus` 端到端起服验证有样本。」——这是「知道面板会空 → 主动追根因到数据通路 → 端到端验证」的完整工程链条，比只列指标名高级。

**深挖点**：
- 为什么只 define 不 hook 面板会空？（指标管线：定义 → 引擎钩子 → exporter → Prometheus 抓 → Grafana，任一段断就空）
- `publishPercentileHistogram` 为什么？（暴露 `_bucket` 序列才能 histogram_quantile 算 P50/P95/P99）
- 真 PG 集成测试怎么落地？（Failsafe `*IT`，CI 有 Docker PG 全跑、本地无 PG 跳过；H2 建兼容表跑真实 SQL）
