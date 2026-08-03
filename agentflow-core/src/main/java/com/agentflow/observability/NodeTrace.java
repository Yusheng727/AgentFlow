package com.agentflow.observability;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

/**
 * 单节点执行追踪（U3 引入，KTD-可观测）。
 *
 * <p>由 {@code SpringAiAgentAdapter}（U3）在 {@code execute()} 中写入：
 * 构造时记 start，{@link #succeed} / {@link #fail} 时记 end + 状态 + token + 摘要。
 * 适配器持有 nodeId + chatResponse.usage，是 NodeTrace 的唯一写入者（OQ-3 决议，
 * 避免并行 VT 共享可变状态；LoggingAdvisor 只做结构化日志，不写 NodeTrace）。
 *
 * <p>线程安全：单节点 trace 由执行该节点的单个 Virtual Thread 写入，
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
        this(nodeId, agentName, System.nanoTime());
    }

    /** 测试钩子：固定 startNanos（纳秒），使 duration/durationMs 断言确定（生产用 {@link System#nanoTime}）。 */
    NodeTrace(String nodeId, String agentName, long startNanos) {
        this.nodeId = nodeId;
        this.agentName = agentName;
        this.startNanos = startNanos;
    }

    /** 测试钩子：固定 end 时间戳（纳秒）。 */
    void endNanosForTest(long endNanos) {
        this.endNanos = endNanos;
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

    @JsonProperty("nodeId")
    public String nodeId() {
        return nodeId;
    }

    @JsonProperty("agentName")
    public String agentName() {
        return agentName;
    }

    @JsonProperty("status")
    public Status status() {
        return status;
    }

    @JsonProperty("promptTokens")
    public long promptTokens() {
        return promptTokens;
    }

    @JsonProperty("completionTokens")
    public long completionTokens() {
        return completionTokens;
    }

    @JsonProperty("totalTokens")
    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    @JsonProperty("outputSummary")
    public String outputSummary() {
        return outputSummary;
    }

    @JsonProperty("error")
    public String error() {
        return error;
    }

    /**
     * 已用时长（毫秒）——与 UI NodeTrace 类型 + StructuredLogger 的 {@code durationMs} 字段
     * 保持 wire 契约一致。未结束则返回到当前的实时时长。
     */
    @JsonProperty("durationMs")
    public long durationMs() {
        return duration().toMillis();
    }

    /** 已用时长。未结束则返回到当前的实时时长。（不参与 JSON 序列化，wire 用 {@link #durationMs}） */
    @JsonIgnore
    public Duration duration() {
        long end = endNanos > 0 ? endNanos : System.nanoTime();
        return Duration.ofNanos(end - startNanos);
    }

    /** 是否已终结（SUCCESS / FAILED）。 */
    @JsonIgnore
    public boolean isTerminal() {
        return status == Status.SUCCESS || status == Status.FAILED;
    }

    public enum Status {
        RUNNING, SUCCESS, FAILED
    }
}
