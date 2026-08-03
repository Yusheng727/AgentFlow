import { useEffect, useState } from 'react'
import { CheckCircle2, Loader2, XCircle } from 'lucide-react'
import { getWorkflowTrace } from '../lib/api'
import { mockTrace } from '../lib/mockData'
import type { ExecutionTraceSnapshot, NodeTrace } from '../types'
import { MockSourceBadge } from './common/MockSourceBadge'

interface PipelineViewProps {
  /** 全局选中的工作流 ID（从看板卡片「查看轨迹」跳入时带入）。 */
  workflowId: string | null
}

const statusBadge: Record<ExecutionTraceSnapshot['status'], { text: string; cls: string }> = {
  COMPLETED: { text: '✓ SUCCESS', cls: 'bg-success-light text-success' },
  RUNNING: { text: '● RUNNING', cls: 'bg-accent-light text-accent' },
  FAILED: { text: '✗ FAILED', cls: 'bg-danger-light text-danger' },
}

/**
 * 把扁平 NodeTrace 列表切成 super-step 分组。
 * v1 展示约定：ExecutionTrace.Snapshot 不含 super-step 信息（待 U3 TraceController 增强），
 * 按 prototype 的 supplier-risk 形态——末位节点为汇总步，其余并列为 Step 0。
 */
function groupIntoSteps(nodes: NodeTrace[]): NodeTrace[][] {
  if (nodes.length <= 1) return nodes.length === 0 ? [] : [nodes]
  return [nodes.slice(0, -1), [nodes[nodes.length - 1]]]
}

function stepLabel(index: number, group: NodeTrace[], totalSteps: number): string {
  if (group.length > 1) return `Step ${index} · 并行 ${group.length} 节点`
  if (totalSteps > 1 && index === totalSteps - 1) return `Step ${index} · 汇总`
  return `Step ${index} · 单节点`
}

/** 执行轨迹 Tab：BSP Pipeline 可视化（super-step 分组 + barrier 标记），真实 trace 优先 + mock 降级。 */
export function PipelineView({ workflowId }: PipelineViewProps) {
  const [trace, setTrace] = useState<ExecutionTraceSnapshot | null>(null)
  const [source, setSource] = useState<'api' | 'mock'>('mock')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    // 未选工作流时用演示 id——mock 降级路径保证页面可浏览
    getWorkflowTrace(workflowId ?? mockTrace.workflowId)
      .then((r) => {
        if (cancelled) return
        setTrace(r.trace)
        setSource(r.source)
        setLoading(false)
      })
      .catch(() => {
        // withMockFallback 已兜底，理论不到这里；防御性处理避免白屏
        if (!cancelled) {
          setTrace(mockTrace)
          setSource('mock')
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [workflowId])

  const steps = trace ? groupIntoSteps(trace.nodes) : []
  const badge = trace ? statusBadge[trace.status] : null

  return (
    <div className="animate-fade-in">
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h2 className="text-[22px] font-bold tracking-tight">执行轨迹</h2>
          <p className="mt-1 flex items-center gap-2 font-mono text-[13px] text-muted">
            {workflowId ?? '未选择工作流（演示数据）'}
            {source === 'mock' && <MockSourceBadge />}
          </p>
        </div>
        {badge && (
          <span className={`rounded-full px-4 py-1.5 text-[13px] font-semibold ${badge.cls}`}>{badge.text}</span>
        )}
      </div>

      <div className="mt-6 rounded-xl border border-line bg-surface p-6 shadow-card">
        <div className="mb-4 flex items-center gap-2 text-sm font-semibold">
          BSP Pipeline（{loading ? '…' : `${steps.length} super-step`}）
        </div>
        {loading ? (
          <div className="flex items-center gap-2 py-10 text-[13px] text-dim">
            <Loader2 size={15} className="animate-spin" />
            轨迹加载中…
          </div>
        ) : steps.length === 0 ? (
          <div className="py-10 text-center text-[13px] text-dim">该工作流暂无节点轨迹</div>
        ) : (
          <div className="flex items-start gap-3 overflow-x-auto pb-3">
            {steps.map((group, i) => (
              <div key={i} className="flex items-start gap-3">
                {i > 0 && <BarrierMarker />}
                <div className="flex-shrink-0">
                  <div className="mb-2.5 inline-block rounded-md bg-hover px-2.5 py-1 text-[11px] font-semibold text-muted">
                    {stepLabel(i, group, steps.length)}
                  </div>
                  <div className="flex flex-col gap-2.5">
                    {group.map((n) => (
                      <NodeCard key={n.nodeId} node={n} />
                    ))}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

/** BSP barrier 标记：竖线 + 竖排文字（对齐 prototype 的 barrier-marker）。 */
function BarrierMarker() {
  return (
    <div className="flex flex-shrink-0 flex-col items-center gap-1 pt-6 text-dim">
      <div className="h-10 w-px bg-line" />
      <div className="text-[10px] tracking-widest [writing-mode:vertical-rl]">barrier</div>
      <div className="h-10 w-px bg-line" />
    </div>
  )
}

const nodeStatusStyle: Record<NodeTrace['status'], { border: string; icon: React.ReactNode }> = {
  SUCCESS: { border: 'border-l-success', icon: <CheckCircle2 size={13} className="text-success" /> },
  RUNNING: { border: 'border-l-accent', icon: <Loader2 size={13} className="animate-spin text-accent" /> },
  FAILED: { border: 'border-l-danger', icon: <XCircle size={13} className="text-danger" /> },
}

function NodeCard({ node }: { node: NodeTrace }) {
  const st = nodeStatusStyle[node.status]
  return (
    <div
      className={`w-60 cursor-pointer rounded-lg border border-line border-l-[3px] bg-surface p-3 transition-all hover:border-accent hover:shadow-card-lg ${st.border}`}
    >
      <div className="mb-1.5 flex items-center justify-between">
        <span className="font-mono text-[13px] font-semibold">{node.nodeId}</span>
        {st.icon}
      </div>
      <div className="mt-1 flex justify-between text-[11px] text-dim">
        <span>Agent</span>
        <span className="font-mono">{node.agentName}</span>
      </div>
      <div className="mt-1 flex justify-between text-[11px] text-dim">
        <span>耗时</span>
        <span className="font-mono">{node.durationMs}ms</span>
      </div>
      {node.totalTokens > 0 && (
        <div className="mt-1 flex justify-between text-[11px] text-dim">
          <span>Token</span>
          <span className="font-mono">{node.totalTokens.toLocaleString()}</span>
        </div>
      )}
      {node.outputSummary !== null && (
        <div className="mt-2 overflow-hidden text-ellipsis whitespace-nowrap rounded bg-hover p-2 font-mono text-[11px] text-muted">
          {node.outputSummary}
        </div>
      )}
      {node.error !== null && (
        <div className="mt-2 overflow-hidden text-ellipsis whitespace-nowrap rounded bg-danger-light p-2 font-mono text-[11px] text-danger">
          {node.error}
        </div>
      )}
    </div>
  )
}
