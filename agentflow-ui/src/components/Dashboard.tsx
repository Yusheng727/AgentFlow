import { useEffect, useState } from 'react'
import { CheckCircle2, Clock, Play, Zap } from 'lucide-react'
import type { WorkflowStatusUi, WorkflowSummary } from '../types'
import { ApiError, listWorkflows, retryWorkflow } from '../lib/api'
import { durationChart, mockKpi, trendChart } from '../lib/mockData'
import type { TabId } from './Layout'
import { KpiCard } from './common/KpiCard'
import { MockSourceBadge } from './common/MockSourceBadge'

interface DashboardProps {
  onNavigate: (tab: TabId, workflowId?: string) => void
  showToast: (msg: string) => void
}

const statusBadge: Record<WorkflowStatusUi, string> = {
  running: 'bg-accent-light text-accent',
  success: 'bg-success-light text-success',
  failed: 'bg-danger-light text-danger',
}

const boardColumns: { key: WorkflowStatusUi; label: string; dotClass: string }[] = [
  { key: 'running', label: '进行中', dotClass: 'text-accent' },
  { key: 'success', label: '已完成', dotClass: 'text-success' },
  { key: 'failed', label: '失败', dotClass: 'text-danger' },
]

/** 看板 Tab：KPI 行 + 图表占位 + 三列看板 + 最近执行表格。 */
export function Dashboard({ onNavigate, showToast }: DashboardProps) {
  const [workflows, setWorkflows] = useState<WorkflowSummary[]>([])
  const [source, setSource] = useState<'api' | 'mock'>('mock')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    listWorkflows()
      .then((r) => {
        if (cancelled) return
        setWorkflows(r.workflows)
        setSource(r.source)
        setLoading(false)
      })
      .catch(() => {
        // withMockFallback 已兜底，理论不到这里；防御性处理避免白屏
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const total = workflows.length
  const succeeded = workflows.filter((w) => w.status === 'success').length
  const successRate = total > 0 ? `${((succeeded / total) * 100).toFixed(1)}%` : '0%'

  const handleRetry = (wf: WorkflowSummary) => {
    retryWorkflow(wf.id)
      .then(() => showToast(`重试已提交：${wf.name}`))
      .catch((err: unknown) => {
        // 后端可达但拒绝（如 401/403）——真实拒绝，不能当 mock 成功
        if (err instanceof ApiError) {
          showToast(`重试失败：HTTP ${err.status}`)
          return
        }
        showToast(`（mock）重试已提交：${wf.name} — 后端不可达，仅演示`)
      })
  }

  return (
    <div className="animate-fade-in">
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h2 className="text-[22px] font-bold tracking-tight">工作流看板</h2>
          <p className="mt-1 flex items-center gap-2 text-[13px] text-muted">
            BSP 引擎执行概览与状态监控
            {source === 'mock' && <MockSourceBadge />}
          </p>
        </div>
        <button
          onClick={() => onNavigate('submit')}
          className="inline-flex items-center gap-2 rounded-lg bg-accent px-5 py-2.5 text-[13px] font-semibold text-white shadow-card transition-all hover:bg-accent-dim"
        >
          + 提交新工作流
        </button>
      </div>

      {/* KPI 行 */}
      <div className="mb-5 grid grid-cols-4 gap-3 max-[960px]:grid-cols-2">
        <KpiCard icon={<Play size={14} />} label="今日执行" value={String(total)} sub="↑ 3 vs 昨日" subClass="text-success" />
        <KpiCard icon={<CheckCircle2 size={14} />} label="成功率" value={successRate} sub={`${succeeded}/${total} 成功`} />
        <KpiCard icon={<Clock size={14} />} label="平均耗时" value={mockKpi.avgDuration} sub={mockKpi.avgDurationTrend} subClass="text-danger" />
        <KpiCard icon={<Zap size={14} />} label="Token 消耗" value={mockKpi.tokenTotal} sub={source === 'mock' ? mockKpi.tokenHint : '真实调用'} />
      </div>

      {/* 图表行（占位，U3 指标端点上线后接真实数据） */}
      <div className="mb-5 grid grid-cols-2 gap-3">
        <ChartPanel title="执行趋势（7 天）" bars={trendChart.bars.map((h) => ({ height: h, color: 'bg-accent' }))} labels={trendChart.labels} />
        <ChartPanel title="节点耗时分布" bars={durationChart.bars} labels={durationChart.labels} />
      </div>

      {/* 三列看板 */}
      <div className="mb-5 grid grid-cols-3 gap-4 max-[960px]:grid-cols-1">
        {boardColumns.map((col) => {
          const group = workflows.filter((w) => w.status === col.key)
          return (
            <div key={col.key} className="flex flex-col gap-2.5">
              <div className="mb-2 flex items-center gap-2 border-b border-line pb-2 text-[13px] font-semibold text-muted">
                <span className={col.dotClass}>●</span>
                {col.label}
                <span data-testid={`count-${col.key}`} className="rounded-full bg-hover px-2 py-0.5 text-[11px] font-semibold">{group.length}</span>
              </div>
              {loading ? (
                <div className="py-6 text-center text-xs text-dim">加载中…</div>
              ) : (
                group.map((wf) => (
                  <WorkflowCard key={wf.id} wf={wf} onNavigate={onNavigate} onRetry={() => handleRetry(wf)} />
                ))
              )}
            </div>
          )
        })}
      </div>

      {/* 最近执行表格 */}
      <div className="overflow-hidden rounded-[10px] border border-line bg-surface shadow-card">
        <div className="border-b border-line px-4 py-3 text-[13px] font-semibold text-muted">最近执行</div>
        <table className="w-full border-collapse text-[13px]">
          <thead>
            <tr>
              {['工作流', '状态', '节点', '耗时', '时间', '操作'].map((h) => (
                <th key={h} className="border-b border-line bg-hover px-3.5 py-2.5 text-left text-[11px] font-semibold uppercase tracking-wider text-muted">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {workflows.map((wf) => (
              <tr key={wf.id} className="group">
                <td className="border-b border-line px-3.5 py-2.5 font-mono group-hover:bg-hover">{wf.name}</td>
                <td className="border-b border-line px-3.5 py-2.5 group-hover:bg-hover">
                  <span className={`rounded-full px-2.5 py-0.5 text-[11px] font-semibold ${statusBadge[wf.status]}`}>{wf.status}</span>
                </td>
                <td className="border-b border-line px-3.5 py-2.5 group-hover:bg-hover">{wf.nodes}</td>
                <td className="border-b border-line px-3.5 py-2.5 font-mono group-hover:bg-hover">{wf.time}</td>
                <td className="border-b border-line px-3.5 py-2.5 text-dim group-hover:bg-hover">{wf.date}</td>
                <td className="border-b border-line px-3.5 py-2.5 group-hover:bg-hover">
                  <div className="flex gap-2">
                    <button onClick={() => onNavigate('trace', wf.id)} className="rounded-md border border-line bg-surface px-3 py-1 text-xs font-medium text-muted transition-all hover:border-accent hover:text-accent">
                      轨迹
                    </button>
                    <button onClick={() => onNavigate('diagnosis', wf.id)} className="rounded-md border border-line bg-surface px-3 py-1 text-xs font-medium text-muted transition-all hover:border-accent hover:text-accent">
                      诊断
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

// ──────────────────────────── 子组件 ────────────────────────────

function ChartPanel({ title, bars, labels }: { title: string; bars: { height: number; color: string }[]; labels: string[] }) {
  return (
    <div className="overflow-hidden rounded-[10px] border border-line bg-surface shadow-card">
      <div className="border-b border-line px-4 py-3 text-[13px] font-semibold text-muted">{title}</div>
      <div className="p-4">
        <div className="flex h-[100px] items-end gap-[3px] py-2">
          {bars.map((bar, i) => (
            <div
              key={i}
              className={`flex-1 rounded-t-sm ${bar.color} opacity-80 transition-opacity hover:opacity-100`}
              style={{ height: `${bar.height}%` }}
            />
          ))}
        </div>
        <div className="flex justify-between pt-1 text-[10px] text-dim">
          {labels.map((l) => (
            <span key={l}>{l}</span>
          ))}
        </div>
      </div>
    </div>
  )
}

function WorkflowCard({ wf, onNavigate, onRetry }: { wf: WorkflowSummary; onNavigate: (tab: TabId, workflowId?: string) => void; onRetry: () => void }) {
  const [expanded, setExpanded] = useState(false)
  return (
    <div
      onClick={() => setExpanded((v) => !v)}
      className={`cursor-pointer rounded-[10px] border bg-surface p-3.5 shadow-card transition-all hover:-translate-y-px hover:border-accent hover:shadow-card-lg ${expanded ? 'border-accent' : 'border-line'}`}
    >
      <div className="mb-2 flex items-center justify-between">
        <span className="text-sm font-semibold">{wf.name}</span>
        <span className={`rounded-full px-2.5 py-0.5 text-[11px] font-semibold ${statusBadge[wf.status]}`}>
          {wf.status.toUpperCase()}
        </span>
      </div>
      <div className="flex gap-4 font-mono text-xs text-muted">
        <span>{wf.nodes} 节点</span>
        <span>{wf.steps} super-step</span>
        <span>{wf.time}</span>
      </div>
      {expanded && (
        <div className="mt-3 border-t border-line pt-3 text-xs leading-relaxed text-muted">
          <p>{wf.desc}</p>
          <div className="mt-2.5 flex gap-2">
            <button
              onClick={(e) => {
                e.stopPropagation()
                onNavigate('trace', wf.id)
              }}
              className="rounded-md border border-line bg-surface px-3 py-1 text-xs font-medium text-muted transition-all hover:border-accent hover:text-accent"
            >
              查看轨迹
            </button>
            <button
              onClick={(e) => {
                e.stopPropagation()
                onNavigate('diagnosis', wf.id)
              }}
              className="rounded-md border border-accent bg-accent px-3 py-1 text-xs font-medium text-white transition-all hover:bg-accent-dim"
            >
              诊断
            </button>
            {wf.status === 'failed' && (
              <button
                onClick={(e) => {
                  e.stopPropagation()
                  onRetry()
                }}
                className="rounded-md border border-line bg-surface px-3 py-1 text-xs font-medium text-muted transition-all hover:border-accent hover:text-accent"
              >
                重试
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
