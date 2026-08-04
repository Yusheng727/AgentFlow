package com.agentflow.observability;

import com.fasterxml.jackson.annotation.JsonCreator;
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
    /** super-step 层号（U10 后续 #10）：由 BspEngine 在超步骤执行后写入，供 UI 按真实拓扑分组（默认 0）。 */
    private volatile int step;

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

    /**
     * Jackson 反序列化工厂（U10 后续 #11）：{@code /api/diagnosis} 提交真实 trace 快照时按 JSON
     * 还原 NodeTrace，供 {@code DiagnosisService} 分析。结构字段（nodeId/agent/status/token/error/step）
     * 完整还原；startNanos 置 0 → {@code duration()/durationMs()} 返回 0——诊断 5 条规则不依赖耗时精度。
     */
    @JsonCreator
    static NodeTrace fromJson(
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("agentName") String agentName,
            @JsonProperty("status") Status status,
            @JsonProperty("promptTokens") long promptTokens,
            @JsonProperty("completionTokens") long completionTokens,
            @JsonProperty("outputSummary") String outputSummary,
            @JsonProperty("error") String error,
            @JsonProperty("step") int step) {
        NodeTrace n = new NodeTrace(nodeId, agentName, 0L);
        n.status = status != null ? status : Status.RUNNING;
        n.promptTokens = promptTokens;
        n.completionTokens = completionTokens;
        n.outputSummary = outputSummary;
        n.error = error;
        n.step = step;
        return n;
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

    /** 所属 super-step 层号（默认 0）。由 BspEngine 在超步骤执行后写入（U10 后续 #10）。 */
    @JsonProperty("step")
    public int step() {
        return step;
    }

    /** 引擎写入所属 super-step 层号（包级；U10 后续 #10）。 */
    void step(int step) {
        this.step = step;
    }

    @JsonProperty("promptTokens")
    public long promptTokens() {
        return promptTokens;
    }

    @JsonProperty("completionTokens")
    public long completionTokens() {
        return completionTokens;
    }

    /** 派生汇总（serialize 输出；READ_ONLY——反序列化时由 prompt+completion 派生，不参与写入）。 */
    @JsonProperty(value = "totalTokens", access = JsonProperty.Access.READ_ONLY)
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
    /** 已用时长（毫秒）——与 UI NodeTrace 类型 + StructuredLogger 的 {@code durationMs} 字段保持 wire 契约一致。
     * READ_ONLY：仅序列化输出（UI 读）；反序列化时忽略（诊断不使用耗时精度）。 */
    @JsonProperty(value = "durationMs", access = JsonProperty.Access.READ_ONLY)
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
