interface DiagnosisPanelProps {
  /** 全局选中的工作流 ID（从看板卡片「诊断」跳入时带入）。 */
  workflowId: string | null
}

/** 诊断报告 Tab —— U1 占位（诊断面板在 U2 实现）。 */
export function DiagnosisPanel({ workflowId }: DiagnosisPanelProps) {
  return (
    <div className="animate-fade-in">
      <div className="mb-6">
        <h2 className="text-[22px] font-bold tracking-tight">诊断报告</h2>
        <p className="mt-1 font-mono text-[13px] text-muted">{workflowId ?? '未选择工作流'}</p>
      </div>
      <div className="rounded-[10px] border border-line bg-surface p-8 text-center text-sm text-muted shadow-card">
        诊断面板将在 U2 实现
      </div>
    </div>
  )
}
