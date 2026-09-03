# AgentFlow v1 交付状态 · 剩余工作 · v2 路线图

> 本文档是 2026-08-07 对 v1 的交付盘点与 v2 边界的一手来源，供接手与口径自洽。
> 更新日期：2026-08-07（2026-08-10 更新：WorkflowSubmissionGuard + per-workflow budget 已交付，见档 B；2026-08-14 更新：档 A Grafana/Prometheus/Kafka 环境落地已部署验证，见档 A；2026-08-17 更新：真 PG `verify` 实跑绿，档 A 全闭环）。计划权威件：`docs/plans/agentflow/`（本文档不替代 plan，只做状态与路线的快照）。

---

## 1. v1 已交付（截至 2026-08-07，全部 `mvn verify` 绿 + 合 main + push）

| 板块 | 内容 | 验证 |
|:---|:---|:---|
| 15 个实现单元 U0–U14 | DSL(U1) / BSP 引擎(U2) / Spring AI 适配器(U3) / 容错(U4) / 两级 Checkpoint+Recovery(U5) / 调试(U6) / 可观测(U7) / 版本管理(U8) / Mock(U9) + 三 Demo(U10–U12) / Starter(U13) / API 安全(U14) | 8 模块 verify 绿 + JaCoCo 80% 门禁 |
| 后续任务 #9–#12 | 可运行 REST server / trace 带超步层号 / diagnosis 反序列化 / 看板列表端点 | verify 绿 |
| 提交守卫（档 B 收尾） | `WorkflowSubmissionGuard`：DAG 节点数 / 预估成本上界，超限 422 拒绝（06 OQ 安全缺口 #2） | verify 绿 + JaCoCo + 合 main（`a37d9dc`） |
| per-workflow 预算（档 B 收尾） | `budget_tokens`/`budget_cost` YAML 字段 → `WorkflowBudget` 累加器 → mock 记账触发 `budget_exceeded`（R10 告警语义） | verify 绿 + JaCoCo + 合 main（`4072b3e`） |
| UI | React 5 Tab + Vitest 单测（api 12 + Dashboard 4 + resolveApiKey 4 = 20） | `npm test` 绿 + build 绿 |
| Grafana 可观测**全闭环** | exporter 接线 + U7 指标挂钩引擎（workflow.executed / node.duration）+ mock token/成本记账（tokens.consumed / cost.estimated / budget_exceeded） | 本地起服验证 `/actuator/prometheus` 返回**全部 5 类指标族** |
| 工程收尾 | Postgres listByCreatedBy / PostgresCheckpointManagerIT（真 PG Failsafe）/ VITE_API_KEY 加固 / UI 入 CI | verify 绿 |
| CI/CD | GitHub Actions（Java verify + Sonar + Docker）+ UI job | — |

Grafana 6 面板现均有数据，指标通路 engine → exporter 端到端打通并本地验证。

---

## 2. v1 剩余工作（按"能否现在做"分三档）

### 档 A — 需环境（Deferred，代码/IT 已就位，只差实际环境）
- **Grafana 真实部署验证**：✅ **已交付**（2026-08-14，`54e5415`）——`docker-compose.yml` 加 `prometheus`+`grafana` 服务（`--profile observability`）+ `deploy/prometheus/prometheus.yml` + `deploy/grafana/provisioning/`（datasource uid=`DS_PROMETHEUS` 对齐模板变量 + dashboard 源文件挂载自动加载，不复制不漂移）；本机 Docker 实跑 5 容器健康 + Grafana datasource/dashboard 自动装配（见 `docs/GRAFANA.md`）。
- **真 PG `verify` 实跑绿**：✅ **已交付**（2026-08-17）——本地起 `postgres:16-alpine` 容器，全量 `mvn verify` 11 模块绿，`PostgresCheckpointManagerIT` **真 PG 实跑**（3 用例非跳过，Flyway 4 迁移）。真 PG 暴露两处测试环境差异缺陷并修复：① IT 数据污染（pg-data 卷持久化导致测试间残留互渗 → `@BeforeEach` 按 `it-%` 前缀清理 4 表）② H2 排序 flaky（`now()` 毫秒碰撞致 `ORDER BY created_at DESC` 不确定 → `insert()` 显式 `secondsAgo` 错开时间戳）。顺带验证真 PG 下 listByCreatedBy / checkpoint 往返端到端。

