import { useState } from 'react'
import { AlertTriangle, CheckCircle2, Clock, Code, Repeat, Unplug, Zap, type LucideIcon } from 'lucide-react'
import { diagnoseWorkflow, getWorkflowTrace } from '../lib/api'
import { mockDiagnosisReport, mockTrace } from '../lib/mockData'
import type { Diagnosis, DiagnosisReport } from '../types'

interface DiagnosisPanelProps {
  /** 全局选中的工作流 ID（从看板卡片「诊断」跳入时带入）。 */
  workflowId: string | null
}

/** 后端 DiagnosisService 的 5 类问题 → 图标（卡片左侧视觉锚点）。 */
const findingIcon: Record<string, LucideIcon> = {
  连续超时: Clock,
  'Token 异常消耗': Zap,
  'SpEL 解析失败': Code,
  'Channel 缺失': Unplug,
  节点重复执行: Repeat,
}

function formatElapsed(ms: number): string {
  return ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(1)}s`
}

/** 诊断报告 Tab：KPI 摘要 + 5 类问题卡片；真实 API 优先，不可达降级 mock 报告（spinner→结果全流程）。 */
export function DiagnosisPanel({ workflowId }: DiagnosisPanelProps) {
  const [running, setRunning] = useState(false)
  const [report, setReport] = useState<DiagnosisReport | null>(null)
  const [elapsed, setElapsed] = useState<string>('-')
  const [source, setSource] = useState<'api' | 'mock'>('mock')

  const runDiagnosis = () => {
    const effectiveId = workflowId ?? mockTrace.workflowId
    setRunning(true)
    const t0 = performance.now()
    // 先取 trace（U3 端点，未上线自动降级 mock），再 POST 诊断
    getWorkflowTrace(effectiveId)
      .then((r) => diagnoseWorkflow(effectiveId, r.trace))
      .then((rep) => {
        setReport(rep)
        setSource('api')
      })
      .catch(() => {
        // 后端不可达：降级 mock 报告，保证演示不白屏
        setReport({ ...mockDiagnosisReport, workflowId: effectiveId })
        setSource('mock')
      })
      .finally(() => {
        setElapsed(formatElapsed(performance.now() - t0))
        setRunning(false)
      })
  }

  return (
    <div className="animate-fade-in">
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h2 className="text-[22px] font-bold tracking-tight">诊断报告</h2>
          <p className="mt-1 flex items-center gap-2 font-mono text-[13px] text-muted">
            {workflowId ?? '未选择工作流（演示数据）'}
            {report !== null && source === 'mock' && (
              <span className="rounded-full bg-hover px-2 py-0.5 font-sans text-[11px] font-semibold text-dim">
                mock 模式
              </span>
            )}
          </p>
        </div>
        <button
          onClick={runDiagnosis}
          disabled={running}
          className="inline-flex items-center gap-2 rounded-lg bg-accent px-5 py-2.5 text-[13px] font-semibold text-white shadow-card transition-all hover:bg-accent-dim disabled:cursor-not-allowed disabled:opacity-70"
        >
          {running && (
            <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/30 border-t-white" />
          )}
          {running ? '分析中...' : '运行诊断'}
        </button>
      </div>

      {/* KPI 摘要 */}
      <div className="mb-5 grid grid-cols-4 gap-3 max-[960px]:grid-cols-2">
        <KpiCard label="总节点" value={report ? String(report.totalNodes) : '-'} />
        <KpiCard
          label="失败节点"
          value={report ? String(report.failedNodes) : '-'}
          valueClass={report && report.failedNodes > 0 ? 'text-danger' : 'text-success'}
        />
        <KpiCard
          label="发现问题"
          value={report ? String(report.findings.length) : '-'}
          valueClass={report && report.findings.length > 0 ? 'text-warning' : 'text-success'}
        />
        <KpiCard label="诊断耗时" value={elapsed} />
      </div>

      {/* 诊断结果 */}
      {report === null ? (
        <div className="rounded-[10px] border border-line bg-surface p-8 text-center text-sm text-muted shadow-card">
          点击「运行诊断」分析该工作流的执行轨迹（超时 / Token 异常 / 解析失败 / Channel 缺失 / 重复执行）
        </div>
      ) : report.findings.length === 0 ? (
        <div className="rounded-[10px] border border-success/30 bg-success-light p-8 text-center shadow-card">
          <div className="flex justify-center text-success">
            <CheckCircle2 size={22} />
          </div>
          <div className="mt-2 text-base font-semibold text-success">无异常发现</div>
          <p className="mt-1 text-[13px] text-muted">工作流执行正常：无超时、无异常 token 消耗、无解析错误</p>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {report.findings.map((f, i) => (
            <FindingCard key={`${f.nodeId}-${i}`} finding={f} />
          ))}
        </div>
      )}
    </div>
  )
}

function KpiCard({ label, value, valueClass }: { label: string; value: string; valueClass?: string }) {
  return (
    <div className="rounded-[10px] border border-line bg-surface p-4 shadow-card transition-all hover:border-accent hover:shadow-card-lg">
      <div className="text-xs text-muted">{label}</div>
      <div className={`mt-1.5 text-[28px] font-bold tracking-tight ${valueClass ?? ''}`}>{value}</div>
    </div>
  )
}

function FindingCard({ finding }: { finding: Diagnosis }) {
  const Icon = findingIcon[finding.problemType] ?? AlertTriangle
  return (
    <div className="rounded-[10px] border border-line border-l-[3px] border-l-warning bg-surface p-4 shadow-card transition-all hover:border-warning">
      <div className="mb-1.5 flex items-center gap-2.5">
        <span className="text-warning">
          <Icon size={17} />
        </span>
        <span className="text-sm font-semibold text-warning">{finding.problemType}</span>
        <span className="rounded bg-hover px-2 py-0.5 font-mono text-[11px] text-dim">{finding.nodeId}</span>
      </div>
      <div className="text-[13px] leading-relaxed text-ink">{finding.description}</div>
      <div className="mt-1.5 text-xs italic text-muted">建议：{finding.suggestion}</div>
    </div>
  )
}
