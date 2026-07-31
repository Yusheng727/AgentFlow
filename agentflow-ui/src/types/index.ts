// ──────────────────────────── UI 看板模型（Dashboard 渲染用） ────────────────────────────

/** 看板三列分组的 UI 状态（对齐后端 WorkflowStatus 的小写形态）。 */
export type WorkflowStatusUi = 'running' | 'success' | 'failed'

/** 看板卡片 / 最近执行表格的一行。 */
export interface WorkflowSummary {
  id: string
  name: string
  status: WorkflowStatusUi
  nodes: number
  steps: number
  time: string
  date: string
  desc: string
}

/** 工作流定义卡片（U2 定义 Tab 用）。 */
export interface WorkflowDefinitionInfo {
  id: string
  name: string
  version: string
  nodes: number
  desc: string
}

/** BSP Pipeline 可视化的单节点（U2 轨迹 Tab 用）。 */
export interface PipelineNode {
  id: string
  agent: string
  status: 'success' | 'running' | 'failed'
  time: number
  output: string
}

// ──────────────────── 后端 REST 契约（agentflow-api WorkflowController / DiagnosisController） ────────────────────

/** POST /api/workflows 请求体。 */
export interface SubmitRequest {
  workflowName: string
  version: string
  yamlContent: string
  inputs: Record<string, unknown>
}

/** POST /api/workflows / POST /api/workflows/{id}/retry 响应体（202）。 */
export interface SubmitResponse {
  workflowId: string
  status: string
  message: string | null
  links: { status: string } | null
}

/** GET /api/workflows/{id}/status 响应体。 */
export interface StatusResponse {
  workflowId: string
  /** 后端 WorkflowStatus 枚举名：PENDING / RUNNING / SUCCESS / FAILED / UNKNOWN */
  status: string
  queriedAt: string
}

/** ExecutionTrace.Snapshot.nodes 的元素（对齐后端 NodeTrace 序列化形态）。 */
export interface NodeTrace {
  nodeId: string
  agentName: string
  status: 'RUNNING' | 'SUCCESS' | 'FAILED'
  durationMs: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  outputSummary: string | null
  error: string | null
}

/** ExecutionTrace.Snapshot（诊断请求 / U3 TraceController 响应）。 */
export interface ExecutionTraceSnapshot {
  workflowId: string
  startTime: string | null
  endTime: string | null
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  nodes: NodeTrace[]
  totalTokens: number
}

/** 单条诊断发现。 */
export interface Diagnosis {
  problemType: string
  nodeId: string
  description: string
  suggestion: string
}

/** POST /api/diagnosis 响应体。 */
export interface DiagnosisReport {
  workflowId: string
  totalNodes: number
  failedNodes: number
  findings: Diagnosis[]
}
