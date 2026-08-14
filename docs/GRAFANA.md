# Grafana 可观测性面板 — 部署与验证

> 本文档说明 `agentflow-starter/src/main/resources/grafana/agentflow-dashboard.json` 的面板、目前验证状态，以及如何在真实 Grafana 环境 import 并确认数据落地。
> 最后更新：2026-08-14（compose 服务落地，档 A 部署验证已闭环）

## 面板是什么

6 面板（Prometheus 数据源，模板变量 `DS_PROMETHEUS`）：

| 面板 | 数据来源（PromQL 指标族） |
|:---|:---|
| 工作流执行趋势（按 status） | `agentflow_workflow_executed_total` |
| 节点耗时 P50/P95/P99 | `agentflow_node_duration_seconds_bucket`（Timer + percentile histogram） |
| Token 消耗 Top10 | `agentflow_tokens_consumed_total` |
| LLM 成本按 model | `agentflow_workflow_cost_estimated_total` |
| 预算超限 + 窗口总成本 | `agentflow_workflow_cost_budget_exceeded_total` |
| 失败率 | `agentflow_workflow_executed_total{status="failed"}` |

指标由 `com.agentflow.observability.AgentFlowMetrics`（agentflow-core）注册。Micrometer→Prometheus 命名转换：点号转下划线、Counter 加 `_total`、Timer 以秒为单位并加 `_seconds_bucket`。

## 已验证（2026-08-07，无 Docker/Grafana，静态 + 本地起服）

1. **JSON 合法**：node `JSON.parse` 通过 —— title「AgentFlow 可观测性总览」、6 面板、`DS_PROMETHEUS` 模板变量。
2. **指标名防漂移**：`GrafanaDashboardMetricAlignmentTest`（agentflow-starter test）通过——面板 PromQL 引用的 5 个指标族在 `AgentFlowMetrics` 常量中全部存在，Java 常量改名后本测试会失败。
3. **Prometheus exporter 已接线 + 本地起服验证**：demo-api 加 `micrometer-registry-prometheus` + `spring-boot-starter-actuator`，移除手写 `SimpleMeterRegistry`（交 Spring Boot 自动配置成 `PrometheusMeterRegistry`），`application.yml` 暴露 `prometheus` 端点。本地 `spring-boot:run` 起服后 `GET /actuator/prometheus` → **HTTP 200 + JVM/系统指标**（192 行）确认 exporter 通路正常。

## 已闭环（2026-08-07）：U7 指标挂钩引擎 + exporter 接线 + mock token/成本记账，本地端到端验证

之前缺口（`recordWorkflowExecuted`/`recordNodeDuration` 引擎从未调用）已修复：
- **BspEngine 注入可空 `AgentFlowMetrics`**（新 6-arg 构造，旧构造器委托 null 向后兼容）：工作流完成记
  `workflow.executed{status}`（success/failed，`outcomeRecorded` 防漏记/防双记），每节点完成记
  `node.duration{agent}`（含重试，PercentileHistogram 供 P50/P95/P99）。
- **MockAgentFunction 模拟 token/成本**（`recordTokens` 供 mock 模式用）：按 prompt/响应长度模拟确定性
  token 数 → `tokens.consumed{agent,model}` + `cost.estimated{model}`；注入预算阈值 → 每次记账后
  `checkBudget` 触发 `budget_exceeded`。demo-api 注入 `gpt-4o-mini` + 演示阈值。
- **demo-api 起服端到端验证**：提交工作流后 `/actuator/prometheus` 现返回全部 5 类指标族：
  `workflow_executed_total`、`node_duration_seconds_bucket/count/max/sum`、
  `tokens_consumed_total{agent,model}`、`cost_estimated_total{model}`、`cost_budget_exceeded_total`。
- **6 面板现均可有数据**。

## 真实 LLM 路径指标证据（2026-08-13，档 1 真实 DeepSeek 端到端）

> demo-api `agentflow.real.enabled=true` + env `DEEPSEEK_API_KEY` 起服，REST POST 一条 `agent: deepseek` 的 2 节点串行工作流，`/actuator/prometheus` 抓到**真实（非 mock 模拟）指标**：

```
agentflow_workflow_executed_total{status="success"} 1.0
agentflow_node_duration_seconds_sum{agent="deepseek"} 20.467 (count=2)
agentflow_node_duration_seconds_max{agent="deepseek"} 11.84
agentflow_tokens_consumed_total{agent="deepseek",model="deepseek-chat"} 3275.0
agentflow_workflow_cost_estimated_total{model="deepseek-chat"} 7.6804E-4
```

意义：`tokens.consumed`/`cost.estimated` 走到**真实 LLM usage**（非 mock 4 字符≈1 token 模拟），`node.duration` 是真实每节点耗时——Grafana 的 Token/成本/耗时面板在有真实 DeepSeek 流量时可填真实数据。档 1 接线 / 结果见 `developer-notes/00-interview-arsenal.md`「档 1 真实 DeepSeek 端到端」。

## 真实 Grafana 部署已落地（2026-08-14，`54e5415`，本机 Docker 实跑验证通过）

`docker-compose.yml` 已加 `--profile observability` 两个服务 + 自动装配（档 A 闭环）：

- **prometheus**（`prom/prometheus:v2.53.0`）：挂载 `deploy/prometheus/prometheus.yml`，采集目标
  `host.docker.internal:8080/actuator/prometheus`（经 `extra_hosts: host-gateway` 回宿主机抓 demo-api）。
- **grafana**（`grafana/grafana:11.1.0`）：`deploy/grafana/provisioning/` 两文件自动装配——
  `datasources/prometheus.yml` 固定 uid=`DS_PROMETHEUS`（对齐 dashboard 模板变量，免手动选数据源）+
  `dashboards/dashboards.yml` 从源文件挂载的 `/var/lib/grafana/dashboards` 自动加载 `agentflow-dashboard.json`
  （**源文件直接挂载，不复制、不漂移**）。

```bash
# 1. 起可观测栈（Grafana http://localhost:3000，admin/admin，dashboard 自动加载）
docker compose --profile observability up -d
# 2. 起 agentflow app（demo-api）产生指标
cd demo-api && mvn -s ../settings.xml spring-boot:run
# 3. 验收：Grafana datasource「Prometheus」+「AgentFlow」dashboard 自动装配；6 面板均有数据
```

验证（本机 Docker 实跑）：PG/Redis/Kafka/Prometheus/Grafana 5 容器健康；Kafka 建 topic 成功；Prometheus
抓取目标正常；Grafana datasource + AgentFlow dashboard 自动装配成功。
