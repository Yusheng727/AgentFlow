---
title: feat: AgentFlow UI + 可观测性 + 辅助 Demo（U7/U11/U12）
type: feat
date: 2026-07-31
---

## Summary

AgentFlow P0 全交付后，补齐交付门面与验证场景：① React UI 把已有 HTML 原型转成真实组件对接 REST API；② U7 可观测性（Micrometer 指标 + LLM 成本核算 + TraceController + Grafana Dashboard）；③ U11 合同审核串行 Demo；④ U12 投资分析双层 fork-join Demo。UI 串行先行作为交付门面，U7/U11/U12 并行。

## Problem Frame

P0 引擎核心已完成（BSP/Checkpoint/Recovery/Mock/容错/鉴权/调试），但缺三块：
- **交付门面**：只有 REST API + 控制台输出，无可视化界面，演示和简历展示弱
- **可观测性**：ExecutionTrace 已有数据结构但 mock 模式不写入，无 Micrometer 指标、无成本核算、无 Grafana
- **拓扑验证**：只有 U10 单层并行 Demo，串行依赖链和复杂混合拓扑未验证

## Requirements

- R8（ExecutionTrace 结构化轨迹）、R9（Micrometer 指标）、R10（成本核算）
- R16（合同审核串行 Demo）、R17（投资分析双层 fork-join Demo）
- UI 对接现有 REST API（R21 鉴权端点），真实 API 优先 + mock fallback（demo 模式后端没起时不白屏）

## Key Technical Decisions

- **KTD-1 真实 API 优先 + mock fallback**：UI 优先调真实 `/api/workflows` 等，后端不可达时降级到内置 mock 数据（prototype-final.html 的 setTimeout 模拟），保证 UI 独立可用
- **KTD-2 mock 模式写 trace**：当前 ExecutionTrace 只在 SpringAiAgentAdapter（真实 LLM 路径）写入，mock 模式（MockAgentFunction）不写。U7 让 MockAgentFunction 也写 NodeTrace，否则 mock 模式下 TraceController 返回空树
- **KTD-3 成本核算在 TokenCountingAdvisor 扩展**：现有 TokenCountingAdvisor 已记 token Counter，U7 扩展加 `agentflow.workflow.cost.estimated` Counter（token × 模型单价表），不新建类
- **KTD-4 U11/U12 仿 U10 模式**：新 Maven 模块 + YAML + Application + 测试，mock 模式零成本，复用 MockAgentFunction + InMemoryCheckpointManager
- **KTD-5 React 技术栈**：React 18 + TypeScript + Vite + Tailwind CSS（package.json 已 staged），按 prototype-final.html 转 5 Tab 组件

## Implementation Units

### U1. React UI 基础架构 + Layout + Dashboard（先行，串行）

- **Goal**: 搭建 React 项目运行，实现深色侧边栏 + 5 Tab 路由 + 看板页（KPI + 图表 + 三列看板 + 表格）
- **Requirements**: UI 对接 REST API
- **Dependencies**: 无
- **Files**:
  - `agentflow-ui/src/App.tsx`（5 Tab 状态 + 全局 workflowId）
  - `agentflow-ui/src/components/Layout.tsx`（深色侧边栏 5 菜单 + 主内容区）
  - `agentflow-ui/src/components/Dashboard.tsx`（KPI 行 + 图表占位 + 三列看板 + 最近执行表格）
  - `agentflow-ui/src/lib/api.ts`（fetch 封装 + mock fallback）
  - `agentflow-ui/src/types/index.ts`（类型定义）
  - `agentflow-ui/src/lib/mockData.ts`（mock fallback 数据）
- **Approach**: 按 `agentflow-ui/prototype-final.html` 转 React 组件。api.ts 封装 fetch，真实 API 失败时降级 mockData。Dashboard 渲染 workflows 状态分组（running/success/failed）
- **Patterns to follow**: `agentflow-ui/prototype-final.html` 的交互逻辑 + Tailwind class
- **Test scenarios**:
  - `npm run dev` 启动，5 Tab 切换正常
  - 看板三列显示 mock 工作流数据
  - 后端未起时降级 mock，不白屏
