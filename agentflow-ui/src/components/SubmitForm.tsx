import React, { useState } from 'react';
import { Play, Loader2 } from 'lucide-react';
import { YamlEditor } from './YamlEditor';
import { submitWorkflow } from '../lib/api';

const DEFAULT_YAML = `agentflow:
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
      {"riskLevel":"LOW","confidence":0.85,"evidence":["财务健康","合规1次违规","声誉良好"],"recommendation":"可合作"}

edges:
  - { from: financial-analysis, to: aggregate-rating }
  - { from: compliance-check, to: aggregate-rating }
  - { from: reputation, to: aggregate-rating }`;

interface SubmitFormProps {
  onSubmitted: (workflowId: string) => void;
}

export function SubmitForm({ onSubmitted }: SubmitFormProps) {
  const [yaml, setYaml] = useState(DEFAULT_YAML);
  const [workflowName, setWorkflowName] = useState('supplier-risk');
  const [inputs, setInputs] = useState('{"supplier":"Acme Corp"}');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit() {
    setLoading(true);
    setError(null);
    try {
      const parsedInputs = inputs ? JSON.parse(inputs) : {};
      const result = await submitWorkflow({
        workflowName,
        version: '1.0',
        yamlContent: yaml,
        inputs: parsedInputs,
      });
      onSubmitted(result.workflowId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '提交失败');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="animate-fade-in space-y-6">
      <div>
        <h2 className="font-display text-2xl font-semibold text-text-bright">提交工作流</h2>
        <p className="text-text-muted text-sm mt-1">用 YAML DSL 声明 Multi-Agent 工作流，BSP 引擎自动分层执行</p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* YAML 编辑器 */}
        <div className="space-y-2">
          <label className="text-xs font-medium text-text-muted uppercase tracking-wider">工作流定义（YAML）</label>
          <YamlEditor value={yaml} onChange={setYaml} />
        </div>

        {/* 右侧配置 */}
        <div className="space-y-6">
          <div className="space-y-2">
            <label className="text-xs font-medium text-text-muted uppercase tracking-wider">工作流名称</label>
            <input
              type="text"
              value={workflowName}
              onChange={e => setWorkflowName(e.target.value)}
              className="w-full bg-space-900 border border-space-700 rounded-lg px-4 py-2.5 text-text-bright text-sm font-mono outline-none focus:border-cyan/50 focus:ring-1 focus:ring-cyan/20"
            />
          </div>

          <div className="space-y-2">
            <label className="text-xs font-medium text-text-muted uppercase tracking-wider">启动入参（JSON）</label>
            <textarea
              value={inputs}
              onChange={e => setInputs(e.target.value)}
              spellCheck={false}
              className="w-full h-32 bg-space-900 border border-space-700 rounded-lg px-4 py-3 text-text-bright text-sm font-mono outline-none focus:border-cyan/50 focus:ring-1 focus:ring-cyan/20 resize-y"
            />
          </div>

          <div className="space-y-3">
            <div className="bg-space-900/50 border border-space-700 rounded-lg p-4 space-y-2 text-xs text-text-muted">
              <div className="font-medium text-text-bright">BSP 分层执行</div>
              <div>3 个专家 Agent 并行分析 → Supervisor 汇总评级</div>
              <div>YAML 4 节点 → 2 super-step，mock 模式零 LLM 成本</div>
            </div>

            {error && (
              <div className="bg-danger/10 border border-danger/30 rounded-lg px-4 py-3 text-sm text-danger">
                {error}
              </div>
            )}

            <button
              onClick={handleSubmit}
              disabled={loading}
              className="w-full flex items-center justify-center gap-2 bg-cyan text-space-950 font-semibold rounded-lg px-6 py-3 hover:bg-cyan-dim transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {loading ? <Loader2 size={18} className="animate-spin" /> : <Play size={18} />}
              {loading ? '执行中...' : '提交工作流'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
