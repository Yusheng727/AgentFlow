import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  ApiError,
  resolveApiKey,
  listWorkflows,
  submitWorkflow,
  getWorkflowStatus,
  retryWorkflow,
  getWorkflowTrace,
  diagnoseWorkflow,
  listPendingApprovals,
  decideApproval,
  type WorkflowListResult,
} from './api'
import type { WorkflowExecutionRecord } from '../types'

/**
 * api.ts 单元测试（KTD-1 真实 API 优先 + mock fallback）。
 *
 * <p>mock 全局 fetch，验证：请求契约（URL / 方法 / X-API-Key header / JSON body）、
 * 状态归一（后端 UPPER 枚举 → UI lowercase，PENDING 并入 running）、
 * 非 2xx / 网络错误 → ApiError / mock fallback 降级。
 */

// ──────────────────── fetch mock 辅助 ────────────────────

type FetchMock = ReturnType<typeof vi.fn>

/** 构造一个形似 fetch Response 的最小对象（request() 只用 ok/status/json）。 */
function fakeResponse(ok: boolean, status: number, data?: unknown) {
  return {
    ok,
    status,
    json: async () => data,
  } as Response
}

let fetchMock: FetchMock

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

// ──────────────────── ApiError ────────────────────

describe('ApiError', () => {
  it('携带 status 且是 Error 实例', () => {
    const e = new ApiError(404, 'GET /x → HTTP 404')
    expect(e).toBeInstanceOf(Error)
    expect(e).toBeInstanceOf(ApiError)
    expect(e.status).toBe(404)
    expect(e.message).toBe('GET /x → HTTP 404')
  })
})

// ──────────────────── resolveApiKey（VITE_API_KEY 生产加固）────────────────────

describe('resolveApiKey', () => {
  it('配置了 VITE_API_KEY → 用之，isDemo=false', () => {
    const r = resolveApiKey('prod-key-123', true)
    expect(r.key).toBe('prod-key-123')
    expect(r.isDemo).toBe(false)
  })

  it('未配置 → 回退 demo key，isDemo=true（dev 不告警）', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const r = resolveApiKey(undefined, false)
    expect(r.key).toBe('demo-key-1234567890abcdef')
    expect(r.isDemo).toBe(true)
    expect(spy).not.toHaveBeenCalled()
    spy.mockRestore()
  })

  it('生产未配置 → 回退 demo key 且 console.error 显式告警（不静默）', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const r = resolveApiKey(undefined, true)
    expect(r.isDemo).toBe(true)
    expect(spy).toHaveBeenCalled()
    spy.mockRestore()
  })

  it('空串/空白 key 视同未配置 → 回退 demo key', () => {
    expect(resolveApiKey('', true).isDemo).toBe(true)
    expect(resolveApiKey('   ', false).isDemo).toBe(true)
  })
})


// ──────────────────── listWorkflows（看板列表）────────────────────

