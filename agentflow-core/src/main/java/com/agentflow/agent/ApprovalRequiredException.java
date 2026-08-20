package com.agentflow.agent;

import java.util.Map;

/**
 * 审批请求异常（U1 HITL）：Agent 在需要人类决策时抛出，工作流在 super-step barrier 处暂停。
 *
 * <p>不属于 Transient/Fatal——引擎把它从失败路径区分出来：不重试、不 abort，而是持久化审批单并置
 * {@link com.agentflow.engine.checkpoint.WorkflowStatus#AWAITING_APPROVAL}。恢复批准后，同一 Agent
 * 凭 {@link com.agentflow.agent.AgentInput#approvalDecision()} 返回真实输出。
 *
 * <p>{@code requestPayload} 为待审载荷（可空，如付款金额/上下文摘要），给人审阅。
 */
public class ApprovalRequiredException extends AgentExecutionException {

    /** 保留 agent 名：审批门 agent（demo/wiring 注册为此名；SemanticValidator 据此做布局 WARN）。 */
    public static final String APPROVAL_AGENT_NAME = "approval";

    private final String nodeId;
    private final String requestDescription;
    private final Map<String, Object> requestPayload;

    public ApprovalRequiredException(String nodeId, String requestDescription) {
        this(nodeId, requestDescription, Map.of());
    }

    public ApprovalRequiredException(String nodeId, String requestDescription,
                                     Map<String, Object> requestPayload) {
        super("需要人工审批: node=" + nodeId + " — " + requestDescription);
        this.nodeId = nodeId;
        this.requestDescription = requestDescription;
        this.requestPayload = requestPayload == null ? Map.of() : requestPayload;
    }

    public String nodeId() {
        return nodeId;
    }

    public String requestDescription() {
        return requestDescription;
    }

    public Map<String, Object> requestPayload() {
        return requestPayload;
    }
}