import type { ReactNode } from 'react'
import {
  AlertCircle,
  FileText,
  GitBranch,
  LayoutDashboard,
  Play,
  ShieldCheck,
  type LucideIcon,
} from 'lucide-react'

export type TabId = 'dashboard' | 'submit' | 'definitions' | 'approval-center' | 'trace' | 'diagnosis'

interface NavItem {
  id: TabId
  label: string
  icon: LucideIcon
}

const navSections: { title: string; items: NavItem[] }[] = [
  {
    title: '操作',
    items: [
      { id: 'dashboard', label: '看板', icon: LayoutDashboard },
      { id: 'submit', label: '提交工作流', icon: Play },
      { id: 'definitions', label: '工作流定义', icon: FileText },
      { id: 'approval-center', label: '审批中心', icon: ShieldCheck },
    ],
  },
  {
    title: '分析',
    items: [
      { id: 'trace', label: '执行轨迹', icon: GitBranch },
      { id: 'diagnosis', label: '诊断报告', icon: AlertCircle },
    ],
  },
]

interface LayoutProps {
  activeTab: TabId
  onTabChange: (tab: TabId) => void
  children: ReactNode
}

/** A 风格布局：深色侧边栏（5 菜单）+ 浅色主内容区，仿 prototype-final.html。 */
export function Layout({ activeTab, onTabChange, children }: LayoutProps) {
  return (
    <div className="flex h-screen overflow-hidden">
      <aside className="flex w-[232px] flex-shrink-0 flex-col bg-sidebar">
        <div className="border-b border-white/10 px-6 py-5">
          <h1 className="text-lg font-bold tracking-tight text-accent">AgentFlow</h1>
          <p className="mt-0.5 text-[11px] text-white/40">Multi-Agent 编排引擎</p>
        </div>
        <nav className="flex-1 p-3">
          {navSections.map((section) => (
            <div key={section.title}>
              <div className="px-3 pb-1 pt-2 text-[10px] font-semibold uppercase tracking-wider text-white/30">
                {section.title}
              </div>
              {section.items.map(({ id, label, icon: Icon }) => (
                <button
                  key={id}
                  onClick={() => onTabChange(id)}
                  className={`mb-0.5 flex w-full items-center gap-2.5 rounded-md border px-3 py-2 text-[13px] font-medium transition-all duration-150 ${
                    activeTab === id
                      ? 'border-sidebar-active-border bg-sidebar-active text-accent'
                      : 'border-transparent text-white/60 hover:bg-sidebar-hover hover:text-white'
                  }`}
                >
                  <Icon size={15} strokeWidth={2} />
                  {label}
                </button>
              ))}
            </div>
          ))}
        </nav>
        <div className="border-t border-white/10 px-5 py-4 text-[11px] leading-relaxed text-white/30">
          BSP 引擎 · Java 21 VT · PG Checkpoint
        </div>
      </aside>

      <main className="flex-1 overflow-y-auto bg-page">
        <div className="mx-auto max-w-[1280px] px-8 py-7">{children}</div>
      </main>
    </div>
  )
}