- **Verification**: `npm run build` 成功 + `npm run dev` 手动验证 5 Tab

### U2. React UI 提交 + 工作流定义 + 轨迹 + 诊断

- **Goal**: 实现剩余 4 Tab，YAML 编辑器可编辑校验，提交流程对接 API
- **Requirements**: 对接 POST /api/workflows、POST /api/diagnosis
- **Dependencies**: U1
- **Files**:
  - `agentflow-ui/src/components/SubmitForm.tsx`（YAML 编辑器 + 配置表单 + 提交/Dry-run 按钮）
  - `agentflow-ui/src/components/WorkflowDefinitions.tsx`（定义卡片网格 + 选择跳转）
  - `agentflow-ui/src/components/PipelineView.tsx`（BSP Pipeline 可视化）
  - `agentflow-ui/src/components/DiagnosisPanel.tsx`（KPI 摘要 + 诊断结果）
  - `agentflow-ui/src/components/YamlEditor.tsx`（可编辑 + 语法高亮 + 行号 + 校验）
- **Approach**: SubmitForm 调真实 POST /api/workflows，spinner → 成功后插入看板 + toast + 跳转轨迹页。YamlEditor 用 contenteditable + 语法高亮（仿 prototype）。PipelineView 按 super-step 分组渲染节点卡片
- **Patterns to follow**: `agentflow-ui/prototype-final.html` 的提交流程 + YAML 高亮
- **Test scenarios**:
  - 提交工作流 → spinner → toast → 跳转轨迹页（真实 API 或 mock）
  - YAML 编辑器实时校验（缺 nodes/agentflow 段警告）
  - 工作流定义点击 → 选中高亮 → 跳转提交页预填名称
  - Pipeline 渲染 Step 0 并行 → barrier → Step 1 汇总
  - 诊断运行 → spinner → 显示结果
- **Verification**: `npm run build` 成功 + 全部 Tab 交互可手动验证

### U3. U7 可观测性：AgentFlowMetrics + 成本核算 + TraceController

- **Goal**: Micrometer 5 指标（执行计数/节点耗时/token/成本估算/预算超限）+ TraceController 端点 + mock 模式写 trace
- **Requirements**: R8, R9, R10
- **Dependencies**: 无（与 U1/U2 并行）
- **Files**:
  - `agentflow-core/src/main/java/com/agentflow/observability/AgentFlowMetrics.java`（5 Micrometer 指标注册 + 成本换算）
  - `agentflow-core/src/main/java/com/agentflow/observability/CostCalculator.java`（token × 模型单价表）
  - `agentflow-api/src/main/java/com/agentflow/api/TraceController.java`（GET /api/workflows/{id}/trace）
  - `agentflow-adapters/spring-ai/src/main/java/com/agentflow/adapters/mock/MockAgentFunction.java`（修改：写 NodeTrace）
  - `agentflow-core/src/test/java/com/agentflow/observability/MetricsTest.java`
- **Approach**: AgentFlowMetrics 封装 MeterRegistry 注册 5 指标。CostCalculator 持模型单价表（OpenAI gpt-4o 等常见模型），token × 单价 = 成本。TokenCountingAdvisor after() 扩展调 AgentFlowMetrics 记成本。MockAgentFunction execute 时构造 NodeTrace.succeed 写入（需注入 ExecutionTrace，或通过 AgentInput 传递）。TraceController 返回 ExecutionTrace.Snapshot
- **Technical design**: 指标流——TokenCountingAdvisor.after() → AgentFlowMetrics.recordTokens(agent, model, tokens) → 内部记 token Counter + 查 CostCalculator 算成本记 cost Counter。trace 流——MockAgentFunction 写 NodeTrace（mock 模式补齐），SpringAiAgentAdapter 已有
- **Patterns to follow**: `TokenCountingAdvisor`（U3 已有 Micrometer 模式）+ `ExecutionTrace.addNode`
- **Test scenarios**:
  - 完整工作流执行后 TraceController 返回完整轨迹树（含 mock 模式）
  - Micrometer 5 指标正确注册（Counter + Timer + 成本 Counter）
  - 成本自动计算（token × 单价，用 SimpleMeterRegistry 断言）
  - mock 模式下 trace 不为空（KTD-2 验证）
  - 预算超限 Counter 触发（成本 > 阈值）
