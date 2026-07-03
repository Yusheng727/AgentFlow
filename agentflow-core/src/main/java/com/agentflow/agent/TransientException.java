package com.agentflow.agent;

/**
 * 可重试异常（KTD-6）。网络超时、429 限流等瞬时故障——U4 RetryPolicy 触发指数退避重试。
 */
public class TransientException extends AgentExecutionException {

    public TransientException(String message) {
        super(message);
    }

    public TransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
