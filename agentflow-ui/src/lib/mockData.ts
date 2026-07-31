import type {
  DiagnosisReport,
  ExecutionTraceSnapshot,
  PipelineNode,
  WorkflowDefinitionInfo,
  WorkflowSummary,
} from '../types'

// ──────────────────────────── mock fallback 数据（KTD-1）────────────────────────────
// 移植自 prototype-final.html 的演示 state：后端不可达时 UI 降级渲染这些数据，保证不白屏。

export const mockWorkflows: WorkflowSummary[] = [
  {
    id: 'wf-1',
    name: 'supplier-risk-v2',
    status: 'running',
    nodes: 4,
    steps: 2,
    time: '1.2s',
    date: '2m 前',
    desc: '正在执行 super-step 0：3 个专家 Agent 并行分析中。',
  },
  {
    id: 'wf-2',
    name: 'supplier-risk-v1',
    status: 'success',
    nodes: 4,
    steps: 2,
    time: '41ms',
    date: '15m 前',
    desc: '3 专家并行分析完成 → Supervisor 汇总评级 LOW。',
  },
  {
    id: 'wf-3',
    name: 'greeting-test',
    status: 'success',
    nodes: 1,
    steps: 1,
    time: '3ms',
    date: '1h 前',
    desc: '单节点问候工作流。',
  },
  {
    id: 'wf-4',
    name: 'contract-review',
    status: 'success',
    nodes: 4,
    steps: 4,
    time: '28ms',
    date: '2h 前',
    desc: '4 步串行合同审核流水线。',
  },
  {
    id: 'wf-5',
    name: 'investment-analysis',
    status: 'failed',
    nodes: 6,
    steps: 4,
    time: '超时 120s',
    date: '30m 前',
    desc: 'super-step 2 风险评估节点超时。',
  },
]

export const mockDefinitions: WorkflowDefinitionInfo[] = [
  { id: 'def-1', name: 'supplier-risk', version: '1.0', nodes: 4, desc: '供应商风险评估：3 专家并行 → Supervisor 汇总' },
  { id: 'def-2', name: 'contract-review', version: '1.0', nodes: 4, desc: '合同审核流水线：4 步链式串行' },
  { id: 'def-3', name: 'investment-analysis', version: '1.0', nodes: 6, desc: '投资分析决策：双层 fork-join 混合拓扑' },
  { id: 'def-4', name: 'greeting-test', version: '1.0', nodes: 1, desc: '最小示例：单节点问候' },
]

export const mockPipelineNodes: PipelineNode[] = [
  { id: 'financial-analysis', agent: 'finance-agent', status: 'success', time: 12, output: '财务风险：低 | 资产负债率：35% | 现金流：稳定' },
  { id: 'compliance-check', agent: 'compliance-agent', status: 'success', time: 10, output: '合规风险：中 | 营业执照：有效 | 1 次环保违规' },
  { id: 'reputation', agent: 'reputation-agent', status: 'success', time: 11, output: '声誉风险：低 | 行业口碑：良好 | 5 年合作' },
  { id: 'aggregate-rating', agent: 'aggregate-agent', status: 'success', time: 8, output: '{"riskLevel":"LOW","confidence":0.85,"evidence":[...]}' },
]

/** KPI 行中无法从工作流列表推算的静态指标（真实指标待 U3 TraceController/Micrometer 端点）。 */
export const mockKpi = {
  avgDuration: '42ms',
  avgDurationTrend: '↓ 15% vs 上周',
  tokenTotal: '0',
  tokenHint: 'mock 模式',
}

/**
 * mock 执行轨迹（ExecutionTrace.Snapshot 形态）：由 mockPipelineNodes 推导。
 * 节点顺序即展示顺序——末位 aggregate-rating 为汇总节点（PipelineView 分组约定）。
 */
export const mockTrace: ExecutionTraceSnapshot = {
  workflowId: 'demo-supplier-risk-1',
  startTime: '2026-07-31T10:00:00Z',
  endTime: '2026-07-31T10:00:00.041Z',
  status: 'COMPLETED',
  totalTokens: 0,
  nodes: mockPipelineNodes.map((n) => ({
    nodeId: n.id,
    agentName: n.agent,
    status: n.status === 'success' ? ('SUCCESS' as const) : n.status === 'failed' ? ('FAILED' as const) : ('RUNNING' as const),
    durationMs: n.time,
    promptTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
    outputSummary: n.output,
    error: null,
  })),
}

/**
 * mock 诊断报告：对齐后端 DiagnosisService 的 5 类问题（连续超时 / Token 异常消耗 /
 * SpEL 解析失败 / Channel 缺失 / 节点重复执行），取 3 类演示卡片渲染。
 */
export const mockDiagnosisReport: DiagnosisReport = {
  workflowId: 'wf-5',
  totalNodes: 6,
  failedNodes: 1,
  findings: [
    {
      problemType: '连续超时',
      nodeId: 'risk-assessment',
      description: '节点连续 3 次重试均超时（阈值 120s），super-step 2 被阻塞',
      suggestion: '检查 Prompt 复杂度或调大 timeout 配置；确认 LLM 网络链路稳定',
    },
    {
      problemType: 'Token 异常消耗',
      nodeId: 'profit-forecast',
      description: '单节点 Token 消耗 12,400，超过同层均值 3 倍',
      suggestion: '精简 prompt_template，或对超长上下文做截断/摘要',
    },
    {
      problemType: '节点重复执行',
      nodeId: 'company-finance',
      description: '同一节点在 super-step 0 被执行 2 次，存在重复计费风险',
      suggestion: '检查 DAG 定义中是否有重复节点 id，或 Recovery 重放逻辑是否误判',
    },
  ],
}

/** 执行趋势（7 天）柱状图：高度百分比。 */
export const trendChart = {
  bars: [40, 65, 50, 80, 55, 70, 90],
  labels: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'],
}

/** 节点耗时分布柱状图：高度 + 颜色（success=正常区间，warning=偏高）。 */
export const durationChart = {
  bars: [
    { height: 30, color: 'bg-success' },
    { height: 45, color: 'bg-success' },
    { height: 60, color: 'bg-warning' },
    { height: 25, color: 'bg-success' },
    { height: 50, color: 'bg-warning' },
    { height: 35, color: 'bg-success' },
  ],
  labels: ['P50', 'P75', 'P90', 'P95', 'P99', 'Max'],
}
