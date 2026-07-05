package com.agentflow.observability;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 执行追踪树（U3 引入，KTD-可观测）。
 *
 * <p>根（workflow 级）+ 子（每节点执行 {@link NodeTrace}）。NodeTrace 由 {@code SpringAiAgentAdapter}
 * （U3）在 execute() 中写入（OQ-3 决议：适配器持 nodeId + chatResponse，是唯一写入者；
 * LoggingAdvisor 只做结构化日志不写 trace）。后续 {@code DiagnosisService}（U6 分析 5 类问题）、
 * Micrometer 指标（U7 接入）读取。
 *
 * <p>线程安全：同 super-step 多节点并行追加子 trace 由 {@link CopyOnWriteArrayList} 承载；
 * 根级 end/status 用 volatile 保证 barrier 后跨线程读可见。
 *
 * <p>不可变快照：{@link #snapshot()} 返回冻结的 {@link Snapshot}，供 REST 端点（TraceController，U7）
 * 与 DiagnosisService 读取，读时不再受并发写影响。
 */
public final class ExecutionTrace {

    private final String workflowId;
    private final Instant startTime;
    private volatile Instant endTime;
    private volatile Status status = Status.RUNNING;
    private final CopyOnWriteArrayList<NodeTrace> nodes = new CopyOnWriteArrayList<>();

    public ExecutionTrace(String workflowId) {
        this.workflowId = workflowId;
        this.startTime = Instant.now();
    }

    /** 追加一个节点 trace（由 LoggingAdvisor 在节点开始时调用）。 */
    public void addNode(NodeTrace node) {
        if (node != null) {
            nodes.add(node);
        }
    }

    /** 标记工作流终结。 */
    public void markCompleted(Status status) {
        this.endTime = Instant.now();
        this.status = status;
    }

    public String workflowId() {
        return workflowId;
    }

    public Instant startTime() {
        return startTime;
    }

    public Instant endTime() {
        return endTime;
    }

    public Status status() {
        return status;
    }

    /** 节点 trace 列表（实时视图，按追加序）。 */
    public List<NodeTrace> nodes() {
        return List.copyOf(nodes);
    }

    /** 全工作流 token 总和（已完成节点）。 */
    public long totalTokens() {
        return nodes.stream().mapToLong(NodeTrace::totalTokens).sum();
    }

    /** 已终结节点数。 */
    public long terminalNodeCount() {
        return nodes.stream().filter(NodeTrace::isTerminal).count();
    }

    /** 不可变快照。 */
    public Snapshot snapshot() {
        return new Snapshot(workflowId, startTime, endTime, status,
                List.copyOf(nodes), totalTokens());
    }

    public enum Status {
        RUNNING, COMPLETED, FAILED
    }

    /** 不可变快照，供 REST/分析读取。 */
    public record Snapshot(
            String workflowId,
            Instant startTime,
            Instant endTime,
            Status status,
            List<NodeTrace> nodes,
            long totalTokens
    ) {
    }
}
