import type { PipelineNode, WorkflowDefinitionInfo, WorkflowSummary } from '../types'

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
