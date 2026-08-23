import { useCallback, useEffect, useState } from 'react'
import { CheckCircle2, Clock, RefreshCw, ShieldCheck, XCircle } from 'lucide-react'
import type { ApprovalSummary } from '../types'
import { ApiError, decideApproval, listPendingApprovals } from '../lib/api'
import { MockSourceBadge } from './common/MockSourceBadge'

interface ApprovalCenterProps {
  showToast: (msg: string) => void
}

/**
 * 审批中心 Tab（U4）：跨工作流待批聚合 + APPROVE/REJECT 决策。
 *
 * <p>数据源 GET /api/approvals/pending（U3 聚合端点，真实 API 优先 + mock fallback
 * 与既有 5 Tab 同模式）；决策 POST 走 per-workflow 端点（ApprovalController），写操作
 * 无 mock fallback——失败 toast 报错（对齐 retry 纪律）。决策成功后乐观移除该行 + toast。
 */
export function ApprovalCenter({ showToast }: ApprovalCenterProps) {
  const [approvals, setApprovals] = useState<ApprovalSummary[]>([])
  const [source, setSource] = useState<'api' | 'mock'>('mock')
  const [loading, setLoading] = useState(true)
  const [deciding, setDeciding] = useState<Record<string, boolean>>({})

  const reload = useCallback(() => {
    setLoading(true)
    listPendingApprovals()
      .then((r) => {
        setApprovals(r.approvals)
        setSource(r.source)
        setLoading(false)
      })
      .catch(() => {
        // withMockFallback 已兜底，防御性处理避免白屏
        setLoading(false)
      })
  }, [])

  useEffect(() => {
    reload()
  }, [reload])

  const handleDecide = (a: ApprovalSummary, decision: 'APPROVE' | 'REJECT') => {
    setDeciding((prev) => ({ ...prev, [a.approvalId]: true }))
    decideApproval(a.workflowId, a.approvalId, decision)
      .then((res) => {
        // 乐观移除已决策行；决策结果状态 toast 透出（SUCCESS/FAILED/AWAITING_APPROVAL——多级审批链）
        setApprovals((prev) => prev.filter((x) => x.approvalId !== a.approvalId))
        showToast(`已${decision === 'APPROVE' ? '批准' : '拒绝'} ${a.nodeId}：工作流 → ${res.workflowStatus}`)
      })
      .catch((err: unknown) => {
        // 写操作不 mock：后端可达但拒绝（401/403/400）→ 真实拒绝；不可达 → 提示离线
        if (err instanceof ApiError) {
          showToast(`决策失败：HTTP ${err.status}`)
        } else {
          showToast('决策失败：后端不可达（写操作不做 mock 演示）')
        }
      })
      .finally(() => {
        setDeciding((prev) => {
          const next = { ...prev }
          delete next[a.approvalId]
          return next
        })
      })
  }

  return (
    <div className="animate-fade-in">
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h2 className="text-[22px] font-bold tracking-tight">审批中心</h2>
          <p className="mt-1 flex items-center gap-2 text-[13px] text-muted">
            跨工作流待批聚合（HITL：中断 → 人工决策 → 恢复续跑）
            {source === 'mock' && <MockSourceBadge />}
          </p>
        </div>
        <button
          onClick={reload}
          disabled={loading}
          className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface px-4 py-2.5 text-[13px] font-semibold text-ink shadow-card transition-all hover:border-accent hover:text-accent disabled:cursor-not-allowed disabled:opacity-60"
        >
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
          刷新
        </button>
      </div>

      {loading ? (
        <div className="py-16 text-center text-sm text-dim">加载中…</div>
      ) : approvals.length === 0 ? (
        <EmptyState />
      ) : (
        <div className="flex flex-col gap-3">
          {approvals.map((a) => (
            <ApprovalCard
              key={a.approvalId}
              a={a}
              deciding={deciding[a.approvalId] === true}
              onDecide={handleDecide}
            />
          ))}
        </div>
      )}
    </div>
  )
}

// ──────────────────────────── 子组件 ────────────────────────────

function ApprovalCard({
  a,
  deciding,
  onDecide,
}: {
  a: ApprovalSummary
  deciding: boolean
  onDecide: (a: ApprovalSummary, decision: 'APPROVE' | 'REJECT') => void
}) {
  return (
    <div className="rounded-[10px] border border-line bg-surface p-4 shadow-card transition-all hover:border-accent hover:shadow-card-lg">
      <div className="mb-2 flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <span className="inline-flex items-center gap-1.5 rounded-full bg-warning-light px-2.5 py-0.5 text-[11px] font-semibold text-warning">
          <ShieldCheck size={11} strokeWidth={2.5} />
          PENDING
        </span>
        <span className="text-sm font-semibold">{a.nodeId}</span>
        <span className="font-mono text-xs text-dim">{a.workflowId}</span>
        {a.createdAt && (
          <span className="ml-auto inline-flex items-center gap-1 text-[11px] text-dim">
            <Clock size={11} />
            {new Date(a.createdAt).toLocaleString()}
          </span>
        )}
      </div>
      {a.description && <p className="mb-3 text-[13px] leading-relaxed text-muted">{a.description}</p>}
      <div className="flex justify-end gap-2">
        <button
          onClick={() => onDecide(a, 'APPROVE')}
          disabled={deciding}
          className="inline-flex items-center gap-1.5 rounded-md border border-success bg-success px-4 py-1.5 text-xs font-semibold text-white transition-all hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-60"
        >
          <CheckCircle2 size={13} />
          批准
        </button>
        <button
          onClick={() => onDecide(a, 'REJECT')}
          disabled={deciding}
          className="inline-flex items-center gap-1.5 rounded-md border border-danger bg-danger px-4 py-1.5 text-xs font-semibold text-white transition-all hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-60"
        >
          <XCircle size={13} />
          拒绝
        </button>
      </div>
    </div>
  )
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center gap-2 rounded-[10px] border border-dashed border-line bg-surface py-16 text-center">
      <CheckCircle2 size={28} className="text-success" />
      <p className="text-sm font-semibold text-ink">暂无待批审批</p>
      <p className="text-xs text-dim">所有工作流无需人工决策，或有审批的工作流均不在你的可见域</p>
    </div>
  )
}
