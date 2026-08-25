# 成本与预算控制（LLM 花费治理）

> 生成时间：2026-08-26 ｜ 代码版本：main@2a37d6b ｜ 可信度概览：已确认 14 条 / 合理推断 1 条 / 待确认 1 条

## 1. 业务目标

LLM 按 token 计费，一个失控的工作流（节点多、prompt 长、循环不收敛）可能烧掉真金白银。本能力建立三道防线：① **提交前拦截**（守卫：节点数/预估成本超上界 → 422，防恶意/失控提交起无界虚拟线程和成本）② **运行中记账**（per-workflow 预算：token/成本上界，逐调用累加）③ **超限告警**（edge-triggered：首次跨过预算记一次 `budget_exceeded` 指标事件，Grafana 可视）。

关键语义拍板（文档化决议）：**per-workflow 预算是「记账/告警，非阻断」**——运行中不中止执行；硬性防护由提交前 WorkflowSubmissionGuard 承担。两道防线分工明确，不重复设卡。

## 2. 范围与边界

- 包含：提交守卫（节点数/成本估算/422）、per-workflow 预算声明与解析校验、预算穿线（DSL→引擎→AgentInput→记账方）、edge-triggered 超限事件、成本单价表（CostCalculator）、指标族与 Grafana 面板对应
- 不包含：提交流程本体（见 [workflow-lifecycle.md](workflow-lifecycle.md)）、可观测指标体系全景（见 [observability-diagnosis.md](observability-diagnosis.md)）
- 上游流程：工作流生命周期（提交时的守卫拦截）
- 下游流程：可观测（budget_exceeded 指标）、Grafana 面板
- 涉及服务/模块：agentflow-api/security（WorkflowSubmissionGuard）、agentflow-core/observability（WorkflowBudget/CostCalculator/AgentFlowMetrics）、agentflow-core/dsl（AgentflowMeta 预算字段 + 校验）、适配器/mock（记账方）

## 3. 触发方式

| 类型 | 触发点 | 调用方/来源 | 证据 |
|---|---|---|---|
| 内部调用 | submit → guard.check（提交前拦截） | WorkflowController.submit:191 | CodeGraph: estimateCost 生产唯一调用点 check:94 |
| 配置（DSL） | YAML `agentflow:` 段声明 `budget_tokens`/`budget_cost` | 工作流定义者 | dsl/AgentflowMeta + SemanticValidator:137-147 |
| 运行时事件 | 每次 LLM 调用完成 → recordBudget 累加 | 适配器/mock 记账方 | AgentFlowMetrics#recordBudget:188 |
| 定时查询（可选） | mock 模式全局阈值 checkBudget | MockAgentFunction | AgentFlowMetrics#checkBudget:208 |

## 4. 前置条件与输入

| 条件/输入 | 说明 | 校验位置 | 证据 |
|---|---|---|---|
| 守卫参数 | maxNodes（默认 500）/model/maxCostUsd 任一 null 即禁用对应检查；成本检查须同时有 model+maxCostUsd | WorkflowSubmissionGuard 构造器 | WorkflowSubmissionGuard.java:44-48 |
| 预算声明合法 | budget_tokens 非负、budget_cost 非负有限 | SemanticValidator | SemanticValidator.java:137-147 |
| 单价表可用 | agentflow-cost-pricings.json 启动期加载 | CostCalculator | CLAUDE.md U7 记录（R4 配置外置） |

## 5. 主流程

1. **提交前：守卫拦截（第一道防线）**。
   - submit 在工具授权后调 guard.check(def)：节点数 > maxNodes → 422「节点数超上限」；model+maxCostUsd 已配 → estimateCost（每节点：prompt 长度/4 字符每 token，下限 500 in + 固定 500 out，单价表折算 USD 求和）> maxCostUsd → 422「预估成本超预算」。拒绝不落执行记录。
   - 证据：WorkflowController.java:190-195 → WorkflowSubmissionGuard#check:94-109。

2. **DSL 预算声明（第二道防线的参数）**。
   - YAML `agentflow: budget_tokens: 100000 / budget_cost: 0.5`；解析期校验非负/有限；未声明任一维度 → 预算对象为 null（记账方不查该维度）。
   - 证据：AgentflowMeta + SemanticValidator:137-147 + BspEngine#budgetFrom:289-295。

