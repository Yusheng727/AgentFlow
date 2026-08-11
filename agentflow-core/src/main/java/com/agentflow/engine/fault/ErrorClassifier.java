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
 *   <li>{@code java.net.*} → transient（网络瞬时错误）</li>
 *   <li>{@link AgentExecutionException}（基类，未细分）→ fatal（保守不重试）</li>
 *   <li>其余 {@link RuntimeException} → fatal（未知错误保守不重试，U4 之后可细化）</li>
 * </ul>
 *
 * <p><b>框架无关（v1.1 B2/M2）</b>：core 不持有任何 LLM 框架的异常知识（曾含
 * {@code org.springframework.web.client.*} 前缀，已移除）。各适配器通过
 * {@link #composed(ErrorClassifier...)} 注册<b>自己的</b>框架 transient 分类器——
 * 网络/限流/超时等可重试异常由适配器判定，core 只认框架无关的根基异常。
 *
 * <p>与 {@code SpringAiAgentAdapter}/{@code LangChain4jAgentAdapter} 的 mapException 一致，本类是
 * engine 层 canonical 分类器；适配器 map 时用 {@code composed(框架分类器)} 注入框架特有规则。
 */
@FunctionalInterface
public interface ErrorClassifier {

    /** 是否为可重试（transient）故障。 */
    boolean isTransient(Throwable cause);

    /**
     * 默认分类器（上述框架无关规则）。
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
            return cause.getClass().getName().startsWith("java.net.");
        };
    }

    /**
     * 组合分类器：任一框架分类器判 transient，或基础规则判 transient → transient。
     *
     * <p>适配器用它注入框架特有规则（如 LangChain4j 的 {@code RetriableException}、Spring 的
     * {@code org.springframework.web.client.*}），同时保留 core 的框架无关根基规则。
     * 仅 add 不能 veto：框架分类器只能把"基础规则认为 fatal"的抬成 transient，不能否决基础 transient。
     *
     * @param frameworks 框架特有分类器（各 LLM 适配器注册；可为空 = 纯基础规则）
     */
    static ErrorClassifier composed(ErrorClassifier... frameworks) {
        ErrorClassifier base = defaultClassifier();
        // 防御性拷贝：varargs 数组被调用方持有，防止外部突变改变本组合的行为（review api-contract）
        ErrorClassifier[] fws = frameworks == null ? new ErrorClassifier[0] : frameworks.clone();
        return cause -> {
            if (cause == null) {
                return false;
            }
            if (base.isTransient(cause)) {
                return true;
            }
            for (ErrorClassifier fw : fws) {
                if (fw != null && fw.isTransient(cause)) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * 把 LLM 调用抛出的异常映射为 {@link TransientException}/{@link FatalException}（review B2 P1 修复）。
     *
     * <p><b>沿 cause 兜底是关键</b>：LLM 框架的 transient 标记常包裹底层异常——LangChain4j 的真实
     * {@code RateLimitException} 是 {@code new RateLimitException(httpException)}（marker 在<b>外层</b>，
     * 内层是 {@code HttpException}）。若适配器先 unwrap 再分类会剥掉标记 → 误判 fatal 不重试。故对
     * {@code thrown} 及其 {@code getCause()} 各判一次（框架标记在外层的场景由此兜住）。
     * 消息取 cause 链最深非空，避免 null message。
     *
     * <p>两适配器共用（消除重复的 mapException，M1）。
     *
     * @param classifier 组合分类器（框架规则 + 基础规则）
     * @param thrown     ChatModel 调用直接抛出的原始异常（未 unwrap，保留框架标记）
     */
    static AgentExecutionException toExecutionException(ErrorClassifier classifier, Throwable thrown) {
        ErrorClassifier c = classifier == null ? defaultClassifier() : classifier;
        boolean transientFlag = c.isTransient(thrown)
                || (thrown != null && thrown.getCause() != null && c.isTransient(thrown.getCause()));
        String msg = deepestMessage(thrown);
        String text = msg == null || msg.isBlank() ? "无详情" : msg;
        return transientFlag
                ? new TransientException("Transient LLM 调用失败: " + text, thrown)
                : new FatalException("LLM 调用失败: " + text, thrown);
    }

    /** 取 cause 链上最深层非空消息（越深越具体，如 LC4j 包在 HttpException 上的具体错误）。 */
    private static String deepestMessage(Throwable t) {
        String msg = null;
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                msg = cur.getMessage();
            }
        }
        return msg;
    }
}
