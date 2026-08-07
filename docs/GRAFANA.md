# Grafana 可观测性面板 — 部署与验证

> 本文档说明 `agentflow-starter/src/main/resources/grafana/agentflow-dashboard.json` 的面板、目前验证状态，以及如何在真实 Grafana 环境 import 并确认数据落地。
> 最后更新：2026-08-07

## 面板是什么

6 面板（Prometheus 数据源，模板变量 `DS_PROMETHEUS`）：

| 面板 | 数据来源（PromQL 指标族） |
|:---|:---|
| 工作流执行趋势（按 status） | `agentflow_workflow_executed_total` |
| 节点耗时 P50/P95/P99 | `agentflow_node_duration_seconds_bucket`（Timer + percentile histogram） |
| Token 消耗 Top10 | `agentflow_tokens_consumed_total` |
| LLM 成本按 model | `agentflow_workflow_cost_estimated_total` |
| 预算超限 + 窗口总成本 | `agentflow_workflow_cost_budget_exceeded_total` |
| 失败率 | `agentflow_workflow_executed_total{status="FAILED"}` |

指标由 `com.agentflow.observability.AgentFlowMetrics`（agentflow-core）注册。Micrometer→Prometheus 命名转换：点号转下划线、Counter 加 `_total`、Timer 以秒为单位并加 `_seconds_bucket`。

## 已验证（2026-08-07，无 Docker/Grafana，静态 + 本地起服）

1. **JSON 合法**：node `JSON.parse` 通过 —— title「AgentFlow 可观测性总览」、6 面板、`DS_PROMETHEUS` 模板变量。
2. **指标名防漂移**：`GrafanaDashboardMetricAlignmentTest`（agentflow-starter test）通过——面板 PromQL 引用的 5 个指标族在 `AgentFlowMetrics` 常量中全部存在，Java 常量改名后本测试会失败。
3. **Prometheus exporter 已接线 + 本地起服验证**：demo-api 加 `micrometer-registry-prometheus` + `spring-boot-starter-actuator`，移除手写 `SimpleMeterRegistry`（交 Spring Boot 自动配置成 `PrometheusMeterRegistry`），`application.yml` 暴露 `prometheus` 端点。本地 `spring-boot:run` 起服后 `GET /actuator/prometheus` → **HTTP 200 + JVM/系统指标**（192 行）确认 exporter 通路正常。

## 已知缺口（U7 指标未挂钩引擎，需 feature 级实现）

**exporter 通了，但运行工作流后 `/actuator/prometheus` 仍无 `agentflow_*` 指标族**。原因：
- `AgentFlowMetrics.recordWorkflowExecuted` / `recordNodeDuration` **只有单元测试调用，引擎（BspEngine/NodeExecutor）从未挂钩**——U7 设计了指标类但没把「工作流完成 / 节点耗时」写进引擎。
- mock 路径不经 `TokenCountingAdvisor`，故 token/成本也不记。
- 结论：即便部署 Grafana/Prometheus，**工作流执行也不会产生 agentflow 指标，面板全空**。
- 要做（U7 完成项）：给 BspEngine 注入可空 `AgentFlowMetrics`，在工作流完成（success/failed）调 `recordWorkflowExecuted`、每节点完成调 `recordNodeDuration(agent, nanos)`，并补引擎层测试 + JaCoCo 覆盖。**未在本轮实现**（核心引擎改动，独立 feature）。

## 在真实 Grafana 环境验证（有 Docker/Grafana/Prometheus 时按此做）

```bash
# 前置：先完成上面「U7 指标挂钩引擎」，否则采集不到 agentflow 指标
# 1. 启动 Prometheus + Grafana，采集目标指向 http://<app>:8080/actuator/prometheus
# 2. 起 agentflow app（demo-api），跑几个工作流产生指标
cd demo-api && mvn -s ../settings.xml spring-boot:run
# 3. Prometheus label values 应含 5 个 agentflow_* 指标族
# 4. Grafana import dashboard JSON（数据源选 Prometheus，Fill DS_PROMETHEUS）
# 5. 验收：6 面板均有非空数据
```
