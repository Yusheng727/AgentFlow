package com.agentflow.engine.fault;

import com.agentflow.agent.AgentInput;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.engine.NodeExecutor;
import com.agentflow.engine.NodeResult;

import java.time.Duration;
import java.util.Objects;

/**
 * 重试策略（KTD-6 异常合约 / plan U4）。
 *
 * <p>包在 {@link NodeExecutor} 外层：每次 attempt 调 {@code nodeExecutor.execute}，
 * Success 即返；Failure 时按 {@link ErrorClassifier} 判定——transient 且未耗尽则指数退避后重试，
 * fatal 或耗尽则返 {@link NodeResult.Failure}。
 *
 * <p>退避：{@code initialBackoff × multiplier^(attempt-1)}——默认 1s→2s→4s，最多 3 次 attempt
 * （plan U4）。仅在 attempt 间 sleep，最后一次不 sleep。
 *
 * <p><b>retry 预算（plan v4.2）</b>：本类的 3 次 attempt × 每次 attempt 内
 * {@code SpringAiAgentAdapter.validateWithRetry} 的 ≤2 次 schema-retry = 最多 9 次 LLM 调用
 * （组合式，非共享计数器——schema-retry 嵌在 attempt 内，RetryPolicy 不感知它）。
 *
 * <p>线程安全：单次 execute 在单个 VT 上顺序跑（retry 不并发），无共享可变状态。
 */
public final class RetryPolicy {

    static final int DEFAULT_MAX_ATTEMPTS = 3;
    static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
    static final double DEFAULT_MULTIPLIER = 2.0;

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final double multiplier;
    private final ErrorClassifier classifier;

    public RetryPolicy() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_BACKOFF, DEFAULT_MULTIPLIER, ErrorClassifier.defaultClassifier());
    }

    public RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, ErrorClassifier classifier) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须 >= 1: " + maxAttempts);
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        if (initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff 不可为负: " + initialBackoff);
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier 必须 >= 1.0: " + multiplier);
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.multiplier = multiplier;
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /**
     * 带 retry 地执行单节点。返回 Success 或（耗尽/fatal 后的）Failure。
     *
     * @param executor 被包裹的 NodeExecutor（已含节点级超时 + cancel）
     */
    public NodeResult execute(NodeExecutor executor, NodeDefinition node, AgentInput input) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(input, "input");
        Throwable lastCause = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            NodeResult r = executor.execute(node, input);
            if (r instanceof NodeResult.Success) {
                return r;
            }
            // Failure：取 cause 判定是否重试
            lastCause = ((NodeResult.Failure) r).error();
            if (!classifier.isTransient(lastCause)) {
                break;  // fatal → 不重试
            }
            if (attempt < maxAttempts) {
                sleep(backoffFor(attempt));
            }
        }
        return new NodeResult.Failure(node.id(), lastCause);
    }

    /** attempt=1 → initialBackoff；attempt=2 → initialBackoff×multiplier；以此类推。 */
    private Duration backoffFor(int attempt) {
        long mult = (long) Math.pow(multiplier, attempt - 1);
        return initialBackoff.multipliedBy(mult);
    }

    /** 可中断的退避 sleep；被中断则恢复中断标志并抛 RuntimeException（不应在正常流程发生）。 */
    private void sleep(Duration d) {
        if (d.isZero()) {
            return;
        }
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("retry backoff 被中断", e);
        }
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration initialBackoff() {
        return initialBackoff;
    }

    public double multiplier() {
        return multiplier;
    }
}
