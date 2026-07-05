package com.agentflow.engine.fault;

import com.agentflow.agent.AgentExecutionException;
import com.agentflow.agent.FatalException;
import com.agentflow.agent.TransientException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * 异常分类器（KTD-6 异常合约 / plan U4）。
 *
 * <p>把节点执行失败的原因分为 <b>transient</b>（可重试）与 <b>fatal</b>（不可重试），
 * 供 {@link RetryPolicy} 决定是否重试。分类规则：
 * <ul>
 *   <li>{@link TransientException} → transient（agent 已显式标记可重试）</li>
 *   <li>{@link FatalException} → fatal（agent 已显式标记不可重试）</li>
 *   <li>{@link TimeoutException} / {@link InterruptedException} / {@link IOException} → transient
 *       （节点超时与网络瞬时故障，可重试）</li>
 *   <li>{@code java.net.*} / {@code org.springframework.web.client.*} → transient（网络/HTTP 瞬时错误）</li>
 *   <li>{@link AgentExecutionException}（基类，未细分）→ fatal（保守不重试）</li>
 *   <li>其余 {@link RuntimeException} → fatal（未知错误保守不重试，U4 之后可细化）</li>
 * </ul>
 *
 * <p>与 {@code SpringAiAgentAdapter.mapException} 的映射一致（U3 粗分类），本类是 engine 层
 * canonical 分类器；后续可让 adapter 复用本类消除重复（U4 范围外）。
 */
@FunctionalInterface
public interface ErrorClassifier {

    /** 是否为可重试（transient）故障。 */
    boolean isTransient(Throwable cause);

    /**
     * 默认分类器（上述规则）。
     */
    static ErrorClassifier defaultClassifier() {
        return cause -> {
            if (cause == null) {
                return false;
            }
            if (cause instanceof TransientException) {
                return true;
            }
            if (cause instanceof FatalException) {
                return false;
            }
            if (cause instanceof TimeoutException || cause instanceof InterruptedException
                    || cause instanceof IOException) {
                return true;
            }
            String cn = cause.getClass().getName();
            return cn.startsWith("java.net.") || cn.startsWith("org.springframework.web.client.");
        };
    }
}
