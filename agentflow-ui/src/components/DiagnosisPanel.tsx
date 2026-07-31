import React, { useState } from 'react';
import { Activity, AlertTriangle, Loader2 } from 'lucide-react';
import { diagnoseWorkflow } from '../lib/api';
import type { Diagnosis, DiagnosisReport } from '../types';

const PROBLEM_ICONS: Record<string, string> = {
  '连续超时': '⏱️',
  'Token 异常消耗': '💰',
  'SpEL 解析失败': '🔤',
  'Channel 缺失': '🔗',
  '节点重复执行': '🔄',
};

interface DiagnosisPanelProps {
  workflowId: string | null;
}

export function DiagnosisPanel({ workflowId }: DiagnosisPanelProps) {
  const [report, setReport] = useState<DiagnosisReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function runDiagnosis() {
    if (!workflowId) return;
    setLoading(true);
    setError(null);
    try {
      const result = await diagnoseWorkflow(workflowId);
      setReport(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : '诊断失败');
    } finally {
      setLoading(false);
    }
  }

  if (!workflowId) {
    return (
      <div className="flex flex-col items-center justify-center h-64 text-center">
        <Activity size={48} className="text-text-muted mb-4 opacity-30" />
        <p className="text-text-muted text-sm">先在「提交工作流」中提交一个工作流</p>
      </div>
    );
  }

  return (
    <div className="animate-fade-in space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="font-display text-2xl font-semibold text-text-bright">诊断报告</h2>
          <p className="text-text-muted text-sm mt-1 font-mono">{workflowId}</p>
        </div>
        <button
          onClick={runDiagnosis}
          disabled={loading}
          className="flex items-center gap-2 bg-cyan text-space-950 font-semibold rounded-lg px-5 py-2.5 hover:bg-cyan-dim transition-colors disabled:opacity-50 text-sm"
        >
          {loading ? <Loader2 size={16} className="animate-spin" /> : <Activity size={16} />}
          运行诊断
        </button>
      </div>

      {error && (
        <div className="bg-danger/10 border border-danger/30 rounded-lg px-4 py-3 text-sm text-danger">{error}</div>
      )}

      {!report && !loading && !error && (
        <div className="flex flex-col items-center justify-center h-48 text-center bg-space-900 border border-space-700 rounded-xl">
          <AlertTriangle size={32} className="text-text-muted mb-3 opacity-30" />
          <p className="text-text-muted text-sm">点击「运行诊断」分析工作流执行轨迹</p>
        </div>
      )}

      {report && (
        <div className="space-y-4">
          {/* 摘要 */}
          <div className="grid grid-cols-3 gap-4">
            <div className="bg-space-900 border border-space-700 rounded-lg px-4 py-3">
              <div className="text-xs text-text-muted">总节点</div>
              <div className="font-display text-2xl font-semibold text-text-bright">{report.totalNodes}</div>
            </div>
            <div className="bg-space-900 border border-space-700 rounded-lg px-4 py-3">
              <div className="text-xs text-text-muted">失败节点</div>
              <div className="font-display text-2xl font-semibold text-danger">{report.failedNodes}</div>
            </div>
            <div className="bg-space-900 border border-space-700 rounded-lg px-4 py-3">
              <div className="text-xs text-text-muted">发现问题</div>
              <div className={`font-display text-2xl font-semibold ${report.findings.length > 0 ? 'text-warning' : 'text-success'}`}>
                {report.findings.length}
              </div>
            </div>
          </div>

          {/* 诊断结果 */}
          {report.findings.length === 0 ? (
            <div className="bg-success/10 border border-success/30 rounded-lg px-6 py-8 text-center">
              <span className="text-success text-lg font-semibold">✓ 无异常发现</span>
              <p className="text-text-muted text-sm mt-1">工作流执行正常，无超时、无异常 token 消耗、无解析错误</p>
            </div>
          ) : (
            <div className="space-y-3">
              {report.findings.map((f: Diagnosis, i: number) => (
                <div key={i} className="bg-space-900 border border-space-700 rounded-lg px-5 py-4 animate-slide-up" style={{ animationDelay: `${i * 100}ms` }}>
                  <div className="flex items-center gap-3 mb-2">
                    <span className="text-lg">{PROBLEM_ICONS[f.problemType] || '⚠️'}</span>
                    <span className="font-semibold text-warning">{f.problemType}</span>
                    <span className="text-xs text-text-muted font-mono bg-space-800 px-2 py-0.5 rounded">{f.nodeId}</span>
                  </div>
                  <p className="text-text-bright text-sm">{f.description}</p>
                  <p className="text-text-muted text-xs mt-1.5 italic">💡 {f.suggestion}</p>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
