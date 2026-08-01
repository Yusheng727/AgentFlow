package com.agentflow.observability;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 workflowId 集中存放 {@link ExecutionTrace}（U7 引入，KTD-2 mock 模式 trace 补齐）。
 *
 * <p>背景：{@code SpringAiAgentAdapter}（真实 LLM 路径）原本通过构造器注入单个 {@link ExecutionTrace}，
 * 但 {@code MockAgentFunction} 是无状态单例、且 {@code BspEngine} 不持有 trace——mock 模式下
 * {@link TraceController} 会返回空树。本 Registry 让 {@code BspEngine} 在执行开始时为每个 workflowId
 * 创建并注册一个 trace，通过 {@link com.agentflow.agent.AgentInput#trace()} 传给每个 AgentFunction，
 * MockAgentFunction 据此写入 {@link NodeTrace}。TraceController 从本 Registry 按 workflowId 取 snapshot。
 *
 * <p>线程安全：{@link ConcurrentHashMap} 承载。{@link #register} 用 {@code computeIfAbsent} 保证
 * 同一 workflowId 只创建一个 trace（BspEngine 可能并发执行同 workflowId？v1 不允许，但防御性幂等）。
 *
 * <p>内存管理：v1 不主动清理（demo 规模 < 100 工作流）；生产环境可加 TTL eviction，留 v1.1。
 */
public class ExecutionTraceRegistry {

    private final Map<String, ExecutionTrace> traces = new ConcurrentHashMap<>();

    /**
     * 为指定 workflowId 注册一个新 trace。若已存在则返回已有实例（幂等）。
     *
     * @param workflowId 工作流执行 id（可空——空时返回 null，不注册，便于无 registry 场景 no-op）
     * @return 新建或已有的 {@link ExecutionTrace}；workflowId 为空时返回 null
     */
    public ExecutionTrace register(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return null;
        }
        return traces.computeIfAbsent(workflowId, ExecutionTrace::new);
    }

    /** 按 workflowId 取 trace（不存在返回 null）。 */
    public ExecutionTrace get(String workflowId) {
        if (workflowId == null) {
            return null;
        }
        return traces.get(workflowId);
    }

    /** 按 workflowId 取 {@link ExecutionTrace#snapshot()}；不存在返回 null。 */
    public ExecutionTrace.Snapshot snapshot(String workflowId) {
        ExecutionTrace trace = get(workflowId);
        return trace == null ? null : trace.snapshot();
    }

    /** 移除某 workflowId 的 trace（用于测试清理或显式回收）。 */
    public void remove(String workflowId) {
        if (workflowId != null) {
            traces.remove(workflowId);
        }
    }

    /** 清空全部（主要用于测试隔离）。 */
    public void clear() {
        traces.clear();
    }

    /** 已注册 workflowId 集合（测试/诊断用）。 */
    public java.util.Set<String> workflowIds() {
        return java.util.Collections.unmodifiableSet(traces.keySet());
    }

    @Override
    public String toString() {
        return "ExecutionTraceRegistry{size=" + traces.size() + "}";
    }

    // 静态 no-op 实例：供不启用 trace 的调用方传入（如纯单元测试），避免 null 检查散布
    private static final ExecutionTraceRegistry NOOP = new ExecutionTraceRegistry() {
        @Override
        public ExecutionTrace register(String workflowId) {
            return null;
        }

        @Override
        public ExecutionTrace.Snapshot snapshot(String workflowId) {
            return null;
        }
    };

    /** 返回一个 no-op Registry（register 永远返回 null，snapshot 永远返回 null）。 */
    public static ExecutionTraceRegistry noop() {
        return NOOP;
    }

    // 构造器：包私有以鼓励通过 Spring @Bean 注入，但允许子类化 no-op
    public ExecutionTraceRegistry() {
    }
}
