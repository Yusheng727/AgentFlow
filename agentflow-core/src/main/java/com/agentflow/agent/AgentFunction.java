package com.agentflow.agent;

/**
 * Agent 函数式 SPI（KTD-6）。
 *
 * <p>对 Agent 实现无侵入：{@link com.agentflow.adapters.springai.SpringAiAgentAdapter}（U3）
 * 深度整合 Spring AI Advisor Chain 是其一种实现；测试与非 LLM Agent 可直接 lambda 实现。
 *
 * <ul>
 *   <li>{@code execute} — 异常合约：抛 {@link AgentExecutionException}（区分 Transient/Fatal）；
 *       引擎据此做异常隔离（同 super-step 其他节点不受影响）</li>
 *   <li>{@code cancel} — 默认 noop（对非 LLM/测试 Agent 友好）；SpringAiAgentAdapter <b>必须覆盖</b>
 *       接底层 HTTP 中断，超时触发时真正中止在飞 LLM 调用、停止 token 计费（KTD-6 v4.3）</li>
 *   <li>{@code supportsStreaming} — v1 不支持流式，预留</li>
 * </ul>
 */
public interface AgentFunction {

    AgentOutput execute(AgentInput input) throws AgentExecutionException;

    default void cancel(AgentInput input) {
        // noop — 非 LLM/测试 Agent 用；生产 LLM-backed Agent 必须覆盖
    }

    default boolean supportsStreaming() {
        return false;
    }
}
