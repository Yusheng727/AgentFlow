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

## 已验证（2026-08-07，无 Docker/Grafana，静态 + 单元）

1. **JSON 合法**：node `JSON.parse` 通过 —— title「AgentFlow 可观测性总览」、6 面板、`DS_PROMETHEUS` 模板变量。
2. **指标名防漂移**：`GrafanaDashboardMetricAlignmentTest`（agentflow-starter test）通过——面板 PromQL 引用的 5 个指标族在 `AgentFlowMetrics` 常量中全部存在，Java 常量改名后本测试会失败。

## 已知缺口（未接线，需 ops/feature 决策）

**App 目前不暴露 `/actuator/prometheus`**：
- `demo-api/ApiConfig.meterRegistry()` 硬编码 `new SimpleMeterRegistry()`，且 `agentflow-starter` 未引入 `micrometer-registry-prometheus` / `spring-boot-starter-actuator`。
- 因此即便部署 Grafana + Prometheus，也**采集不到指标**（面板全空）。
- 要做：给可运行 server（demo-api 或生产接入方）加 `micrometer-registry-prometheus`（+ actuator），并把 MeterRegistry 换成 Prometheus 形态，使 `GET /actuator/prometheus` 返回 5 个指标族。这是功能级改动，未在本轮落地。

## 在真实 Grafana 环境验证（有 Docker/Grafana/Prometheus 时按此做）

```bash
# 1. 启动 Prometheus + Grafana
#    采集目标指向运行中的 agentflow app（http://<app>:8080/actuator/prometheus）

# 2. 起 agentflow app（demo-api），跑几个工作流产生指标（mock 模式即可触发 AgentFlowMetrics）
cd demo-api && mvn -s ../settings.xml spring-boot:run

# 3. Prometheus 里确认指标族被采集
#    http://prometheus:9090/api/v1/label/__name__/values 应含
#    agentflow_workflow_executed_total / agentflow_node_duration_seconds_bucket / ...

# 4. Grafana import dashboard JSON
#    数据源选 Prometheus（Fill DS_PROMETHEUS 模板变量）

# 5. 验收：6 面板均有非空数据；节点耗时面板能看到 P50/P95/P99 曲线
```

> **前置**：先完成上面的「已知缺口」接线，否则第 1–3 步采集不到数据。