3. **预算穿线**。
   - BspEngine.execute/recoverAndExecute/approveAndResume 开头按 def.agentflow() 构造 WorkflowBudget → AgentInput.budget 字段穿到每个节点执行 → 适配器（SpringAi/LangChain4j）/MockAgentFunction 在每次真实 LLM 调用处调 `metrics.recordBudget(budget, model, promptTokens, completionTokens)`。
   - 证据：BspEngine#budgetFrom + AgentInput + AgentFlowMetrics#recordBudget 注释（防与 TokenCountingAdvisor 双计）。

4. **记账与超限事件（edge-triggered）**。
   - recordBudget：cost = 单价表折算 → budget.record(tokens, cost)——**仅首次跨过任一上界返回 true** → 记一次 budget_exceeded Counter；此后继续记账但不再重复记事件（事件数=1 而非节点数）。
   - 证据：AgentFlowMetrics#recordBudget:188-196 → WorkflowBudget#record:57-67（synchronized + exceededReported 标记）。

5. **非阻断语义**。
   - 超限后工作流**继续执行到终态**——预算不中止运行；硬防护在提交前。指标/trace 可事后审计。
   - 证据：WorkflowBudget 无中止逻辑 + CLAUDE.md「#5 语义拍板」记录。

6. **记账时机（逐轮）**。
   - 适配器在 chatWithTools 每轮（schema 重试/工具多轮/随后失败轮次）都记账——修 success-only+last-wins 低估（≤3x 低估修复记录）。
   - 证据：CLAUDE.md 2026-08-12 ce-code-review 修复 ① 记录。

7. **mock 模式全局阈值（legacy 路径）**。
   - 无 per-workflow 预算的 mock 调用可回落全局 checkBudget(threshold)（读 cost Counter 聚合，全局语义）；per-workflow 优先。
   - 证据：AgentFlowMetrics#checkBudget:198-217 注释（全局 vs per-workflow 区分）。

## 6. 流程图

```mermaid
flowchart TD
    U[调用方 POST 提交 YAML] --> GUARD{提交守卫<br/>节点数≤500? 成本预估≤上限?}
    GUARD -->|超限| R422[422 SUBMISSION_LIMIT<br/>不落执行记录]
    GUARD -->|通过| INIT[initWorkflow + 派发]

    INIT --> EXEC[BSP 执行]
    EXEC --> BUDGET{def 声明了<br/>budget_tokens/cost?}
    BUDGET -->|否| NB[预算null: 纯记账不告警]
    BUDGET -->|是| ACC[WorkflowBudget 穿线 AgentInput]

    ACC --> CALL[每次 LLM 调用完成]
    CALL --> RB[metrics.recordBudget:<br/>单价表折算 + budget.record 累加]
    RB --> EDGE{首次跨过任一上界?}
    EDGE -->|是, 仅一次| BEP[budget_exceeded 指标+1<br/>edge-triggered]
    EDGE -->|否/已报过| CONT[继续记账]
    BEP --> RUNON[工作流继续执行到终态<br/>非阻断]
    CONT --> RUNON
    NB --> CALL2[记账照常(tokens/cost指标)]
    CALL2 --> RUNON
```

## 7. 关键业务规则

