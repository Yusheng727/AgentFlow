import { useEffect, useRef, useState } from 'react'
import { mockDefinitions } from '../lib/mockData'
import type { TabId } from './Layout'

interface WorkflowDefinitionsProps {
  navigate: (tab: TabId) => void
  showToast: (msg: string) => void
  /** 选中定义并跳转提交页（预填名称）。 */
  onUseDefinition: (name: string) => void
}

/** 工作流定义 Tab：定义卡片网格 + 点击选中高亮 + 延迟跳转提交页预填（仿 prototype selectDefinition）。 */
export function WorkflowDefinitions({ navigate, showToast, onUseDefinition }: WorkflowDefinitionsProps) {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const timers = useRef<number[]>([])

  useEffect(() => {
    const pending = timers.current
    return () => pending.forEach((t) => window.clearTimeout(t))
  }, [])

  const handleSelect = (id: string, name: string) => {
    setSelectedId(id)
    showToast(`已选择工作流定义：${name}，正在跳转提交页…`)
    // 仿 prototype：500ms 延迟跳转，让用户看到选中高亮反馈
    timers.current.push(window.setTimeout(() => onUseDefinition(name), 500))
  }

  return (
    <div className="animate-fade-in">
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h2 className="text-[22px] font-bold tracking-tight">工作流定义</h2>
          <p className="mt-1 text-[13px] text-muted">管理已保存的 YAML 工作流定义，可复用、编辑、删除</p>
        </div>
        <button
          onClick={() => navigate('submit')}
          className="inline-flex items-center gap-2 rounded-lg bg-accent px-5 py-2.5 text-[13px] font-semibold text-white shadow-card transition-all hover:bg-accent-dim"
        >
          + 新建定义
        </button>
      </div>

      <div className="grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-4">
        {mockDefinitions.map((def) => (
          <div
            key={def.id}
            onClick={() => handleSelect(def.id, def.name)}
            className={`cursor-pointer rounded-[10px] border bg-surface p-4 shadow-card transition-all hover:border-accent hover:shadow-card-lg ${
              selectedId === def.id ? 'border-accent bg-accent-light' : 'border-line'
            }`}
          >
            <div className="mb-1 text-sm font-semibold">{def.name}</div>
            <div className="font-mono text-xs text-muted">
              v{def.version} · {def.nodes} 节点
            </div>
            <div className="mt-1.5 text-xs leading-relaxed text-muted">{def.desc}</div>
          </div>
        ))}
      </div>
    </div>
  )
}
