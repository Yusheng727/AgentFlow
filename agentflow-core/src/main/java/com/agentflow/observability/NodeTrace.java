package com.agentflow.observability;

import java.time.Duration;

/**
 * 单节点执行追踪（U3 引入，KTD-可观测）。
 *
 * <p>由 {@code LoggingAdvisor}（U3）在 {@code before()}/{@code after()} 钩子中写入：
 * 构造时记 start，{@link #succeed} / {@link #fail} 时记 end + 状态 + token + 摘要。
 *
 * <p>线程安全：单节点 trace 由执行该节点的单个 Virtual Thread 写入（Advisor Chain 同步在该 VT 上跑），
 * 字段用 volatile 保证 barrier 后跨线程读可见。多节点并行追加由
 * {@link ExecutionTrace} 的 CopyOnWriteArrayList 承载。
 */
public final class NodeTrace {

    private final String nodeId;
    private final String agentName;
    private final long startNanos;

    private volatile long endNanos;
    private volatile Status status = Status.RUNNING;
    private volatile long promptTokens;
    private volatile long completionTokens;
    private volatile String outputSummary;
    private volatile String error;

    public NodeTrace(String nodeId, String agentName) {
        this.nodeId = nodeId;
        this.agentName = agentName;
        this.startNanos = System.nanoTime();
    }

    /** 节点执行成功，记录耗时 + token + 输出摘要。 */
    public void succeed(String outputSummary, long promptTokens, long completionTokens) {
        this.endNanos = System.nanoTime();
        this.status = Status.SUCCESS;
        this.outputSummary = outputSummary;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    /** 节点执行失败，记录错误信息。 */
    public void fail(String error) {
        this.endNanos = System.nanoTime();
        this.status = Status.FAILED;
        this.error = error;
    }

    public String nodeId() {
        return nodeId;
    }

    public String agentName() {
        return agentName;
    }

    public Status status() {
        return status;
    }

    public long promptTokens() {
        return promptTokens;
    }

    public long completionTokens() {
        return completionTokens;
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    public String outputSummary() {
        return outputSummary;
    }

    public String error() {
        return error;
    }

    /** 已用时长。未结束则返回到当前的实时时长。 */
    public Duration duration() {
        long end = endNanos > 0 ? endNanos : System.nanoTime();
        return Duration.ofNanos(end - startNanos);
    }

    /** 是否已终结（SUCCESS / FAILED）。 */
    public boolean isTerminal() {
        return status == Status.SUCCESS || status == Status.FAILED;
    }

    public enum Status {
        RUNNING, SUCCESS, FAILED
    }
}
