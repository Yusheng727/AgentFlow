package com.agentflow.agent;

/**
 * Mock 模式下节点未配置 mock_response（U9）。
 *
 * <p>{@code @EnableAgentFlow(mockLlm=true)} 时，{@code MockAgentFunction} 从 YAML 节点的
 * {@code mock_response} 字段读预设响应；若该字段缺失，抛本异常——属配置错误，{@link FatalException}
 * 不可重试，直接进 ErrorHandler。
 */
public class MissingMockResponseException extends FatalException {

    private final String nodeId;

    public MissingMockResponseException(String nodeId) {
        super("Mock 模式下节点 " + nodeId + " 缺少 mock_response 配置");
        this.nodeId = nodeId;
    }

    public String nodeId() {
        return nodeId;
    }
}
