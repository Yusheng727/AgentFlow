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
    /** v2 条件分支：路由决策（已走边 from→to），供事后诊断解释为何选某分支。 */
    private final CopyOnWriteArrayList<String> routingDecisions = new CopyOnWriteArrayList<>();
    /** v2 on_error：是否经 on_error 兜底完成（区分「正常完成」与「兜底完成」三终态）。 */
    private volatile boolean completedViaOnError;
    /** v2 循环：最大迭代轮次（默认 0，无回边工作流恒 0）。 */
    private volatile int maxRound;
    /** v2 循环/失败诊断：工作流级失败原因摘要（如「迭代超限」），供 DiagnosisService 识别。 */
    private volatile String workflowError;

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

    /**
     * 记录某节点所属 super-step 层号（U10 后续 #10）：供 UI 按真实 BSP 拓扑分组渲染。
     * 节点尚未追加（如崩溃恢复中跳过未重跑的节点）则 no-op。线程安全：遍历 COW 列表。
     */
    public void recordStep(String nodeId, int step) {
        for (NodeTrace n : nodes) {
            if (n.nodeId().equals(nodeId)) {
                n.step(step);
                return;
            }
        }
    }

    /** 标记工作流终结。 */
    public void markCompleted(Status status) {
        this.endTime = Instant.now();
        this.status = status;
    }

    /** v2 条件分支：记录一次路由决策（已走边 from→to）。 */
    public void recordRoutingDecision(String from, String to) {
        if (from != null && to != null) {
            routingDecisions.add(from + "->" + to);
        }
    }

    /** v2 条件分支：追加一个 SKIPPED 节点 trace（被路由剪枝、未执行）。 */
    public void addSkippedNode(String nodeId, String agentName) {
        if (nodeId == null) {
            return;
        }
        NodeTrace skipped = new NodeTrace(nodeId, agentName == null ? "" : agentName);
        skipped.markSkipped();
        nodes.add(skipped);
    }

    /** v2 on_error：标记工作流经 on_error 兜底完成。 */
    public void markCompletedViaOnError() {
        this.completedViaOnError = true;
    }

    /** v2 循环：记录迭代轮次（取最大，无回边工作流恒 0）。 */
    public void recordRound(int round) {
        if (round > this.maxRound) {
            this.maxRound = round;
        }
    }

    /** 最大迭代轮次（v2 循环）。 */
    public int maxRound() {
        return maxRound;
    }

    /** 记录工作流级失败原因（v2 循环：迭代超限 / 无分支命中等）。 */
    public void recordWorkflowError(String error) {
        this.workflowError = error;
    }

    public String workflowError() {
        return workflowError;
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

    /** 路由决策列表（实时视图，按追加序）。 */
    public List<String> routingDecisions() {
        return List.copyOf(routingDecisions);
    }

    /** 是否经 on_error 兜底完成。 */
    public boolean completedViaOnError() {
        return completedViaOnError;
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
                List.copyOf(nodes), List.copyOf(routingDecisions), completedViaOnError, totalTokens(), maxRound, workflowError);
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
            List<String> routingDecisions,
            boolean completedViaOnError,
            long totalTokens,
            int maxRound,
            String workflowError
    ) {
        /** 便捷构造：无路由决策/兜底标志/轮次/失败原因（向后兼容旧 6-arg 调用点，如 api 测试）。 */
        public Snapshot(String workflowId, Instant startTime, Instant endTime, Status status,
                        List<NodeTrace> nodes, long totalTokens) {
            this(workflowId, startTime, endTime, status, nodes, List.of(), false, totalTokens, 0, null);
        }
    }
}
