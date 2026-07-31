import { useEffect, useMemo, useRef } from 'react'

interface YamlEditorProps {
  /** YAML 全文（受控）。外部变更（如预填定义）会重置编辑器内容。 */
  value: string
  onChange: (text: string) => void
  /** 编辑器头部左侧显示的文件名。 */
  fileName?: string
  /** 最少展示的行号数（视觉对齐 prototype）。 */
  minLines?: number
}

/** 轻量 YAML 校验：只检查关键段存在性（与 prototype 行为一致，结构校验由后端 DSL Parser 兜底）。 */
function validate(text: string): { ok: boolean; message: string } {
  if (!text.includes('nodes:')) return { ok: false, message: '⚠ 缺少 nodes 段' }
  if (!text.includes('agentflow:')) return { ok: false, message: '⚠ 缺少 agentflow 声明' }
  return { ok: true, message: '✓ 校验通过' }
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/**
 * YAML 语法高亮（手写，零依赖；类名样式在 index.css 的 .yh-*）。
 * 替换顺序经过调整：字符串先于注释/键——若先包 span，span 属性里的引号会被
 * 字符串正则二次匹配，产出破损 HTML（prototype 原版存在该问题，此处修复）。
 */
function highlightYaml(text: string): string {
  return escapeHtml(text)
    .replace(/"([^"]*)"/g, '<span class="yh-string">"$1"</span>')
    .replace(/^(#.*)$/gm, '<span class="yh-comment">$1</span>')
    .replace(/^(\s*)([a-zA-Z_-]+):/gm, '$1<span class="yh-key">$2</span>:')
    .replace(/\$\{([^}]+)\}/g, '<span class="yh-placeholder">\${$1}</span>')
}

/**
 * YAML 编辑器：contenteditable + 行号 + 语法高亮 + 实时校验（仿 prototype-final.html）。
 *
 * 光标保护策略：仅在外部 value 变更（预填）时重写 innerHTML；用户输入期间不重排
 * 高亮（与 prototype 一致——输入只刷新行号与校验状态，避免光标跳动）。
 */
export function YamlEditor({ value, onChange, fileName = 'workflow.yml', minLines = 10 }: YamlEditorProps) {
  const contentRef = useRef<HTMLDivElement>(null)
  // 记录最近一次由用户输入同步出去的文本，用于区分「外部预填」与「自身输入」
  const lastEmitted = useRef<string | null>(null)

  // 外部 value 变更（非自身输入回环）时重置编辑器内容并重新高亮
  useEffect(() => {
    const el = contentRef.current
    if (el && value !== lastEmitted.current) {
      el.innerHTML = highlightYaml(value)
    }
  }, [value])

  const lineCount = useMemo(() => Math.max(value.split('\n').length, minLines), [value, minLines])
  const status = useMemo(() => validate(value), [value])

  const handleInput = () => {
    const el = contentRef.current
    if (!el) return
    const text = el.innerText.replace(/\n$/, '') // contenteditable 尾部多一个换行
    lastEmitted.current = text
    onChange(text)
  }

  return (
    <div className="overflow-hidden rounded-[10px] border border-line bg-surface shadow-card">
      <div className="flex items-center justify-between border-b border-line px-4 py-2.5 text-xs text-muted">
        <span className="font-mono">{fileName}</span>
        <span className={`text-[11px] font-medium ${status.ok ? 'text-success' : 'text-warning'}`}>
          {status.message}
        </span>
      </div>
      <div className="relative">
        <div
          aria-hidden
          className="absolute bottom-0 left-0 top-0 w-10 select-none border-r border-line bg-hover px-2 py-3 text-right"
        >
          {Array.from({ length: lineCount }, (_, i) => (
            <span key={i} className="block font-mono text-[11px] leading-[22px] text-dim">
              {i + 1}
            </span>
          ))}
        </div>
        <div
          ref={contentRef}
          contentEditable
          suppressContentEditableWarning
          spellCheck={false}
          onInput={handleInput}
          onPaste={(e) => {
            // 强制纯文本粘贴，防止富文本破坏高亮 span 结构
            e.preventDefault()
            const text = e.clipboardData.getData('text/plain')
            document.execCommand('insertText', false, text)
          }}
          className="min-h-[400px] whitespace-pre-wrap py-3 pl-[52px] pr-4 font-mono text-[13px] leading-[22px] outline-none"
        />
      </div>
    </div>
  )
}