### 档 B — 真实未落地的 v1 代码缺口（可立刻做）
| 项 | 引用 | 现状 |
|:---|:---|:---|
| **WorkflowSubmissionGuard**（DAG 节点数 / token 成本上界，超限 422 拒绝） | 06 OQ `POST /workflows 无 DAG/token 预算上界`（security-lens, conf 75） | ✅ **已交付**（2026-08-10，`a37d9dc`）——`WorkflowSubmissionGuard`（api/security）+ 提交链 422`SUBMISSION_LIMIT`，节点数/预估成本超上界拒绝；demo-api 接 `agentflow.guard.*`；CLAUDE.md 已记 |
| **R20 agentflow-archetypes** | R20 | ⛔ **明确不做**（2026-08-10 决策）——声明但无实现单元交付；投入产出比低，v1 砍单 |
| **per-workflow 预算字段** `budget_tokens`/`budget_cost` | R10 | ✅ **已交付**（2026-08-10，`4072b3e`）——`AgentflowMeta` 加预算字段 + `WorkflowBudget`（observability，edge-triggered 累加器）+ BspEngine 穿线 AgentInput → mock 逐节点记账触发 `budget_exceeded`；无预算时回落全局阈值向后兼容 |
| **Reducer 冲突路径刻意演练**（overwrite/concat/max/custom 触发测试） | 06 OQ `Reducer 冲突无 demo` | ⛔ **明确不做**（2026-08-10 决策）——抽象已有单测覆盖各策略语义，冲突演示性价比低，v1 砍单 |

### 档 C — 项目叙事 / 对外讲述口径 ✅ 已定稿（2026-08-18）
- **30s/5min 自述稿已附** `07-sources-revision-interview.md`（主线「静态 DAG → 动态路由 → 迭代收敛 → 分布式解耦」，收口 13 条叙事追问：buy-vs-build / 七三开折算 / KTD-1 BSP vs Actor/CSP / @Tool 与 InterviewCoach 边界 / Spring AI 差异化 / KTD-3 防重复计费 / v1.1 Kafka 解耦）。
- API Key 完整签发/轮换 registry：现为 demo key + env `AGENTFLOW_API_API_KEYS` 追加，完整签发/轮换属 ops。

---

## 3. v1.1 路线图（介于 v1 / v2）