| 编号 | 规则 | 触发条件 | 处理结果 | 影响范围 | 证据 | 可信度 |
|---|---|---|---|---|---|---|
| R1 | 提交前节点数硬上界（默认 500） | nodes > maxNodes | 422，防无界 VT | 提交侧 | DEFAULT_MAX_NODES + check:96-99 | 已确认 |
| R2 | 提交前预估成本上界 | model+maxCostUsd 已配且估算超限 | 422（估算式：4 字符/token + 500/500 基准） | 提交侧 | estimateCost:113-120 | 已确认 |
| R3 | 守卫参数任一 null 即禁用对应检查 | 装配配置 | 灵活启用（成本检查需 model+maxCostUsd 双配） | 部署 | 构造器注释:53-57 | 已确认 |
| R4 | 预算 DSL 声明：非负/有限 | 解析期 | 违例 400 | 提交侧 | SemanticValidator:137-147 | 已确认 |
| R5 | per-workflow 预算=记账告警非阻断 | 运行中超限 | 记事件继续执行（硬防护在提交前，两道防线分工） | 语义决议 | WorkflowBudget 无中止 + CLAUDE.md 拍板 | 已确认 |
| R6 | edge-triggered：超限事件恰一次 | 首次跨过上界 | budget_exceeded Counter +1 一次（非每节点） | 告警质量 | WorkflowBudget#record exceededReported | 已确认 |
| R7 | 两维度独立（tokens 与 cost 各自上界） | 任一超即触发 | 未配维度不检查 | 灵活性 | record:60-62（null 判断） | 已确认 |
| R8 | 记账线程安全 | 并行节点同时记账 | synchronized record（低频操作锁开销可忽略） | 正确性 | WorkflowBudget 注释:17-19 | 已确认 |
| R9 | 单价表外置配置 | 启动 | agentflow-cost-pricings.json 加载（R4 规避硬编码） | 可运维 | CostCalculator + CLAUDE.md U7 | 已确认 |
| R10 | recordBudget 与 TokenCountingAdvisor 防双计 | 真实适配器路径 | recordBudget 只累加预算不写 token/cost counter；counter 由 advisor 写 | 记账正确性 | AgentFlowMetrics#recordBudget 注释 | 已确认 |
| R11 | 恢复/审批路径同样构造预算 | recoverAndExecute/approveAndResume | budgetFrom 对齐 execute（三条入口一致） | 恢复一致性 | BspEngine#recoverAndExecute:426-427 | 已确认 |
| R12 | 逐轮记账（含 schema 重试/工具多轮/失败轮） | 适配器 chatWithTools | 修 success-only 低估（≤3x） | 成本精度 | CLAUDE.md 2026-08-12 修复① | 已确认 |
| R13 | 拒绝原因安全可外露 | 422 响应 | 原因直接回显（无内部信息泄漏） | API 契约 | check:98/106 reject 消息 | 已确认 |
| R14 | 预算估算 vs 实际记账分离 | 守卫估算/运行记账 | 守卫是提交前近似（不含循环轮次×节点重复），实际以运行记账为准 | 已知边界 | WorkflowSubmissionGuard 注释:23-28 | 已确认 |
| R15 | 成本估算低估面：循环轮次未计入 | 含回边工作流 | 守卫按单轮 DAG 估算——循环工作流实际成本可能数倍于估算 | 已知限制 | 估算式无 round 维度 + CLAUDE.md「成本估算低估面待 per-workflow」 | 合理推断 |

## 8. 状态与生命周期

无状态机；预算对象是**单次执行的累加器**（每 execute/recover/approve 新建，跨轮共享——循环工作流同一预算对象累计所有轮次）。

| 对象 | 创建 | 累计范围 | 销毁 |
|---|---|---|---|
| WorkflowBudget | 执行入口 budgetFrom | 整个工作流（全部轮次/节点/恢复续跑段） | 执行结束随栈丢弃（不持久化——重启恢复重建，已花费不回溯） |

注：崩溃恢复后预算从零重新累计（checkpoint 不存预算进度）——超限事件可能因恢复而重复触发一次。合理推断（未发现预算持久化；exceededReported 在新对象上重置）。

## 9. 数据变更与一致性

| 数据对象 | 操作 | 关键字段 | 事务/一致性策略 | 证据 |
|---|---|---|---|---|
| （内存）WorkflowBudget | synchronized 累加 | tokens/cost/exceededReported | 线程安全；不持久化 | WorkflowBudget |
| Micrometer Counter | budget_exceeded +1（edge）| 标签无 | Counter 单调增 | recordBudgetExceeded:167 |
| Micrometer Counters | tokens.consumed / cost.estimated（记账） | agent/model 标签 | advisor 或 mock 写 | AgentFlowMetrics 常量:42-43 |

## 10. 异常、重试与补偿

| 场景 | 触发条件 | 系统行为 | 重试/补偿/人工处理 | 证据 | 可信度 |
|---|---|---|---|---|---|
| 守卫拒绝 | 节点数/成本超限 | 422 + 原因 | 调小 DAG 或申请预算 | check | 已确认 |
| 超限事件 | 首次跨过 | 指标 +1，执行继续 | Grafana 面板告警（人工介入） | recordBudget | 已确认 |
| 单价表缺失/畸形 JSON | 启动加载失败 | CostCalculator 容错（畸形 JSON 修复记录） | 配置修正 | CLAUDE.md U7 review 修复 | 已确认 |
| 预算对象 null | 未声明预算 | 记账方 no-op 安全通过 | — | recordBudget:189-191 | 已确认 |

