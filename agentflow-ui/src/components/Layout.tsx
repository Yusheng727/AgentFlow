import React from 'react';
import { Play, GitBranch, Activity } from 'lucide-react';

interface LayoutProps {
  children: React.ReactNode;
  activeTab: string;
  onTabChange: (tab: 'submit' | 'monitor' | 'diagnosis') => void;
}

const tabs = [
  { id: 'submit' as const, label: '提交工作流', icon: Play },
  { id: 'monitor' as const, label: '执行轨迹', icon: GitBranch },
  { id: 'diagnosis' as const, label: '诊断报告', icon: Activity },
];

export function Layout({ children, activeTab, onTabChange }: LayoutProps) {
  return (
    <div className="flex h-screen bg-space-950 overflow-hidden">
      {/* 左侧导航 */}
      <aside className="w-56 border-r border-space-800 flex flex-col flex-shrink-0">
        <div className="px-6 py-5 border-b border-space-800">
          <h1 className="font-display text-xl font-bold text-cyan tracking-tight">AgentFlow</h1>
          <p className="text-xs text-text-muted mt-0.5">Multi-Agent 编排引擎</p>
        </div>
        <nav className="flex-1 px-3 py-4 space-y-1">
          {tabs.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              onClick={() => onTabChange(id)}
              className={`w-full flex items-center gap-3 px-4 py-2.5 rounded-lg text-sm font-medium transition-all duration-200 ${
                activeTab === id
                  ? 'bg-cyan/10 text-cyan border border-cyan/20'
                  : 'text-text-muted hover:text-text-bright hover:bg-space-800 border border-transparent'
              }`}
            >
              <Icon size={16} strokeWidth={activeTab === id ? 2.5 : 2} />
              {label}
            </button>
          ))}
        </nav>
        <div className="px-6 py-4 border-t border-space-800 text-xs text-text-muted">
          BSP 引擎 · Java 21 VT · PG Checkpoint
        </div>
      </aside>

      {/* 主内容 */}
      <main className="flex-1 overflow-y-auto">
        <div className="max-w-6xl mx-auto px-8 py-8">{children}</div>
      </main>
    </div>
  );
}