- **Verification**: mvn verify 绿 + MetricsTest 覆盖 5 指标 + TraceControllerTest 覆盖端点

### U4. U7 Grafana Dashboard JSON

- **Goal**: 可 import 的 Grafana Dashboard 模板
- **Requirements**: R9
- **Dependencies**: U3
- **Files**:
  - `agentflow-starter/src/main/resources/grafana/agentflow-dashboard.json`
- **Approach**: Grafana Dashboard JSON 含 5 面板：工作流执行趋势（Counter 时序）、各 Agent P50/P95/P99 延迟（Timer histogram）、token 消耗 Top10（Counter 按 agent tag）、每次工作流成本（cost Counter）、失败率时间序列。数据源 Prometheus，指标名对齐 U3 的 AgentFlowMetrics
- **Patterns to follow**: 标准 Grafana Dashboard JSON schema
- **Test scenarios**:
  - JSON 格式合法（jq 解析通过）
  - 面板引用的指标名与 AgentFlowMetrics 一致
  - Grafana import 成功（手动验证或 JSON schema 校验）
- **Verification**: JSON 合法 + 指标名对齐 U3

### U5. U11 合同审核流水线 Demo（4 步串行）

- **Goal**: 验证引擎串行依赖链 + 上下文传递能力，与 U10 并行拓扑对比
- **Requirements**: R16
- **Dependencies**: 无（与 U3/U4/U12 并行，worktree 隔离）
- **Files**:
  - `demo-contract-review/pom.xml`
  - `demo-contract-review/src/main/resources/workflows/contract-review.yml`
  - `demo-contract-review/src/main/resources/application.yml`
  - `demo-contract-review/src/main/java/com/agentflow/demo/contract/ContractReviewApplication.java`
  - `demo-contract-review/src/test/java/com/agentflow/demo/contract/ContractReviewDemoTest.java`
  - `pom.xml`（加 module）
- **Approach**: 仿 U10 demo-supplier-risk 模式。YAML 4 节点串行（合同解析 → 法律风险 → 合规建议 → 最终报告），每步 mock_response 引用 `${previousStep}`。Application 编程式组装 BspEngine + MockAgentFunction。测试验证 4 步串行 + 上下文传递 + 每步时间差 > 100ms + checkpoint
- **Patterns to follow**: `demo-supplier-risk/`（U10 模式）+ `SupplierRiskDemoTest`
- **Test scenarios**:
  - 4 步串行执行，每步输出正确传递下一步（`${contract-parse}` 占位符替换）
  - ExecutionTrace 展示 4 节点串行依赖拓扑（4 super-step 各 1 节点）
  - 任一步失败不影响前置已完成步骤的 checkpoint
  - 端到端 < 20s（mock 模式）
  - 串行验证：每步启动等待前一步完成（时间差 > 100ms）
- **Verification**: mvn verify 7 模块绿 + ContractReviewDemoTest 4 场景过

### U6. U12 投资分析决策 Demo（双层 fork-join 混合）

- **Goal**: 验证引擎复杂混合拓扑（双层 fork-join），证明 super-step 分层泛用性
- **Requirements**: R17
- **Dependencies**: 无（与 U3/U4/U5 并行，worktree 隔离）
- **Files**:
  - `demo-investment-analysis/pom.xml`
  - `demo-investment-analysis/src/main/resources/workflows/investment-analysis.yml`
  - `demo-investment-analysis/src/main/resources/application.yml`
  - `demo-investment-analysis/src/main/java/com/agentflow/demo/investment/InvestmentAnalysisApplication.java`
  - `demo-investment-analysis/src/test/java/com/agentflow/demo/investment/InvestmentAnalysisDemoTest.java`
  - `pom.xml`（加 module）