- ~~**`LangChain4jAgentAdapter`**（R5）~~ → ✅ **已交付**（2026-08-10，`f4651b2`，本地未推送）——新模块 `agentflow-adapters/langchain4j`（1.0.0 GA），依赖面仅 core + langchain4j 无 Spring AI，窄表面对齐（SpEL/ChatModel/@Tool 循环/TokenUsage/ErrorClassifier/trace/cancel）；KTD-7 可移植性约束实证
- **分布式模式**：Redis + Kafka（R18③ / R19，v1 用内存 @Async + DB 任务表轻量替代）——**Kafka 提交/执行解耦（v1.1 U1–U3）✅ 已交付**（2026-08-18，`feat/v11-kafka-e2e`，ce-code-review 10 评审闭环后 commit 到分支、未 push）：`agentflow-kafka-starter`（producer `KafkaWorkflowDispatcher` + consumer `KafkaWorkflowConsumer` + `KafkaAgentFlowAutoConfiguration` 属性门控）+ `KafkaDispatchE2eIT`（真 Kafka `localhost:9092` 端到端：dispatch→consumer→BspEngine→SUCCESS + 重放幂等）。demo-api 已接 kafka-starter（`agentflow.kafka.enabled` opt-in，默认本地 dispatcher 不变）。**单 JVM 语义**：producer/consumer 同应用；真跨节点 read-after-write 延后，Kafka 部署强化（topic ACL/SASL_SSL）与 R18③/R19 引擎侧分布式仍待后续。
  - **live 运行**（真 Kafka 起 `docker compose --profile distributed up -d`）：
    1. 起 demo-api（Kafka 派发）：`mvn -s settings.xml -pl demo-api spring-boot:run -Dspring-boot.run.arguments="--agentflow.kafka.enabled=true --spring.kafka.bootstrap-servers=localhost:9092"`
    2. 提交 2 节点串行工作流：`curl -X POST localhost:8080/api/workflows -H 'X-API-Key: demo-key-1234567890abcdef' -H 'Content-Type: application/json' -d '{"workflowName":"kafka-live","version":"1.0","yamlContent":"agentflow: {version: \"1.0\"}\nnodes:\n  - {id: step1, agent: mock, prompt_template: \"t1\", mock_response: \"a\"}\n  - {id: step2, agent: mock, prompt_template: \"t2\", mock_response: \"b\"}\nedges:\n  - {from: step1, to: step2}\n","inputs":{}}'` → 得 `workflowId`
    3. 轮询终态：`curl -s localhost:8080/api/workflows/<id>/status -H 'X-API-Key: demo-key-1234567890abcdef'` → 直至 `"status":"SUCCESS"`（经 Kafka topic `agentflow.workflow.executions` 派发）
    4. 指标：`curl -s localhost:8080/actuator/prometheus | grep agentflow_workflow` → `agentflow_workflow_executed_total{status="success"}` 计数 +1
  - **验证证据**：`KafkaDispatchE2eIT` 真 Kafka 实跑绿（Skipped: 0）；无 Kafka 时整类跳过不红（AE2）
- **工具级授权 DB 表 + 管理 API**（R21）→ ✅ **已交付**（2026-08-18，`feat/review-residual-r21`）——`CallerToolAllowlist` 升级为 config ∪ DB（`ToolGrantRepository` InMemory/Jdbc + V6 迁移 `caller_tool_grants`）+ `ToolGrantController` 管理 API（`/api/tools/grants`，admin-key 门控变更）；提交强制点不变。live 验证授权即时生效（grant 后 202 / 未授权 403）
- **checkpoint 敏感数据列级加密**（R22 注明的升级点，v1 文档标注"明文存储 + R21 鉴权保护"）→ ✅ **已交付**（2026-08-20，`feat/hitl-r22-rag` U7）——`com.agentflow.security`：`ColumnEncryptor` 接口 + `NoopColumnEncryptor` + `AesGcmColumnEncryptor`（AES-256-GCM，12B 随机 IV，`AESGCM:` 自描述前缀，篡改检测）+ `ColumnEncryptors` 工厂（`fromEnv` 宽松 dev/demo / `fromEnvStrict` 生产 fail-closed）。`PostgresCheckpointManager` 注入可空加密器，`saveNodeOutput/saveBarrier/审批载荷` 写加密、读解密（非 `AESGCM:` 前缀 legacy 明文原样返回）；`AgentFlowAutoConfiguration` 生产装配 `fromEnvStrict()`。引擎/DSL/InMemory 零感知。**启用 key 需 key-before-first-write**；key 轮换记后续
- **R22 系统性扩列**（原 Residual「加密扩到 routing_decisions」）→ ✅ **已交付**（2026-08-23，`feat/r22-extend-approval-ui` U1/U2）——`workflow_routing_decisions.decisions` + `workflow_definitions.definition`（V8 迁移 JSONB→TEXT）走同一 `ColumnEncryptor` 边界；starter 生产模式补注册 `PostgresWorkflowDefinitionStore` + strict 加密（与 checkpoint 双 fail-closed）；真 PG IT 验证密文形态（列值 `AESGCM:` 前缀、不含 prompt 明文）。**5 处 JSONB 敏感列全覆盖 = 系统性方案**；key 轮换仍 Deferred

---

## 4. v2 范围（plan 明示，非 v1 目标）

