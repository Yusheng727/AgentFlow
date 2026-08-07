import type {
  DiagnosisReport,
  ExecutionTraceSnapshot,
  StatusResponse,
  SubmitRequest,
  SubmitResponse,
  WorkflowExecutionRecord,
  WorkflowStatusUi,
  WorkflowSummary,
} from '../types'
import { mockTrace, mockWorkflows } from './mockData'

/**
 * fetch 封装（KTD-1：真实 API 优先 + mock fallback）。
 *
 * API base 默认走相对路径 `/api`——开发环境由 vite.config.ts 的 proxy 转发到
 * http://localhost:8080（后端暂无 CORS 配置，浏览器直连会被拦）；
 * 生产同源部署或后端配好 CORS 后，可用 VITE_API_BASE=http://localhost:8080/api 直连覆盖。
 */
const BASE: string = import.meta.env.VITE_API_BASE ?? '/api'

/** 仅演示/本地 mock 用的公开 demo key（与 demo-api ApiConfig 注册的 key 一致）。非生产凭证。 */
const DEMO_API_KEY = 'demo-key-1234567890abcdef'

/**
 * 解析请求头 `<b>X-API-Key</b>：`VITE_API_KEY` 优先；未配置则回退 demo key。
 *
 * <p>生产加固：生产构建（{@code import.meta.env.PROD}）未配置 `VITE_API_KEY` 时，
 * 打 `console.error` 显式告警，避免在真实后端前静默使用公开 demo key（后端生产应配
 * `AGENTFLOW_API_KEYS` 拒绝/替换 demo key，见 README）。演示态用 demo key + isDemo 标记。
 */
export function resolveApiKey(
  envKey: string | undefined,
  isProd: boolean,
): { key: string; isDemo: boolean } {
  if (envKey && envKey.trim() !== '') {
    return { key: envKey, isDemo: false }
  }
  if (isProd) {
    // 不能静默回退到公开 demo key——运维需显式配置 VITE_API_KEY
    console.error(
      '[agentflow] 生产构建未配置 VITE_API_KEY，回退到公开演示 key。' +
        '请设置 VITE_API_KEY 并在后端配置 AGENTFLOW_API_KEYS（否则演示 key 可能被拒绝/无权）。',
    )
  }
  return { key: DEMO_API_KEY, isDemo: true }
}

/** 鉴权 key（ApiKeyAuthFilter，X-API-Key）；解析结果在模块加载时求值一次。 */
const API_KEY: string = resolveApiKey(import.meta.env.VITE_API_KEY, import.meta.env.PROD).key

/** 单请求超时：超过即视为后端不可达，触发 mock fallback。 */
const TIMEOUT_MS = 5000

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), TIMEOUT_MS)
  try {
    const res = await fetch(`${BASE}${path}`, {
      ...init,
      headers: { 'Content-Type': 'application/json', 'X-API-Key': API_KEY },
      signal: controller.signal,
    })
    if (!res.ok) {
      throw new ApiError(res.status, `${init?.method ?? 'GET'} ${path} → HTTP ${res.status}`)
    }
    return (await res.json()) as T
  } finally {
    window.clearTimeout(timer)
  }
}

/** 真实 API 优先；任何失败（网络错误 / 超时 / 非 2xx）降级到 mock，保证 UI 不白屏。 */
async function withMockFallback<T>(
  real: () => Promise<T>,
  mock: () => T,
): Promise<{ data: T; source: 'api' | 'mock' }> {
  try {
    return { data: await real(), source: 'api' }
  } catch {
    return { data: mock(), source: 'mock' }
  }
}

// ──────────────────────────── 端点封装 ────────────────────────────

export interface WorkflowListResult {
  workflows: WorkflowSummary[]
  /** 数据来源：api=真实后端，mock=降级数据（UI 据此显示 mock 徽标）。 */
  source: 'api' | 'mock'
}

/**
 * 工作流列表（看板数据源，U10 后续 #12）。
 * 后端 GET /api/workflows 返回 WorkflowExecutionRecord（workflowId/workflowName/status/createdAt，
 * status 为 UPPER 枚举）。此处归一化映射：status → lowercase（pending 并入 running，供看板三列分桶）、
 * mock 专属展示字段（nodes/steps/time/desc）给出缺省。后端不可达/404 → 降级 mockData。
 */
export async function listWorkflows(): Promise<WorkflowListResult> {
  const { data, source } = await withMockFallback(
    () => request<WorkflowExecutionRecord[]>('/workflows').then((list) => list.map(toWorkflowSummary)),
    () => mockWorkflows,
  )
  return { workflows: data, source }
}

/** 后端执行记录 → 看板摘要（状态大小写归一，避免 real enum UPPER 静默错分列/成功率）。 */
function toWorkflowSummary(r: WorkflowExecutionRecord): WorkflowSummary {
  const raw = r.status.toLowerCase()
  // pending 是瞬时态，并入 running（看板三列 running/success/failed 分桶；对应后端 status=UPPER）
  const status: WorkflowStatusUi = raw === 'pending' ? 'running' : (raw as WorkflowStatusUi)
  return {
    id: r.workflowId,
    name: r.workflowName,
    status,
    nodes: 0,
    steps: 0,
    time: '--',
    date: r.createdAt ? new Date(r.createdAt).toLocaleTimeString() : '--',
    desc: r.status,
  }
}

/** POST /api/workflows — 提交 YAML + inputs，异步执行（202）。 */
export function submitWorkflow(req: SubmitRequest): Promise<SubmitResponse> {
  return request<SubmitResponse>('/workflows', { method: 'POST', body: JSON.stringify(req) })
}

/** GET /api/workflows/{id}/status — 轮询执行状态。 */
export function getWorkflowStatus(workflowId: string): Promise<StatusResponse> {
  return request<StatusResponse>(`/workflows/${workflowId}/status`)
}

/** POST /api/workflows/{id}/retry — 重试 FAILED 工作流。 */
export function retryWorkflow(workflowId: string): Promise<SubmitResponse> {
  return request<SubmitResponse>(`/workflows/${workflowId}/retry`, { method: 'POST' })
}

export interface WorkflowTraceResult {
  trace: ExecutionTraceSnapshot
  /** 数据来源：api=TraceController（U3 端点），mock=降级数据。 */
  source: 'api' | 'mock'
}

/**
 * GET /api/workflows/{id}/trace — 执行轨迹（U3 TraceController）。
 * 端点未上线或后端不可达时自动降级 mockTrace，轨迹页不白屏。
 */
export async function getWorkflowTrace(workflowId: string): Promise<WorkflowTraceResult> {
  const { data, source } = await withMockFallback(
    () => request<ExecutionTraceSnapshot>(`/workflows/${workflowId}/trace`),
    () => ({ ...mockTrace, workflowId }),
  )
  return { trace: data, source }
}

/** POST /api/diagnosis — 对 ExecutionTrace.Snapshot 做 5 类问题诊断。 */
export function diagnoseWorkflow(
  workflowId: string,
  trace: ExecutionTraceSnapshot,
): Promise<DiagnosisReport> {
  return request<DiagnosisReport>('/diagnosis', {
    method: 'POST',
    body: JSON.stringify({ workflowId, trace }),
  })
}