describe('listWorkflows', () => {
  it('真实 API 成功：状态归一 UPPER→lower（PENDING/RUNNING→running，AWAITING_APPROVAL→awaiting_approval 独立列，SUCCESS→success，FAILED→failed）', async () => {
    const records: WorkflowExecutionRecord[] = [
      { workflowId: 'w1', workflowName: 'a', status: 'PENDING', createdAt: '2026-08-07T00:00:00Z' },
      { workflowId: 'w2', workflowName: 'b', status: 'RUNNING', createdAt: '2026-08-07T00:00:01Z' },
      { workflowId: 'w2a', workflowName: 'b2', status: 'AWAITING_APPROVAL', createdAt: '2026-08-07T00:00:01Z' },
      { workflowId: 'w3', workflowName: 'c', status: 'SUCCESS', createdAt: null },
      { workflowId: 'w4', workflowName: 'd', status: 'FAILED', createdAt: '2026-08-07T00:00:02Z' },
    ]
    fetchMock.mockResolvedValue(fakeResponse(true, 200, records))

    const result: WorkflowListResult = await listWorkflows()

    expect(result.source).toBe('api')
    expect(result.workflows.map((w) => w.status)).toEqual(['running', 'running', 'awaiting_approval', 'success', 'failed'])
    // createdAt 为 null → date 回退 '--'
    expect(result.workflows[3].date).toBe('--')
    // desc 保留原始 UPPER 形态（供 UI 展示真实枚举）
    expect(result.workflows[3].desc).toBe('SUCCESS')
  })

  it('请求路径 /api/workflows + X-API-Key header', async () => {
    fetchMock.mockResolvedValue(fakeResponse(true, 200, []))

    await listWorkflows()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/workflows')
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(init.headers['X-API-Key']).toBe('demo-key-1234567890abcdef')
  })

  it('后端 5xx（非 2xx）→ 降级 mock 数据，source=mock', async () => {
    fetchMock.mockResolvedValue(fakeResponse(false, 500))

    const result = await listWorkflows()

    expect(result.source).toBe('mock')
    // mock 工作流列表（5 条演示数据）
    expect(result.workflows.length).toBeGreaterThan(0)
    expect(result.workflows.map((w) => w.name)).toContain('supplier-risk-v2')
  })

  it('网络错误（fetch reject）→ 降级 mock 数据，source=mock', async () => {
    fetchMock.mockRejectedValue(new TypeError('Network request failed'))

    const result = await listWorkflows()

    expect(result.source).toBe('mock')
  })
})

// ──────────────────── submitWorkflow（提交）────────────────────

describe('submitWorkflow', () => {
  it('POST /api/workflows 且 body 为序列化请求，返回 202 响应', async () => {
    const resp = { workflowId: 'wf-x', status: 'ACCEPTED', message: null, links: { status: '/api/workflows/wf-x/status' } }
    fetchMock.mockResolvedValue(fakeResponse(true, 202, resp))

    const req = { workflowName: 'supplier', version: '1.0', yamlContent: 'nodes: []', inputs: {} }
    const out = await submitWorkflow(req)

    expect(out.workflowId).toBe('wf-x')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/workflows')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual(req)
  })

  it('后端拒绝（401）→ 抛 ApiError(401) 而非静默降级（真实拒绝须露出）', async () => {
    fetchMock.mockResolvedValue(fakeResponse(false, 401))

    await expect(submitWorkflow({ workflowName: 'x', version: '1.0', yamlContent: '', inputs: {} }))
      .rejects.toBeInstanceOf(ApiError)
    await expect(
      submitWorkflow({ workflowName: 'x', version: '1.0', yamlContent: '', inputs: {} }),
    ).rejects.toMatchObject({ status: 401 })
  })
})

// ──────────────────── getWorkflowStatus / retryWorkflow ────────────────────

describe('getWorkflowStatus & retryWorkflow', () => {
  it('getWorkflowStatus GET 对应路径', async () => {
    fetchMock.mockResolvedValue(fakeResponse(true, 200, { workflowId: 'wf-x', status: 'RUNNING', queriedAt: 't' }))

    const out = await getWorkflowStatus('wf-x')

    expect(out.status).toBe('RUNNING')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/workflows/wf-x/status')
  })

  it('retryWorkflow POST retry 路径', async () => {
    fetchMock.mockResolvedValue(fakeResponse(true, 202, { workflowId: 'wf-x', status: 'ACCEPTED', message: null, links: null }))

    await retryWorkflow('wf-x')

    expect(fetchMock.mock.calls[0][0]).toBe('/api/workflows/wf-x/retry')
    expect(fetchMock.mock.calls[0][1].method).toBe('POST')
  })
})

// ──────────────────── getWorkflowTrace（轨迹）────────────────────

