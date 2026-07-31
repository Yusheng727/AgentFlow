export interface SubmitRequest {
  workflowName: string;
  version: string;
  yamlContent: string;
  inputs: Record<string, unknown>;
}

export interface SubmitResponse {
  workflowId: string;
  status: string;
  message: string | null;
  statusLinks: { status: string } | null;
}

export interface StatusResponse {
  workflowId: string;
  status: string;
  timestamp: string;
}

export interface NodeTrace {
  nodeId: string;
  agentName: string;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  durationMs: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  outputSummary: string | null;
  error: string | null;
}

export interface ExecutionTraceSnapshot {
  workflowId: string;
  startTime: string | null;
  endTime: string | null;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  nodes: NodeTrace[];
  totalTokens: number;
}

export interface Diagnosis {
  problemType: string;
  nodeId: string;
  description: string;
  suggestion: string;
}

export interface DiagnosisReport {
  workflowId: string;
  totalNodes: number;
  failedNodes: number;
  findings: Diagnosis[];
}
