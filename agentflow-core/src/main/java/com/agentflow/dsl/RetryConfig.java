package com.agentflow.dsl;

/**
 * 节点级重试配置（R4）。指数退避：initial_backoff × 2^(attempt-1)，最多 max_attempts 次。
 *
 * <pre>
 * retry:
 *   max_attempts: 3
 *   initial_backoff: 1s
 * </pre>
 */
public record RetryConfig(int maxAttempts, String initialBackoff) {
}
