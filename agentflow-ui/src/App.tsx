import React, { useState } from 'react';
import { Layout } from './components/Layout';
import { SubmitForm } from './components/SubmitForm';
import { WorkflowMonitor } from './components/WorkflowMonitor';
import { DiagnosisPanel } from './components/DiagnosisPanel';

type Tab = 'submit' | 'monitor' | 'diagnosis';

export default function App() {
  const [tab, setTab] = useState<Tab>('submit');
  const [workflowId, setWorkflowId] = useState<string | null>(null);

  return (
    <Layout activeTab={tab} onTabChange={setTab}>
      {tab === 'submit' && <SubmitForm onSubmitted={(id) => { setWorkflowId(id); setTab('monitor'); }} />}
      {tab === 'monitor' && <WorkflowMonitor workflowId={workflowId} />}
      {tab === 'diagnosis' && <DiagnosisPanel workflowId={workflowId} />}
    </Layout>
  );
}
