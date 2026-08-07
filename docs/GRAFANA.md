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

## 已闭环（2026-08-07）：U7 指标挂钩引擎 + exporter 接线 + 本地端到端验证

之前缺口（`recordWorkflowExecuted`/`recordNodeDuration` 引擎从未调用）已修复：
- **BspEngine 注入可空 `AgentFlowMetrics`**（新 6-arg 构造，旧构造器委托 null 向后兼容）：工作流完成记
  `workflow.executed{status}`（success/failed，`outcomeRecorded` 防漏记/防双记），每节点完成记
  `node.duration{agent}`（含重试，PercentileHistogram 供 P50/P95/P99）。
- **demo-api 起服端到端验证**：提交工作流后 `/actuator/prometheus` 现返回
  `agentflow_workflow_executed_total{status=...}` + `agentflow_node_duration_seconds_bucket/count/max/sum`。
- 趋势 / 节点耗时(P50/P95/P99) / 失败率 面板现可有数据。**Token/成本面板需真实 LLM token 流量**（mock 无 token，属运行时行为，非代码缺口）。

## 在真实 Grafana 环境验证（有 Docker/Grafana/Prometheus 时按此做）

```bash
# 指标通路已就绪；Token/成本更完整展示需跑真实 LLM 路径（mock 只出 executed + node.duration）
# 1. 启动 Prometheus + Grafana，采集目标指向 http://<app>:8080/actuator/prometheus
# 2. 起 agentflow app（demo-api），跑几个工作流产生指标
cd demo-api && mvn -s ../settings.xml spring-boot:run
# 3. Prometheus label values 应含 5 个 agentflow_* 指标族（mock 下 2 个：executed/node.duration）
# 4. Grafana import dashboard JSON（数据源选 Prometheus，Fill DS_PROMETHEUS）
# 5. 验收：执行趋势 / 节点耗时 / 失败率面板有数据；Token/成本面板需真实 LLM 流量
```