describe('getWorkflowTrace', () => {
  it('真实 API 成功 → source=api', async () => {
    const trace = { workflowId: 'wf-x', startTime: null, endTime: null, status: 'COMPLETED', nodes: [], totalTokens: 0 }
    fetchMock.mockResolvedValue(fakeResponse(true, 200, trace))

    const out = await getWorkflowTrace('wf-x')

    expect(out.source).toBe('api')
    expect(out.trace.workflowId).toBe('wf-x')
  })

  it('端点不可达 → 降级 mock 轨迹，workflowId 用请求 id', async () => {
    fetchMock.mockRejectedValue(new TypeError('fail'))

    const out = await getWorkflowTrace('wf-req')

    expect(out.source).toBe('mock')
    expect(out.trace.workflowId).toBe('wf-req') // mockTrace 覆盖为请求 id
  })
})

// ──────────────────── diagnoseWorkflow（诊断）────────────────────

describe('diagnoseWorkflow', () => {
  it('POST /diagnosis，body 含 {workflowId, trace}', async () => {
    const trace = { workflowId: 'wf-x', startTime: null, endTime: null, status: 'FAILED', nodes: [], totalTokens: 0 }
    const report = { workflowId: 'wf-x', totalNodes: 0, failedNodes: 0, findings: [] }
    fetchMock.mockResolvedValue(fakeResponse(true, 200, report))

    const out = await diagnoseWorkflow('wf-x', trace)

    expect(out.workflowId).toBe('wf-x')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/diagnosis')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual({ workflowId: 'wf-x', trace })
  })
})

// ──────────────────── HITL 审批（U3 聚合 + U4 决策）────────────────────

describe('listPendingApprovals（审批中心聚合）', () => {
  it('真实 API 成功 → source=api + 透传投影数组', async () => {
    const approvals = [
      { approvalId: 'a1', workflowId: 'wf-1', workflowName: 'pay', nodeId: 'gate', description: 'd', status: 'PENDING', createdAt: null },
    ]
    fetchMock.mockResolvedValue(fakeResponse(true, 200, approvals))

    const out = await listPendingApprovals()

    expect(out.source).toBe('api')
    expect(out.approvals[0].approvalId).toBe('a1')
    const [url] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/approvals/pending')
  })

  it('后端不可达（网络错误）→ 降级 mock 待批数据，source=mock', async () => {
    fetchMock.mockRejectedValue(new TypeError('Network request failed'))

    const out = await listPendingApprovals()

    expect(out.source).toBe('mock')
    // mock 待批审批（2 条演示数据，mockData U4）
    expect(out.approvals.length).toBe(2)
    expect(out.approvals[0].nodeId).toBe('pay-gate')
  })

  it('后端 5xx → 不降级 mock，抛 ApiError（review P1：防 corrupt 审批 500 被 mock 掩盖）', async () => {
    fetchMock.mockResolvedValue(fakeResponse(false, 500))

    await expect(listPendingApprovals()).rejects.toMatchObject({ status: 500 })
  })
})

describe('decideApproval（审批决策）', () => {
  it('POST /api/workflows/{wfId}/approvals/{approvalId}，body 仅 {decision}（decidedBy 服务端推导）', async () => {
    const resp = { decision: 'APPROVE', workflowStatus: 'SUCCESS' }
    fetchMock.mockResolvedValue(fakeResponse(true, 200, resp))

    const out = await decideApproval('wf-1', 'a1', 'APPROVE')

    expect(out.workflowStatus).toBe('SUCCESS')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/workflows/wf-1/approvals/a1')
    expect(init.method).toBe('POST')
    // 请求体只有 decision——decidedBy 不由客户端提供（防伪造）
    expect(JSON.parse(init.body)).toEqual({ decision: 'APPROVE' })
  })

  it('写操作无 mock fallback：后端拒绝（403）→ 抛 ApiError(403)（真实拒绝须露出）', async () => {
    fetchMock.mockResolvedValue(fakeResponse(false, 403))

    await expect(decideApproval('wf-1', 'a1', 'REJECT')).rejects.toMatchObject({
      status: 403,
    })
  })

  it('写操作无 mock fallback：网络错误 → 直接抛（不静默假成功）', async () => {
    fetchMock.mockRejectedValue(new TypeError('Network request failed'))

    await expect(decideApproval('wf-1', 'a1', 'APPROVE')).rejects.toThrow(TypeError)
  })
})
