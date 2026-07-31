import React, { useState, useEffect } from 'react';
import { GitBranch, Loader2 } from 'lucide-react';
import { PipelineView } from './PipelineView';
import { getWorkflowStatus } from '../lib/api';
import type { NodeTrace } from '../types';

// Mock 演示数据（真实环境下从 ExecutionTrace 读取）
const DEMO_NODES: NodeTrace[] = [
  { nodeId: 'financial-analysis', agentName: 'finance-agent', status: 'SUCCESS', durationMs: 12, promptTokens: 0, completionTokens: 0, totalTokens: 0, outputSummary: '财务风险：低', error: null },
  { nodeId: 'compliance-check', agentName: 'compliance-agent', status: 'SUCCESS', durationMs: 10, promptTokens: 0, completionTokens: 0, totalTokens: 0, outputSummary: '合规风险：中', error: null },
  { nodeId: 'reputation', agentName: 'reputation-agent', status: 'SUCCESS', durationMs: 11, promptTokens: 0, completionTokens: 0, totalTokens: 0, outputSummary: '声誉风险：低', error: null },
  { nodeId: 'aggregate-rating', agentName: 'aggregate-agent', status: 'SUCCESS', durationMs: 8, promptTokens: 0, completionTokens: 0, totalTokens: 0, outputSummary: '{"riskLevel":"LOW",...}', error: null },
];

interface WorkflowMonitorProps {
  workflowId: string | null;
}

export function WorkflowMonitor({ workflowId }: WorkflowMonitorProps) {
  const [status, setStatus] = useState<string | null>(null);
  const [nodes] = useState<NodeTrace[]>(DEMO_NODES);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!workflowId) return;
    setLoading(true);
    getWorkflowStatus(workflowId)
      .then(res => setStatus(res.status))
      .catch(e => setError(e.message))
      .finally(() => setLoading(false));
  }, [workflowId]);

  if (!workflowId) {
    return (
      <div className="flex flex-col items-center justify-center h-64 text-center">
        <GitBranch size={48} className="text-text-muted mb-4 opacity-30" />
        <p className="text-text-muted text-sm">先在「提交工作流」中提交一个工作流</p>
      </div>
    );
  }

  return (
    <div className="animate-fade-in space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="font-display text-2xl font-semibold text-text-bright">执行轨迹</h2>
          <p className="text-text-muted text-sm mt-1 font-mono">{workflowId}</p>
        </div>
        <div className="flex items-center gap-3">
          {loading && <Loader2 size={16} className="animate-spin text-text-muted" />}
          <span className={`px-3 py-1 rounded-full text-xs font-semibold ${
            status === 'SUCCESS' || status === 'COMPLETED' ? 'bg-success/20 text-success' :
            status === 'FAILED' ? 'bg-danger/20 text-danger' :
            'bg-cyan/20 text-cyan'
          }`}>
            {status || 'PENDING'}
          </span>
        </div>
      </div>

      {error && (
        <div className="bg-danger/10 border border-danger/30 rounded-lg px-4 py-3 text-sm text-danger">{error}</div>
      )}

      {/* Pipeline 可视化 — 签名元素 */}
      <div className="bg-space-900 border border-space-700 rounded-xl p-6">
        <div className="text-xs font-medium text-text-muted uppercase tracking-wider mb-4">BSP Pipeline</div>
        <PipelineView nodes={nodes} />
      </div>

      {/* 统计卡片 */}
      <div className="grid grid-cols-3 gap-4">
        {[
          { label: '总节点', value: nodes.length, unit: '个' },
          { label: '总耗时', value: nodes.reduce((s, n) => s + n.durationMs, 0), unit: 'ms' },
          { label: '总 Token', value: nodes.reduce((s, n) => s + n.totalTokens, 0), unit: '' },
        ].map(({ label, value, unit }) => (
          <div key={label} className="bg-space-900 border border-space-700 rounded-lg px-4 py-3">
            <div className="text-xs text-text-muted">{label}</div>
            <div className="font-display text-2xl font-semibold text-cyan mt-1">
              {value}<span className="text-sm text-text-muted ml-1">{unit}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
