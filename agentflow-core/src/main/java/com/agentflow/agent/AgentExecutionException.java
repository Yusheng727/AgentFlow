package com.agentflow.agent;

/**
 * Agent 执行异常基类（KTD-6 异常合约）。
 *
 * <p>分层为 {@link TransientException}（可重试：网络超时、429 限流）与
 * {@link FatalException}（不可重试：400 内容违规、参数错误）。U4 的 ErrorClassifier
 * 据此决定是否触发 RetryPolicy。非 AgentExecutionException 的 RuntimeException
 * 默认按 fatal 处理。
 *
 * <p>U2 引入最小合约；U4 在其上构建 RetryPolicy / ErrorClassifier / ErrorHandler。
 */
public class AgentExecutionException extends Exception {

    public AgentExecutionException(String message) {
        super(message);
    }

    public AgentExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
