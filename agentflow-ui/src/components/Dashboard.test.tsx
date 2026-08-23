import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { Dashboard } from './Dashboard'
import type { WorkflowSummary } from '../types'

/**
 * Dashboard 组件测试：三列看板按状态分桶、成功率 KPI、mock 来源徽标。
 *
 * <p>mock {@code ../lib/api}（listWorkflows/retryWorkflow/ApiError），组件其余依赖（mockData 图表等）用真实数据。
 */

const mocks = vi.hoisted(() => ({
  listWorkflows: vi.fn(),
  retryWorkflow: vi.fn(),
}))

vi.mock('../lib/api', () => ({
  ApiError: class ApiError extends Error {
    constructor(public status: number, message: string) {
      super(message)
    }
  },
  listWorkflows: mocks.listWorkflows,
  retryWorkflow: mocks.retryWorkflow,
}))

const noop = () => {}

describe('Dashboard', () => {
  beforeEach(() => {
    mocks.listWorkflows.mockReset()
    mocks.retryWorkflow.mockReset()
  })

  const wf = (id: string, status: WorkflowSummary['status']): WorkflowSummary => ({
    id,
    name: id,
    status,
    nodes: 3,
    steps: 2,
    time: '10ms',
    date: '1m 前',
    desc: 'd',
  })

  it('真实 API 数据按四列分桶：running/awaiting_approval/success/failed 各自成组（U4 状态修复）', async () => {
    mocks.listWorkflows.mockResolvedValue({
      workflows: [
        wf('r1', 'running'),
        wf('r2', 'running'),
        wf('ap1', 'awaiting_approval'),
        wf('s1', 'success'),
        wf('f1', 'failed'),
      ],
      source: 'api',
    })

    render(<Dashboard onNavigate={noop} showToast={noop} />)

    // 等异步 listWorkflows 落定 + 分桶计数渲染
    await screen.findByText('进行中')
    // 四列计数徽标（data-testid 定位）：running=2, awaiting_approval=1, success=1, failed=1
    expect(screen.getByTestId('count-running')).toHaveTextContent('2')
    expect(screen.getByTestId('count-awaiting_approval')).toHaveTextContent('1')
    expect(screen.getByTestId('count-success')).toHaveTextContent('1')
    expect(screen.getByTestId('count-failed')).toHaveTextContent('1')
    // 待审批列有卡片 + 去审批按钮（卡片点击展开后可见，U4；ap1 在卡片+表格出现两次取卡片）
    expect(screen.getByText('待审批')).toBeInTheDocument()
    fireEvent.click(screen.getAllByText('ap1')[0])
    expect(screen.getByText('去审批')).toBeInTheDocument()
    // 每个工作流名在看板卡片 + 最近执行表各出现一次
    expect(screen.getAllByText('r1')).toHaveLength(2)
    expect(screen.getAllByText('f1')).toHaveLength(2)
  })

  it('旧三列断言回归：running/success/failed 分桶仍成立', async () => {
    mocks.listWorkflows.mockResolvedValue({
      workflows: [wf('r1', 'running'), wf('r2', 'running'), wf('s1', 'success'), wf('f1', 'failed')],
      source: 'api',
    })

    render(<Dashboard onNavigate={noop} showToast={noop} />)

    await screen.findByText('进行中')
    expect(screen.getByTestId('count-running')).toHaveTextContent('2')
    expect(screen.getByTestId('count-success')).toHaveTextContent('1')
    expect(screen.getByTestId('count-failed')).toHaveTextContent('1')
    expect(screen.getAllByText('r1')).toHaveLength(2)
    expect(screen.getAllByText('f1')).toHaveLength(2)
  })

  it('source=api 时不渲染 mock 来源徽标', async () => {
    mocks.listWorkflows.mockResolvedValue({ workflows: [wf('s1', 'success')], source: 'api' })

    render(<Dashboard onNavigate={noop} showToast={noop} />)

    await screen.findByText('已完成')
    // api 模式下：无徽标 + Token KPI 副文案为 '真实调用' → 'mock 模式' 出现 0 次
    expect(screen.queryAllByText('mock 模式')).toHaveLength(0)
  })

  it('source=mock 时渲染 mock 来源徽标', async () => {
    mocks.listWorkflows.mockResolvedValue({ workflows: [wf('s1', 'success')], source: 'mock' })

    render(<Dashboard onNavigate={noop} showToast={noop} />)

    // mock 模式下：徽标 + Token KPI 副文案（tokenHint='mock 模式'）各出现一次
    expect(await screen.findByText('已完成')).toBeInTheDocument()
    expect(screen.getAllByText('mock 模式')).toHaveLength(2)
  })

  it('成功率 KPI 正确：2 success / 4 total → 50.0%', async () => {
    mocks.listWorkflows.mockResolvedValue({
      workflows: [wf('s1', 'success'), wf('s2', 'success'), wf('r1', 'running'), wf('f1', 'failed')],
      source: 'api',
    })

    render(<Dashboard onNavigate={noop} showToast={noop} />)

    expect(await screen.findByText('50.0%')).toBeInTheDocument()
    // 副文案 "2/4 成功"
    expect(screen.getByText('2/4 成功')).toBeInTheDocument()
  })
})
