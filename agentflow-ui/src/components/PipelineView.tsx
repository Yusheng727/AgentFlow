interface PipelineViewProps {
  /** 全局选中的工作流 ID（从看板卡片「查看轨迹」跳入时带入）。 */
  workflowId: string | null
}

/** 执行轨迹 Tab —— U1 占位（BSP Pipeline 可视化在 U2 实现）。 */
export function PipelineView({ workflowId }: PipelineViewProps) {
  return (
    <div className="animate-fade-in">
      <div className="mb-6">
        <h2 className="text-[22px] font-bold tracking-tight">执行轨迹</h2>
        <p className="mt-1 font-mono text-[13px] text-muted">{workflowId ?? '未选择工作流'}</p>
      </div>
      <div className="rounded-[10px] border border-line bg-surface p-8 text-center text-sm text-muted shadow-card">
        BSP Pipeline 可视化将在 U2 实现
      </div>
    </div>
  )
}
