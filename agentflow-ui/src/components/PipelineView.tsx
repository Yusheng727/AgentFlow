import React from 'react';
import type { NodeTrace } from '../types';

interface PipelineViewProps {
  nodes: NodeTrace[];
}

export function PipelineView({ nodes }: PipelineViewProps) {
  // 按 super-step 分组（简化：按执行顺序分组）
  const groups: NodeTrace[][] = [];
  let current: NodeTrace[] = [];
  for (const node of nodes) {
    if (node.nodeId === 'aggregate-rating') {
      groups.push(current);
      groups.push([node]);
    } else {
      current.push(node);
    }
  }
  if (current.length > 0 && !groups.includes(current)) groups.push(current);

  return (
    <div className="flex items-start gap-3 overflow-x-auto pb-4">
      {groups.map((group, gi) => (
        <React.Fragment key={gi}>
          {/* super-step 列 */}
          <div className="flex-shrink-0">
            <div className="text-xs text-text-muted uppercase tracking-wider mb-3 px-1">
              Step {gi}
            </div>
            <div className="space-y-2">
              {group.map((node, ni) => (
                <NodeCard key={`${gi}-${ni}`} node={node} />
              ))}
            </div>
          </div>
          {/* 箭头 */}
          {gi < groups.length - 1 && (
            <div className="flex-shrink-0 flex items-center pt-10 text-text-muted">
              <svg width="32" height="24" viewBox="0 0 32 24" fill="none">
                <path d="M0 12h28M24 6l6 6-6 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" opacity="0.5"/>
              </svg>
            </div>
          )}
        </React.Fragment>
      ))}
    </div>
  );
}

function NodeCard({ node }: { node: NodeTrace }) {
  const isRunning = node.status === 'RUNNING';
  const isSuccess = node.status === 'SUCCESS';
  const isFailed = node.status === 'FAILED';

  const statusColor = isRunning
    ? 'border-cyan/40 bg-cyan/5 text-cyan'
    : isSuccess
    ? 'border-success/40 bg-success/5 text-success'
    : 'border-danger/40 bg-danger/5 text-danger';

  const icon = isRunning
    ? '◉'
    : isSuccess
    ? '✓'
    : '✗';

  return (
    <div className={`w-64 border rounded-lg px-4 py-3 transition-all duration-300 hover:shadow-lg ${statusColor}`}>
      <div className="flex items-center justify-between mb-2">
        <span className="font-mono text-sm font-medium truncate">{node.nodeId}</span>
        <span className="text-xs font-semibold">{icon} {node.status}</span>
      </div>
      <div className="space-y-1 text-xs opacity-80">
        <div className="flex justify-between">
          <span>Agent</span>
          <span className="font-mono">{node.agentName}</span>
        </div>
        <div className="flex justify-between">
          <span>耗时</span>
          <span className="font-mono">{node.durationMs}ms</span>
        </div>
        {node.totalTokens > 0 && (
          <div className="flex justify-between">
            <span>Token</span>
            <span className="font-mono">{node.totalTokens}</span>
          </div>
        )}
      </div>
      {node.outputSummary && (
        <div className="mt-2 pt-2 border-t border-current/10 text-xs truncate opacity-60">
          {node.outputSummary}
        </div>
      )}
    </div>
  );
}
