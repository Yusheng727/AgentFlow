# AgentFlow v1 交付状态 · 剩余工作 · v2 路线图

> 本文档是 2026-08-07 对 v1 的交付盘点与 v2 边界的一手来源，供接手/面试口径自洽。
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

### 档 C — 叙事 / 面试口径（演示前定即可，非代码）
- 06 OQ `From 2026-06-28 review` 13 条叙事/范围项，如：从0 vs 扩 LangGraph4j 的 buy-vs-build 论证、七三开(后端70%+Agent30%)折算、KTD-1 BSP vs Actor/CSP、@Tool 与 InterviewCoach 边界、Spring AI 差异化口径。
- API Key 完整签发/轮换 registry：现为 demo key + env `AGENTFLOW_API_KEYS` 追加，完整签发/轮换属 ops。

---

## 3. v1.1 路线图（介于 v1 / v2）

- ~~**`LangChain4jAgentAdapter`**（R5）~~ → ✅ **已交付**（2026-08-10，`f4651b2`，本地未推送）——新模块 `agentflow-adapters/langchain4j`（1.0.0 GA），依赖面仅 core + langchain4j 无 Spring AI，窄表面对齐（SpEL/ChatModel/@Tool 循环/TokenUsage/ErrorClassifier/trace/cancel）；KTD-7 可移植性约束实证
- **分布式模式**：Redis + Kafka（R18③ / R19，v1 用内存 @Async + DB 任务表轻量替代）——Kafka 环境已落地（2026-08-14，`54e5415`：`apache/kafka:3.9.2` KRaft 免 Zookeeper，`--profile distributed`），引擎侧 R18③/R19 仍未动
- **工具级授权 DB 表 + 管理 API**（R21，v1 为 config/env 硬编码 `CallerToolAllowlist`）
- **checkpoint 敏感数据列级加密**（R22 注明的升级点，v1 文档标注"明文存储 + R21 鉴权保护"）

---

## 4. v2 范围（plan 明示，非 v1 目标）

| v2 能力 | 说明 |
|:---|:---|
| **运行时条件分支 / 动态跳转** | ✅ **已交付**（2026-08-14，`feat/v2-conditional-branching`，U1–U8）——`when` 谓词条件边 + `on_error: goto` 兜底，BSP 可达性剪枝 + SKIPPED + checkpoint 路由决策持久化；见 `docs/plans/2026-08-14-001-feat-v2-conditional-branching-plan.md` |
| **完整 Human-in-the-Loop 审批中间件** | 中断→外部审批→恢复执行 |
| **Web 可视化工作流编辑器** | |
| **多租户 SaaS 平台** | v2+ |
| **RAG 演示加分项**（2026-08-10 拍板） | 自定义 `RagAgentFunction`（AgentFunction 内调向量检索），引擎层零改动——验证 KTD-6 扩展点设计成立。与 InterviewCoach（RAG 项目）分工不重复：AgentFlow 只做编排侧接入 |

> 注意：v1 只做**静态 DAG**（KTD-9）；条件分支（动态路由 + on_error 跳转）已交付 v2（2026-08-14）；其余（Human-in-the-Loop、Web 编辑器、多租户、RAG 演示）仍属 v2。

---

## 5. 建议的 v1 收尾优先级

1. **WorkflowSubmissionGuard**（档 B，真安全缺口）—— ✅ 已交付（2026-08-10，`a37d9dc`，见档 B 表）。
2. Grafana / 真 PG 部署验证（档 A）—— ✅ 均已交付：Grafana（2026-08-14，`54e5415`）；真 PG `verify` 实跑绿（2026-08-17，本地 PG 容器，IT 真跑非跳过）。
3. ~~R20 archetypes、Reducer 冲突演练~~（档 B，2026-08-10 决策明确不做）→ **档 B 已全部闭环**。
4. 档 C 面试口径 → 定稿一份 30s/5min 自述稿附到 `07-sources-revision-interview.md` 或本仓库面试文档（待定）。