| v2 能力 | 说明 |
|:---|:---|
| **运行时条件分支 / 动态跳转** | ✅ **已交付**（2026-08-14，`feat/v2-conditional-branching`，U1–U8）——`when` 谓词条件边 + `on_error: goto` 兜底，BSP 可达性剪枝 + SKIPPED + checkpoint 路由决策持久化；见 `docs/plans/2026-08-14-001-feat-v2-conditional-branching-plan.md` |
| **完整 Human-in-the-Loop 审批中间件** | ✅ **已交付**（2026-08-20/21，`feat/hitl-r22-rag` U4–U8）：U4 引擎暂停（`AWAITING_APPROVAL` + 审批单快照落库）+ U5 `approveAndResume` 恢复 + U6 REST 审批端点（`ApprovalController` 待批投影/决策，decidedBy 服务端推导）+ U7 R22 列加密 + U8 demo-rag；**审批 Web UI → ✅ 已交付**（2026-08-23，`feat/r22-extend-approval-ui` U3/U4）：`ApprovalCenterController` 跨工作流聚合端点（`GET /api/approvals/pending`，admin/创建者可见域）+ React UI 第 6 Tab「审批中心」（决策无 mock fallback）+ 看板 AWAITING_APPROVAL 独立第四列；**Kafka 跨节点审批恢复 Deferred** |
| **Web 可视化工作流编辑器** | |
| **多租户 SaaS 平台** | v2+ |
| **Spring Cloud 接入（Nacos/Gateway/Sentinel）** | ⛔ **明确不做**（2026-08-25 调研拍板）——考察了 Dify / Coze Studio 开源版 / Spring AI Alibaba / LangGraph4j 的架构与依赖树：**没有一个把 Spring Cloud 织进编排内核**（Dify=Flask 单体+celery worker；Coze=单 Go 二进制+nginx，etcd 是 Milvus 的；SAA 的 graph-core/agent-framework 零 Spring Cloud 依赖，Nacos 只在可选 side starter）。赛道共识形态 = 无状态执行层 + 队列分发 + 共享状态存储——AgentFlow 的 Kafka 解耦 + tryClaim 幂等 + PG checkpoint 已是该形态。注册中心/网关属 executor 多节点时的接入层决策，不进引擎内核；决策详见 `plans/agentflow/07-sources-revision-interview.md` ⑬「为什么不接 Spring Cloud」 |
| **RAG 演示加分项**（2026-08-10 拍板） | ✅ **已交付**（2026-08-21，`feat/hitl-r22-rag` U8）——新模块 `demo-rag`：`InMemoryVectorStore`（确定性 token 集合 embedder + 余弦 top-k）+ `RagAgentFunction`（检索→增强→委托 wrapped agent）。**KTD-6 证明**：`RagEngineZeroChangeTest` 用 BspEngine 直跑 `agent: rag` 节点零改动成立。与 InterviewCoach（RAG 项目）分工不重复：AgentFlow 只做编排侧接入 |

> 注意：v1 只做**静态 DAG**（KTD-9）；条件分支（动态路由 + on_error 跳转）已交付 v2（2026-08-14）；Human-in-the-Loop 审批 + RAG 演示已交付（2026-08-20/21，`feat/hitl-r22-rag`）；其余（Web 编辑器、多租户）仍属 v2。

---

## 5. 建议的 v1 收尾优先级

1. **WorkflowSubmissionGuard**（档 B，真安全缺口）—— ✅ 已交付（2026-08-10，`a37d9dc`，见档 B 表）。
2. Grafana / 真 PG 部署验证（档 A）—— ✅ 均已交付：Grafana（2026-08-14，`54e5415`）；真 PG `verify` 实跑绿（2026-08-17，本地 PG 容器，IT 真跑非跳过）。
3. ~~R20 archetypes、Reducer 冲突演练~~（档 B，2026-08-10 决策明确不做）→ **档 B 已全部闭环**。
4. 档 C 叙事口径 → ✅ **已定稿**（2026-08-18，自述稿已附 `plans/agentflow/07-sources-revision-interview.md`）——v1 收尾档 A/B/C 全闭环。
