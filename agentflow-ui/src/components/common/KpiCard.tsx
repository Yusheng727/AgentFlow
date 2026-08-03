import type { ReactNode } from 'react'

interface KpiCardProps {
  label: string
  value: string
  /** 头部左侧图标（有则与 label 同行，无则仅渲染纯文本 label）。 */
  icon?: ReactNode
  /** 底部副文案；不传则不渲染副文案行。 */
  sub?: string
  /** 副文案颜色类（默认 text-dim）。 */
  subClass?: string
  /** 数值颜色类（默认无额外色）。 */
  valueClass?: string
}

/** KPI 卡片共用外壳：看板与诊断页复用，保证视觉一致。 */
export function KpiCard({ label, value, icon, sub, subClass, valueClass }: KpiCardProps) {
  return (
    <div className="rounded-[10px] border border-line bg-surface p-4 shadow-card transition-all hover:border-accent hover:shadow-card-lg">
      {icon ? (
        <div className="flex items-center gap-1.5 text-xs text-muted">
          {icon}
          {label}
        </div>
      ) : (
        <div className="text-xs text-muted">{label}</div>
      )}
      <div className={`mt-1.5 text-[28px] font-bold tracking-tight ${valueClass ?? ''}`}>{value}</div>
      {sub !== undefined && <div className={`mt-0.5 text-xs ${subClass ?? 'text-dim'}`}>{sub}</div>}
    </div>
  )
}
