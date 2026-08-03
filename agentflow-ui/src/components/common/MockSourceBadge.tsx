interface MockSourceBadgeProps {
  /** 徽标文案；默认 "mock 模式"。 */
  label?: string
}

/** mock 数据来源徽标 pill；仅在调用方 source === 'mock' 时渲染。 */
export function MockSourceBadge({ label = 'mock 模式' }: MockSourceBadgeProps) {
  return (
    <span className="rounded-full bg-hover px-2 py-0.5 text-[11px] font-semibold text-dim">{label}</span>
  )
}
