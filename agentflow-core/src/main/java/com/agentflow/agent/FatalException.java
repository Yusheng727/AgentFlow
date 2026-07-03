package com.agentflow.agent;

/**
 * 不可重试异常（KTD-6）。400 内容违规、参数错误等永久故障——U4 跳过重试直接进 ErrorHandler。
 */
public class FatalException extends AgentExecutionException {

    public FatalException(String message) {
        super(message);
    }

    public FatalException(String message, Throwable cause) {
        super(message, cause);
    }
}
