import { useRef, useState } from 'react'
import { Layout, type TabId } from './components/Layout'
import { Dashboard } from './components/Dashboard'
import { SubmitForm } from './components/SubmitForm'
import { WorkflowDefinitions } from './components/WorkflowDefinitions'
import { PipelineView } from './components/PipelineView'
import { DiagnosisPanel } from './components/DiagnosisPanel'
import { ApprovalCenter } from './components/ApprovalCenter'

export default function App() {
  const [tab, setTab] = useState<TabId>('dashboard')
  // 全局选中的工作流：看板卡片「查看轨迹 / 诊断」跳入对应 Tab 时带入
  const [workflowId, setWorkflowId] = useState<string | null>(null)
  // 定义 Tab 选中后跳转提交页时预填的名称（SubmitForm 挂载时消费并清空）
  const [prefillName, setPrefillName] = useState<string | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const toastTimer = useRef<number | undefined>(undefined)

  const showToast = (msg: string) => {
    window.clearTimeout(toastTimer.current)
    setToast(msg)
    toastTimer.current = window.setTimeout(() => setToast(null), 3000)
  }

  const navigate = (next: TabId, wfId?: string) => {
    if (wfId !== undefined) setWorkflowId(wfId)
    setTab(next)
  }

  const useDefinition = (name: string) => {
    setPrefillName(name)
    setTab('submit')
  }

  return (
    <>
      <Layout activeTab={tab} onTabChange={setTab}>
        {tab === 'dashboard' && <Dashboard onNavigate={navigate} showToast={showToast} />}
        {tab === 'submit' && (
          <SubmitForm
            navigate={navigate}
            showToast={showToast}
            prefillName={prefillName}
            onPrefillConsumed={() => setPrefillName(null)}
          />
        )}
        {tab === 'definitions' && (
          <WorkflowDefinitions navigate={navigate} showToast={showToast} onUseDefinition={useDefinition} />
        )}
        {tab === 'approval-center' && <ApprovalCenter showToast={showToast} />}
        {tab === 'trace' && <PipelineView workflowId={workflowId} />}
        {tab === 'diagnosis' && <DiagnosisPanel workflowId={workflowId} />}
      </Layout>
      {toast !== null && (
        <div className="fixed bottom-6 right-6 z-50 animate-fade-in rounded-lg bg-ink px-5 py-3 text-[13px] font-medium text-white shadow-card-lg">
          {toast}
        </div>
      )}
    </>
  )
}
