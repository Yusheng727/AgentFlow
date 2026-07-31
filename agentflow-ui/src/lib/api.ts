import type { SubmitRequest, SubmitResponse, StatusResponse, DiagnosisReport } from '../types';

const BASE = '/api';

export async function submitWorkflow(req: SubmitRequest): Promise<SubmitResponse> {
  const res = await fetch(`${BASE}/workflows`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Key': 'demo-key-1234567890abcdef' },
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(`Submit failed: ${res.status}`);
  return res.json();
}

export async function getWorkflowStatus(workflowId: string): Promise<StatusResponse> {
  const res = await fetch(`${BASE}/workflows/${workflowId}/status`, {
    headers: { 'X-API-Key': 'demo-key-1234567890abcdef' },
  });
  if (!res.ok) throw new Error(`Status fetch failed: ${res.status}`);
  return res.json();
}

export async function diagnoseWorkflow(workflowId: string): Promise<DiagnosisReport> {
  // DiagnosisController accepts POST with trace data — for demo we pass a minimal trace
  const trace = {
    workflowId,
    nodes: [],
    totalTokens: 0,
  };
  const res = await fetch(`${BASE}/diagnosis`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Key': 'demo-key-1234567890abcdef' },
    body: JSON.stringify({ workflowId, trace }),
  });
  if (!res.ok) throw new Error(`Diagnosis failed: ${res.status}`);
  return res.json();
}