- **Approach**: 仿 U10 模式。YAML 6 节点 4 super-step：step0（公司财报+市场数据并行）→ step1（可行性分析串行）→ step2（风险评估+收益预测并行）→ step3（投资裁决汇总）。测试验证 4 super-step 分层 + channel 隔离 + 汇总引用前 3 层输出
- **Patterns to follow**: `demo-supplier-risk/`（U10 模式）+ `SupplierRiskDemoTest`
- **Test scenarios**:
  - 6 节点 4 super-step 正确分层（0-based step 0/1/2/3）
  - 上层并行结果正确传递下层串行（`${company-finance}` 等占位符）
  - 最终汇总引用前 3 层所有输出
  - ExecutionTrace 展示完整 4 层结构
  - channel 隔离：并行执行时同 super-step 内 channel 不互相污染
  - 端到端 < 40s（mock 模式）
- **Verification**: mvn verify 8 模块绿 + InvestmentAnalysisDemoTest 场景过

## Scope Boundaries

### In scope
- React UI 5 Tab 真实 API 对接 + mock fallback
- U7 Micrometer 5 指标 + 成本核算 + TraceController + Grafana Dashboard
- U11 合同审核串行 Demo
- U12 投资分析双层 fork-join Demo

### Deferred to Follow-Up Work
- U8 Workflow 版本管理（P2，独立做）
- UI 移动端适配（先桌面）
- React 单元测试（先手动验证，后续补 Vitest）
- Grafana 真实部署验证（JSON 模板先备好）

### Outside this product's identity
- 动态 DAG / 条件分支（v2）
- 分布式 CheckpointManager（Redis/etcd）

## Dependencies / Prerequisites

- main 分支含 P0 全交付（U1-U14+U9+U10+U13）+ U6 调试体验
- 工具链：Maven 3.9.16 + JDK 21 + Node.js（UI）
- U1/U2（UI）串行先行；U3/U4/U5/U6（后端 + Demo）可并行（worktree 隔离）

## Risks

- **R1 mock 模式写 trace 破坏 MockAgentFunction 无状态单例**：当前 MockAgentFunction 是单例（任意 agent name 复用），写 trace 需要注入 ExecutionTrace。解法：通过 AgentInput 传 trace，或改为 per-workflow 实例
- **R2 React 骨架组件与 prototype 不匹配**：当前 src/ 下组件是旧版（U10 前写的），需重写而非改
- **R3 并行 worktree git index 冲突**：U5/U6 都改 pom.xml 加 module。解法：worktree 隔离，merge 时按依赖序处理 pom 冲突
- **R4 成本单价表维护**：模型单价变化频繁，硬编码会过时。解法：单价表放配置文件，启动期加载

## High-Level Technical Design

```
[UI 串行先行]                    [后端 + Demo 并行]
U1 Layout+Dashboard ───────┐    U3 AgentFlowMetrics+TraceController
U2 Submit+Pipeline+Diag ───┘    U4 Grafana Dashboard
                                 U5 U11 合同审核串行
                                 U6 U12 投资分析 fork-join
                                          ↓
                                   merge to main（依赖序）
```

**指标流**（U3）：
```
LLM 调用 → TokenCountingAdvisor.after()
  → AgentFlowMetrics.recordTokens(agent, model, tokens)
    → Counter agentflow.tokens.consumed
    → CostCalculator.cost(model, tokens) → Counter agentflow.workflow.cost.estimated
  → 超 budget → Counter agentflow.workflow.cost.budget_exceeded
```

**trace 流**（U3 KTD-2）：
```
真实模式: SpringAiAgentAdapter.execute() → NodeTrace.succeed() → ExecutionTrace
mock 模式: MockAgentFunction.execute() → NodeTrace.succeed() → ExecutionTrace（U7 补齐）
查询: GET /api/workflows/{id}/trace → ExecutionTrace.Snapshot
```

## Sources & Research

- 本仓库 `docs/plans/agentflow/05-implementation-units.md` U7/U11/U12 章节
- `agentflow-ui/prototype-final.html`（交互原型）
- `agentflow-adapters/spring-ai/.../TokenCountingAdvisor.java`（U7 扩展基础）
- `demo-supplier-risk/`（U11/U12 模式参考）
- `agentflow-api/.../WorkflowController.java`（API 契约）
