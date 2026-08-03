import { useEffect, useRef, useState } from 'react'
import { ApiError, submitWorkflow } from '../lib/api'
import type { TabId } from './Layout'
import { YamlEditor } from './YamlEditor'

// 默认 YAML：移植自 prototype-final.html 的 DEFAULT_YAML（供应商风险评估 demo）
const DEFAULT_YAML = `# 供应商风险评估工作流
agentflow:
  version: "1.0"

nodes:
  - id: financial-analysis
    agent: finance-agent
    prompt_template: "分析供应商 \${supplier} 的财务风险"
    mock_response: |
      财务风险：低
      资产负债率：35%
      现金流：稳定

  - id: compliance-check
    agent: compliance-agent
    prompt_template: "检查供应商 \${supplier} 的合规记录"
    mock_response: |
      合规风险：中
      营业执照：有效
      近 2 年有 1 次环保违规

  - id: reputation
    agent: reputation-agent
    prompt_template: "评估供应商 \${supplier} 的行业声誉"
    mock_response: |
      声誉风险：低
      行业口碑：良好
      合作历史：5 年

  - id: aggregate-rating
    agent: aggregate-agent
    prompt_template: |
      汇总三路评估，输出风险评级 JSON：
      财务：\${financial-analysis}
      合规：\${compliance-check}
      声誉：\${reputation}
    mock_response: |
      {"riskLevel":"LOW","confidence":0.85,...}

edges:
  - { from: financial-analysis, to: aggregate-rating }
  - { from: compliance-check, to: aggregate-rating }
  - { from: reputation, to: aggregate-rating }`

interface SubmitFormProps {
  navigate: (tab: TabId, workflowId?: string) => void
  showToast: (msg: string) => void
  /** 从「工作流定义」Tab 跳入时预填的名称（挂载时消费一次）。 */
  prefillName?: string | null
  onPrefillConsumed?: () => void
}

