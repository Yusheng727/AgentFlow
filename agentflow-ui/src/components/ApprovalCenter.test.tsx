import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { ApprovalCenter } from './ApprovalCenter'
import type { ApprovalSummary } from '../types'

/**
 * ApprovalCenter 组件测试（U4）：列表渲染（mock fallback 数据）、决策按钮触发
 * decideApproval 调用（正确 URL 契约由调用方 api 层测试覆盖）、决策成功乐观移除、
 * 决策失败（ApiError）toast 报错不移除、空态。
 */

const mocks = vi.hoisted(() => ({
  listPendingApprovals: vi.fn(),
  decideApproval: vi.fn(),
}))

vi.mock('../lib/api', () => ({
  ApiError: class ApiError extends Error {
    constructor(public status: number, message: string) {
      super(message)
    }
  },
  listPendingApprovals: mocks.listPendingApprovals,
  decideApproval: mocks.decideApproval,
}))

// 从被 mock 的 api 模块取 ApiError 构造——instanceof 判定须与组件 import 的是同一类
const { ApiError } = vi.mocked(await import('../lib/api'))

const approval = (id: string, nodeId: string): ApprovalSummary => ({
  approvalId: id,
  workflowId: 'wf-1',
  workflowName: 'supplier-pay-hold',
  nodeId,
  description: `审批描述 ${id}`,
  status: 'PENDING',
  createdAt: '2026-08-23T10:30:00Z',
})

const showToast = vi.fn()

describe('ApprovalCenter', () => {
  beforeEach(() => {
    mocks.listPendingApprovals.mockReset()
    mocks.decideApproval.mockReset()
    showToast.mockReset()
  })

  it('渲染待批列表：每条审批一张卡（nodeId + 描述 + 批准/拒绝按钮）', async () => {
    mocks.listPendingApprovals.mockResolvedValue({
      approvals: [approval('a1', 'pay-gate'), approval('a2', 'contract-gate')],
      source: 'api',
    })

    render(<ApprovalCenter showToast={showToast} />)

    expect(await screen.findByText('pay-gate')).toBeInTheDocument()
    expect(screen.getByText('contract-gate')).toBeInTheDocument()
    expect(screen.getByText('审批描述 a1')).toBeInTheDocument()
    // 每卡批准 + 拒绝按钮（2 卡 → 各 2 个）
    expect(screen.getAllByText('批准')).toHaveLength(2)
    expect(screen.getAllByText('拒绝')).toHaveLength(2)
  })

  it('点击批准 → decideApproval(wfId, approvalId, APPROVE) 被调用', async () => {
    mocks.listPendingApprovals.mockResolvedValue({
      approvals: [approval('a1', 'pay-gate')],
      source: 'api',
    })
    mocks.decideApproval.mockResolvedValue({ decision: 'APPROVE', workflowStatus: 'SUCCESS' })

    render(<ApprovalCenter showToast={showToast} />)

    fireEvent.click(await screen.findByText('批准'))

    await waitFor(() => {
      expect(mocks.decideApproval).toHaveBeenCalledWith('wf-1', 'a1', 'APPROVE')
    })
  })

  it('决策成功 → 卡片乐观移除 + toast 透出后续工作流状态', async () => {
    mocks.listPendingApprovals.mockResolvedValue({
      approvals: [approval('a1', 'pay-gate')],
      source: 'api',
    })
    mocks.decideApproval.mockResolvedValue({ decision: 'APPROVE', workflowStatus: 'SUCCESS' })

    render(<ApprovalCenter showToast={showToast} />)

    fireEvent.click(await screen.findByText('批准'))

    await waitFor(() => {
      expect(screen.queryByText('pay-gate')).not.toBeInTheDocument()
    })
    expect(showToast).toHaveBeenCalledWith('已批准 pay-gate：工作流 → SUCCESS')
  })

  it('决策失败（ApiError 403）→ toast 报错，卡片保留（决策未生效不丢单）', async () => {
    mocks.listPendingApprovals.mockResolvedValue({
      approvals: [approval('a1', 'pay-gate')],
      source: 'api',
    })
    mocks.decideApproval.mockRejectedValue(new ApiError(403, 'POST → HTTP 403'))

    render(<ApprovalCenter showToast={showToast} />)

    fireEvent.click(await screen.findByText('批准'))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('决策失败：HTTP 403')
    })
    // 卡片仍在（不能静默丢审批单）
    expect(screen.getByText('pay-gate')).toBeInTheDocument()
  })

  it('决策按钮点击后置 disabled 防重复提交（deciding 态）', async () => {
    let resolveDecide: (v: { decision: string; workflowStatus: string }) => void = () => {}
    mocks.listPendingApprovals.mockResolvedValue({
      approvals: [approval('a1', 'pay-gate')],
      source: 'api',
    })
    mocks.decideApproval.mockImplementation(
      () => new Promise((res) => { resolveDecide = res }),
    )

    render(<ApprovalCenter showToast={showToast} />)

    const approveBtn = (await screen.findByText('批准')).closest('button') as HTMLButtonElement
    fireEvent.click(approveBtn)

    await waitFor(() => {
      expect(approveBtn).toBeDisabled()
    })
    resolveDecide({ decision: 'APPROVE', workflowStatus: 'SUCCESS' })
    // 决策成功后卡片被乐观移除——按钮随卡片卸载即不再在文档中
    await waitFor(() => {
      expect(screen.queryByText('pay-gate')).not.toBeInTheDocument()
    })
  })

  it('空列表 → 空态提示（非报错）', async () => {
    mocks.listPendingApprovals.mockResolvedValue({ approvals: [], source: 'api' })

    render(<ApprovalCenter showToast={showToast} />)

    expect(await screen.findByText('暂无待批审批')).toBeInTheDocument()
  })

  it('mock 来源 → 徽标显示', async () => {
    mocks.listPendingApprovals.mockResolvedValue({
      approvals: [approval('a1', 'pay-gate')],
      source: 'mock',
    })

    render(<ApprovalCenter showToast={showToast} />)

    expect(await screen.findByText('pay-gate')).toBeInTheDocument()
    expect(screen.getByText('mock 模式')).toBeInTheDocument()
  })
})
