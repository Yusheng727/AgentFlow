package com.agentflow.dsl;

/**
 * DSL 解析或语义/DAG 校验失败时抛出。携带精确字段信息，便于定位 YAML 错误。
 */
public class WorkflowValidationException extends RuntimeException {

    public WorkflowValidationException(String message) {
        super(message);
    }

    public WorkflowValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