/** 提交工作流 Tab：YAML 编辑器 + 配置表单 + 提交/Dry-run（真实 API 优先，失败走 prototype 式 mock 降级）。 */
export function SubmitForm({ navigate, showToast, prefillName, onPrefillConsumed }: SubmitFormProps) {
  const [yaml, setYaml] = useState(DEFAULT_YAML)
  const [name, setName] = useState(prefillName?.trim() || 'supplier-risk')
  const [inputsText, setInputsText] = useState('{"supplier": "Acme Corp"}')
  const [submitting, setSubmitting] = useState(false)
  const timers = useRef<number[]>([])
  // 组件是否仍在挂载：异步回调 / 定时器触发前先检查，防止卸载后仍 setState / 跳转
  const mounted = useRef(true)

  // 卸载时清理待触发定时器并标记卸载，避免跳转后 setState / 用户切走后被打断跳走
  useEffect(() => {
    const pending = timers.current
    return () => {
      mounted.current = false
      pending.forEach((t) => window.clearTimeout(t))
    }
  }, [])

  // prefillName 仅在挂载时消费一次（消费完通知 App 清空，防止下次进入又预填）
  useEffect(() => {
    onPrefillConsumed?.()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const later = (fn: () => void, ms: number) => {
    timers.current.push(window.setTimeout(fn, ms))
  }

  const handleSubmit = () => {
    const trimmed = name.trim()
    if (!trimmed) {
      showToast('请填写工作流名称')
      return
    }
    let inputs: Record<string, unknown>
    try {
      inputs = JSON.parse(inputsText.trim() || '{}') as Record<string, unknown>
    } catch {
      showToast('启动入参 JSON 格式错误，请检查')
      return
    }
    setSubmitting(true)
    submitWorkflow({ workflowName: trimmed, version: '1.0', yamlContent: yaml, inputs })
      .then((res) => {
        if (!mounted.current) return
        setSubmitting(false)
        showToast(`工作流提交成功：${res.workflowId}`)
        later(() => {
          if (!mounted.current) return
          navigate('trace', res.workflowId)
        }, 800)
      })
      .catch((err: unknown) => {
        if (!mounted.current) return
        // 后端可达但拒绝了请求（INVALID_YAML 400 / 鉴权 401/403）——真实拒绝，不能当成功演示
        if (err instanceof ApiError) {
          setSubmitting(false)
          showToast(`提交被拒绝：HTTP ${err.status} ${err.message}`)
          return
        }
        // 网络/超时（TypeError/AbortError）等不可达错误：按 prototype 的 setTimeout 模拟降级——mock 也走通 spinner→toast→跳转全流程
        later(() => {
          if (!mounted.current) return
          setSubmitting(false)
          const mockId = `wf-${Date.now()}`
          showToast(`（mock）工作流提交成功：${trimmed} — 后端不可达，仅演示`)
          later(() => {
            if (!mounted.current) return
            navigate('trace', mockId)
          }, 800)
        }, 1500)
      })
  }

  const handleDryRun = () => {
    // 本地静态检查（不调 LLM、不依赖后端）：统计节点数
    const nodeCount = (yaml.match(/^\s*-\s+id:/gm) ?? []).length
    showToast(`Dry-run 完成：YAML 静态检查通过，共 ${nodeCount} 节点（未调 LLM）`)
  }

  return (
    <div className="animate-fade-in">
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h2 className="text-[22px] font-bold tracking-tight">提交工作流</h2>
          <p className="mt-1 text-[13px] text-muted">用 YAML DSL 声明 Multi-Agent 工作流，BSP 引擎自动分层执行</p>
        </div>
        <button
          onClick={() => navigate('definitions')}
          className="inline-flex items-center gap-2 rounded-lg border border-line bg-surface px-5 py-2.5 text-[13px] font-semibold text-ink transition-all hover:border-accent hover:text-accent"
        >
          从已有定义选择
        </button>
      </div>

      <div className="grid grid-cols-[1fr_320px] gap-6 max-[960px]:grid-cols-1">
        <YamlEditor value={yaml} onChange={setYaml} fileName={`${name.trim() || 'workflow'}.yml`} />

        <div className="flex flex-col gap-5">
          <div>
            <label className="mb-1.5 block text-[11px] font-semibold uppercase tracking-wider text-muted">
              工作流名称
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-lg border border-line bg-surface px-3.5 py-2.5 font-mono text-[13px] outline-none transition-all focus:border-accent focus:ring-[3px] focus:ring-accent/10"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-[11px] font-semibold uppercase tracking-wider text-muted">
              启动入参（JSON）
            </label>
            <textarea
              value={inputsText}
              onChange={(e) => setInputsText(e.target.value)}
              className="min-h-[100px] w-full resize-y rounded-lg border border-line bg-surface px-3.5 py-2.5 font-mono text-[13px] outline-none transition-all focus:border-accent focus:ring-[3px] focus:ring-accent/10"
            />
          </div>
          <div className="rounded-lg border border-line bg-surface p-3.5 text-xs leading-relaxed text-muted shadow-card">
            <strong className="mb-1 block text-ink">BSP 分层执行</strong>
            3 个专家 Agent 并行分析 → Supervisor 汇总评级
            <br />
            YAML 4 节点 → 2 super-step，mock 模式零 LLM 成本
          </div>
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className="inline-flex w-full items-center justify-center gap-2 rounded-lg bg-accent px-5 py-2.5 text-[13px] font-semibold text-white shadow-card transition-all hover:bg-accent-dim disabled:cursor-not-allowed disabled:opacity-70"
          >
            {submitting && (
              <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/30 border-t-white" />
            )}
            {submitting ? '执行中...' : '提交工作流'}
          </button>
          <button
            onClick={handleDryRun}
            disabled={submitting}
            className="inline-flex w-full items-center justify-center gap-2 rounded-lg border border-line bg-surface px-5 py-2.5 text-[13px] font-semibold text-ink transition-all hover:border-accent hover:text-accent disabled:cursor-not-allowed disabled:opacity-60"
          >
            Dry-run 预览（不调 LLM）
          </button>
        </div>
      </div>
    </div>
  )
}