## 11. 权限、幂等与并发控制

- 权限/数据权限：守卫无 per-caller 差异（全局上界）；预算 DSL 由定义者自声明。已确认。
- 幂等键/防重逻辑：budget_exceeded edge-triggered（每执行至多一次）；恢复重建后可再触发一次（见第 8 节注）。
- 并发控制策略：synchronized record；守卫 check 无状态。
- 可能的竞态风险：同工作流并行节点同时 record——synchronized 已覆盖；无风险。
- 租户/组织维度隔离：无（全局单价表、全局守卫参数）。

## 12. 外部依赖与契约

| 依赖系统/组件 | 调用目的 | 请求/事件 | 成功处理 | 失败处理 | 证据 |
|---|---|---|---|---|---|
| 单价表配置文件 | 模型单价 | agentflow-cost-pricings.json | 折算成本 | 容错回退 | CostCalculator |
| Micrometer/Prometheus | 指标暴露 | budget_exceeded/tokens/cost | Grafana 面板（预算超限+窗口总成本面板） | — | grafana/agentflow-dashboard.json |
| LLM 适配器（记账方） | 逐调用记账 | recordBudget(budget, model, in, out) | 累加+可能超限事件 | 记账失败不阻断执行（适配器 try 语义） | LangChain4jAgentAdapter/SpringAiAgentAdapter |

## 13. 代码证据索引

| # | 证据 | 类型 | 支持的结论 |
|---|---|---|---|
| E1 | agentflow-api/src/main/java/com/agentflow/api/security/WorkflowSubmissionGuard.java#check | 源码 | 提交前双上界拦截（R1/R2/R3） |
| E2 | agentflow-api/src/main/java/com/agentflow/api/security/WorkflowSubmissionGuard.java#estimateCost:113-120 | 源码 | 估算式（R2/R14；CodeGraph: estimateCost 生产唯一调用点 check:94） |
| E3 | agentflow-core/src/main/java/com/agentflow/observability/WorkflowBudget.java#record | 源码 | edge-triggered + 线程安全 + 双维度（R6/R7/R8） |
| E4 | agentflow-core/src/main/java/com/agentflow/observability/AgentFlowMetrics.java#recordBudget:188-196 | 源码 | 单一真相源记账助手 + 防双计（R10） |
| E5 | agentflow-core/src/main/java/com/agentflow/observability/AgentFlowMetrics.java#checkBudget:198-217 | 源码 | 全局 legacy 语义区分 |
| E6 | agentflow-core/src/main/java/com/agentflow/engine/BspEngine.java#budgetFrom:289-295 | 源码 | DSL→预算对象构造 + 三入口对齐（R11） |
| E7 | agentflow-core/src/main/java/com/agentflow/dsl/SemanticValidator.java:137-147 | 源码 | 预算声明校验（R4） |
| E8 | agentflow-core/src/main/java/com/agentflow/observability/CostCalculator.java | 源码 | 单价表折算（R9） |
| E9 | agentflow-api/src/test/java/com/agentflow/api/security/WorkflowSubmissionGuardTest.java（12 断言含 blankPromptStillCountsBaseline:107 / longPromptRaisesEstimatedCost:119 / estimateSumsAcrossNodes:130） | 测试 | 估算式三特性（R2 B 级交叉验证） |
| E10 | agentflow-core/src/test/java/com/agentflow/observability/WorkflowBudgetTest.java（12 断言含并发 edge-triggered） | 测试 | 并发安全 + 恰一次（R6/R8 B 级交叉验证） |
| E11 | agentflow-core/src/test/java/com/agentflow/engine/MockBudgetIntegrationTest.java | 测试 | 引擎→mock 全链路记账（R12 B 级） |
| E12 | CLAUDE.md「#5 语义拍板：per-workflow budget 为记账/告警（非阻断）」 | 文档 | R5 决议记录 |

## 14. 待业务确认的问题

1. **问题**：超限告警的运维响应预案——budget_exceeded 事件发生后人工介入的 SLA 与动作（kill 运行中工作流？联系提交方？仅记录）？
   - 为什么代码不足以确认：非阻断语义下系统不采取任何动作；响应流程是运维制度问题，代码无法回答。
   - 建议向谁确认：运维/产品。
   - 建议核查的资料或日志：Grafana 告警通道配置（是否接 webhook/通知）；现有运维手册。
