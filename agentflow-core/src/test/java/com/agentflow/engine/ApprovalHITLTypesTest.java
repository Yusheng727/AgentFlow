package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.ApprovalRequiredException;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.SemanticValidator;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.engine.checkpoint.ApprovalRequest;
import com.agentflow.engine.checkpoint.ApprovalStatus;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import com.agentflow.engine.fault.ErrorClassifier;
import com.agentflow.engine.fault.RetryPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** U1 审批核心类型 + NodeExecutor/RetryPolicy 第三态透传 + SemanticValidator WARN。 */
class ApprovalHITLTypesTest {

    private NodeDefinition node(String id, String agent) {
        return new NodeDefinition(id, agent, null, null, null, null, null, null);
    }

    private AgentInput input(String nodeId) {
        return AgentInput.of(nodeId, "a", null, new WorkflowContext(), Map.of());
    }

    // ────────────────────── 领域类型 ──────────────────────

    @Test
    @DisplayName("ApprovalRequest.pending 构造 PENDING 单 + null 防御")
    void approvalRequestPending() {
        ApprovalRequest r = ApprovalRequest.pending(
                "wf1", "pay", 0, 2, "approve payment", Map.of("amount", 100), null);
        assertThat(r.workflowId()).isEqualTo("wf1");
        assertThat(r.nodeId()).isEqualTo("pay");
        assertThat(r.superStep()).isEqualTo(2);
        assertThat(r.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(r.decidedBy()).isNull();
        assertThat(r.contextSnapshot()).isEmpty();
        assertThat(r.approvalId()).isNotBlank();
        assertThat(r.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("状态枚举含审批态")
    void statusEnums() {
        assertThat(WorkflowStatus.valueOf("AWAITING_APPROVAL")).isEqualTo(WorkflowStatus.AWAITING_APPROVAL);
        assertThat(com.agentflow.engine.checkpoint.ApprovalDecision.valueOf("APPROVE"))
                .isEqualTo(com.agentflow.engine.checkpoint.ApprovalDecision.APPROVE);
        assertThat(com.agentflow.engine.checkpoint.ApprovalDecision.valueOf("REJECT"))
                .isEqualTo(com.agentflow.engine.checkpoint.ApprovalDecision.REJECT);
        assertThat(ApprovalStatus.valueOf("PENDING")).isEqualTo(ApprovalStatus.PENDING);
    }

    // ────────────────────── NodeExecutor 映射 ──────────────────────

    @Test
    @DisplayName("agent 抛 ApprovalRequiredException → NodeResult.ApprovalRequired（非 Failure）")
    void nodeExecutorMapsApproval() {
        Function<String, AgentFunction> resolver = name -> in -> {
            throw new ApprovalRequiredException(in.nodeId(), "user approval", Map.of("k", "v"));
        };
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            NodeExecutor ne = new NodeExecutor(resolver, exec);
            NodeResult r = ne.execute(node("A", "a"), input("A"));
            assertThat(r).isInstanceOf(NodeResult.ApprovalRequired.class);
            var ar = (NodeResult.ApprovalRequired) r;
            assertThat(ar.nodeId()).isEqualTo("A");
            assertThat(ar.description()).isEqualTo("user approval");
            assertThat(ar.requestPayload()).containsEntry("k", "v");
        }
    }

    @Test
    @DisplayName("普通异常仍 → Failure（回归）")
    void nodeExecutorStillMapsFailure() {
        Function<String, AgentFunction> loader = name -> in -> {
            throw new RuntimeException("boom");
        };
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            NodeExecutor ne = new NodeExecutor(loader, exec);
            NodeResult r = ne.execute(node("A", "a"), input("A"));
            assertThat(r).isInstanceOf(NodeResult.Failure.class);
        }
    }

    // ────────────────────── RetryPolicy 第三态透传 ──────────────────────

    @Test
    @DisplayName("RetryPolicy 收到 ApprovalRequired → 原样透传（不 CCE、不重试）")
    void retryPolicyPassesApprovalThrough() {
        Function<String, AgentFunction> loader = name -> in -> {
            throw new ApprovalRequiredException(in.nodeId(), "approval");
        };
        RetryPolicy rp = new RetryPolicy(3, Duration.ofMillis(1), 1.0, ErrorClassifier.defaultClassifier());
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            NodeExecutor ne = new NodeExecutor(loader, exec);
            NodeResult r = rp.execute(ne, node("A", "a"), input("A"));
            assertThat(r).isInstanceOf(NodeResult.ApprovalRequired.class);
        }
    }

    @Test
    @DisplayName("RetryPolicy fatal 失败仍 → Failure（回归，cast 修复未破坏原逻辑）")
    void retryPolicyStillMapsFatalFailure() {
        Function<String, AgentFunction> loader = name -> in -> {
            throw new RuntimeException("fatal");
        };
        RetryPolicy rp = new RetryPolicy(3, Duration.ofMillis(1), 1.0, ErrorClassifier.defaultClassifier());
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            NodeExecutor ne = new NodeExecutor(loader, exec);
            NodeResult r = rp.execute(ne, node("A", "a"), input("A"));
            assertThat(r).isInstanceOf(NodeResult.Failure.class);
            assertThat(((NodeResult.Failure) r).error()).hasMessage("fatal");
        }
    }

    // ────────────────────── SemanticValidator WARN ──────────────────────

    @Test
    @DisplayName("审批节点同层有兄弟 → WARN；独立一层 → 无 WARN")
    void approvalLayoutWarnings() {
        // approval 与另一节点同层（都是 level0）
        WorkflowDefinition sibling = new WorkflowDefinition(null, null, List.of(
                node("gate", ApprovalRequiredException.APPROVAL_AGENT_NAME),
                node("peer", "mock")), List.of());
        assertThat(new SemanticValidator().approvalLayoutWarnings(sibling)).isNotEmpty();

        // approval 独立一层（gate→后续）
        WorkflowDefinition isolated = new WorkflowDefinition(null, null, List.of(
                node("gate", ApprovalRequiredException.APPROVAL_AGENT_NAME),
                node("after", "mock")), List.of(new com.agentflow.dsl.EdgeDefinition("gate", "after")));
        assertThat(new SemanticValidator().approvalLayoutWarnings(isolated)).isEmpty();
    }
}