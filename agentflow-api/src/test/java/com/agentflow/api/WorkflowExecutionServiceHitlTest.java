package com.agentflow.api;

import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.ApprovalGateAgent;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.checkpoint.ApprovalDecision;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import com.agentflow.version.InMemoryWorkflowDefinitionStore;
import com.agentflow.version.WorkflowVersionManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U6 服务层 HITL 感知测试：{@code WorkflowExecutionService} 在审批暂停后不误标 SUCCESS、
 * {@code resumeAfterApproval} 续跑至终态（approve/reject）。
 *
 * <p>这是 plan U6 的关键服务层断言（integration）：审批工作流经 {@code run()} 停在
 * {@code AWAITING_APPROVAL}（而非被错误覆盖为 SUCCESS），批准后续跑至 SUCCESS、拒绝至 FAILED；
 * 无审批工作流回归仍 SUCCESS。
 */
class WorkflowExecutionServiceHitlTest {

    private static final String APPROVAL_YAML = """
            agentflow:
              version: "1.0"
            nodes:
              - { id: gate, agent: approval }
              - { id: finalize, agent: echo, mock_response: "done" }
            edges:
              - { from: gate, to: finalize }
            """;

    private final WorkflowDSLParser parser = new WorkflowDSLParser();
    private InMemoryCheckpointManager cm;
    private WorkflowVersionManager versionManager;
    private NodeRegistry registry;
    private WorkflowExecutionService svc;

    @BeforeEach
    void setUp() {
        cm = new InMemoryCheckpointManager();
        versionManager = new WorkflowVersionManager(new InMemoryWorkflowDefinitionStore());
        registry = new NodeRegistry(Map.of("approval", new ApprovalGateAgent(),
                "echo", input -> AgentOutput.of(input.mockResponse())));
        svc = new WorkflowExecutionService(new BspEngine(), registry, cm, new ChannelReducer(), versionManager);
    }

    private WorkflowDefinition parse() {
        return parser.parse(new ByteArrayInputStream(APPROVAL_YAML.getBytes(StandardCharsets.UTF_8)));
    }

    private String stageWorkflow() {
        versionManager.recordWorkflowDefinition("hitl", parse());
        cm.initWorkflow("hitl-1", "hitl", "1.0", "caller");
        return "hitl-1";
    }

    @Test
    @DisplayName("run() 遇审批暂停：不误标 SUCCESS，停 AWAITING_APPROVAL；finalize 未跑")
    void runStopsAtAwaitingApprovalNotSuccess() {
        String wfId = stageWorkflow();

        svc.run(wfId, "hitl", "1.0", Map.of());

        // 关键断言：run() 不覆盖为 SUCCESS（U4 paused 是合法中间态）
        assertThat(cm.findStatus(wfId)).contains(WorkflowStatus.AWAITING_APPROVAL);
        // 下游节点未执行（审批切断后续）
        assertThat(cm.findPendingApprovals(wfId)).hasSize(1);
    }

    @Test
    @DisplayName("resumeAfterApproval(APPROVE)：待批节点重跑 → 下游续跑 → SUCCESS，审批 APPROVED")
    void resumeAfterApproval_approveReachesSuccess() {
        String wfId = stageWorkflow();
        svc.run(wfId, "hitl", "1.0", Map.of());
        String approvalId = cm.findPendingApprovals(wfId).get(0).approvalId();

        WorkflowStatus status = svc.resumeAfterApproval(wfId, "hitl", "1.0", approvalId,
                ApprovalDecision.APPROVE, "caller");

        assertThat(status).isEqualTo(WorkflowStatus.SUCCESS);
        assertThat(cm.findStatus(wfId)).contains(WorkflowStatus.SUCCESS);
        assertThat(cm.findApprovalById(approvalId)).isNotEmpty();
    }

    @Test
    @DisplayName("resumeAfterApproval(REJECT)：工作流 FAILED")
    void resumeAfterApproval_rejectFails() {
        String wfId = stageWorkflow();
        svc.run(wfId, "hitl", "1.0", Map.of());
        String approvalId = cm.findPendingApprovals(wfId).get(0).approvalId();

        WorkflowStatus status = svc.resumeAfterApproval(wfId, "hitl", "1.0", approvalId,
                ApprovalDecision.REJECT, "caller");

        assertThat(status).isEqualTo(WorkflowStatus.FAILED);
        assertThat(cm.findStatus(wfId)).contains(WorkflowStatus.FAILED);
    }

    @Test
    @DisplayName("无审批工作流：run() 正常 SUCCESS（回归）")
    void run_noApprovalSucceeds() {
        // 复用审批工作流的 downstream 语义：把 gate 改成 echo（无审批）→ 全链路 SUCCESS
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: gate, agent: echo, mock_response: "g" }
                  - { id: finalize, agent: echo, mock_response: "done" }
                edges:
                  - { from: gate, to: finalize }
                """;
        versionManager.recordWorkflowDefinition("plain", parser.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
        cm.initWorkflow("plain-1", "plain", "1.0", "caller");

        svc.run("plain-1", "plain", "1.0", Map.of());

        assertThat(cm.findStatus("plain-1")).contains(WorkflowStatus.SUCCESS);
        assertThat(cm.findPendingApprovals("plain-1")).isEmpty();
    }
}