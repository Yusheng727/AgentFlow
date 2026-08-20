package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.ApprovalRequiredException;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.checkpoint.ApprovalRequest;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.RecoveryProtocol;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import com.agentflow.observability.AgentFlowMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U4 引擎审批暂停（pause-on-approval）：super-step barrier 识别 {@code ApprovalRequired} →
 * 持久化审批单 + 上下文快照、置 {@code AWAITING_APPROVAL}、提前退出且不置终态。
 *
 * <p>覆盖 plan U4 全部测试场景：单节点审批 happy / 同层兄弟 Success 入快照 / 同层兄弟 Fail 仍暂停 /
 * 无审批回归（approvalDecision 恒 null）/ paused 不写 success-failed 指标而记审批计数 / recoverAndExecute
 * 对 AWAITING_APPROVAL 不误恢复（防御）。
 */
class BspEngineApprovalPauseTest {

    private InMemoryCheckpointManager cp;

    @BeforeEach
    void setUp() {
        cp = new InMemoryCheckpointManager();
    }

    /** 回显 mock_response 的内联 agent（普通节点用）。 */
    private static final AgentFunction ECHO = input -> AgentOutput.of(input.mockResponse());

    private static WorkflowDefinition parse(String yaml) throws Exception {
        return new WorkflowDSLParser().parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private WorkflowContext run(String yaml, Map<String, AgentFunction> agents, String wfId) throws Exception {
        return new BspEngine(new DAGLayerer(), null, null, null, null, null)
                .execute(parse(yaml), new NodeRegistry(agents), Map.of(), cp, new ChannelReducer(), wfId);
    }

    // ────────────────────── 场景 1：单节点审批 happy ──────────────────────

    @Test
    @DisplayName("单节点审批：engine 返回（不抛）、status=AWAITING_APPROVAL、下游未运行、审批单含快照")
    void singleApprovalPausesAndPersists() throws Exception {
        AtomicInteger downstreamCalls = new AtomicInteger();
        AgentFunction downstream = input -> {
            downstreamCalls.incrementAndGet();
            return AgentOutput.of("ran");
        };
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: gate, agent: approval }
                  - { id: pay, agent: downstream }
                edges:
                  - { from: gate, to: pay }
                """;

        WorkflowContext result = run(yaml, Map.of("approval", new ApprovalGateAgent(), "downstream", downstream),
                "wf-pause-single");

        // engine 返回而非抛异常；status 停在 AWAITING_APPROVAL
        assertThat(result).isNotNull();
        assertThat(cp.findStatus("wf-pause-single")).contains(WorkflowStatus.AWAITING_APPROVAL);
        // 下游（pay）未运行——暂停切断后续
        assertThat(downstreamCalls.get()).isZero();
        // 审批单持久化 1 条 PENDING，nodeId=gate，含上下文快照
        assertThat(cp.findPendingApprovals("wf-pause-single")).hasSize(1);
        ApprovalRequest req = cp.findPendingApprovals("wf-pause-single").get(0);
        assertThat(req.nodeId()).isEqualTo("gate");
        assertThat(req.round()).isZero();
        assertThat(req.superStep()).isZero();
        assertThat(req.contextSnapshot()).isNotNull();
        // 该层未写 barrier：无已完成节点
        assertThat(cp.findCompletedNodes("wf-pause-single", 0)).isEmpty();
    }

    // ────────────────────── 场景 2：同层兄弟 Success 入快照 ──────────────────────

    @Test
    @DisplayName("同层兄弟 Success：兄弟输出并入暂停快照、审批节点不产出")
    void brotherSuccessOutputEntersSnapshot() throws Exception {
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: gate, agent: approval }
                  - { id: prep, agent: mock, mock_response: "prepped" }
                  - { id: pay, agent: downstream }
                edges:
                  - { from: gate, to: pay }
                """;

        run(yaml, Map.of("approval", new ApprovalGateAgent(),
                "mock", ECHO,
                "downstream", input -> AgentOutput.of("ran")), "wf-pause-brother");

        ApprovalRequest req = cp.findPendingApprovals("wf-pause-brother").get(0);
        // 兄弟 prep 的 channel 输出已进快照（审批节点 gate 无输出）
        assertThat(req.contextSnapshot()).containsKey("prep");
        assertThat(req.contextSnapshot().get("prep")).isEqualTo("prepped");
        assertThat(req.contextSnapshot()).doesNotContainKey("gate");
        assertThat(cp.findStatus("wf-pause-brother")).contains(WorkflowStatus.AWAITING_APPROVAL);
    }

    // ────────────────────── 场景 3：同层兄弟 Fail 仍暂停 ──────────────────────

    @Test
    @DisplayName("同层兄弟 Fail：仍以审批暂停为准（不 abort），快照不含失败节点输出")
    void brotherFailureStillPauses() throws Exception {
        AgentFunction boom = input -> {
            throw new RuntimeException("brother boom");
        };
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: gate, agent: approval }
                  - { id: risk, agent: boom }
                  - { id: pay, agent: downstream }
                edges:
                  - { from: gate, to: pay }
                """;

        run(yaml, Map.of("approval", new ApprovalGateAgent(),
                "boom", boom,
                "downstream", input -> AgentOutput.of("ran")), "wf-pause-brother-fail");

        // 审批优先：尽管同层有失败，工作流仍是 AWAITING_APPROVAL 而非 FAILED
        assertThat(cp.findStatus("wf-pause-brother-fail")).contains(WorkflowStatus.AWAITING_APPROVAL);
        assertThat(cp.findPendingApprovals("wf-pause-brother-fail")).hasSize(1);
        // 失败节点无输出进快照
        assertThat(cp.findPendingApprovals("wf-pause-brother-fail").get(0).contextSnapshot())
                .doesNotContainKey("risk");
    }

    // ────────────────────── 场景 4：无审批回归 ──────────────────────

    @Test
    @DisplayName("无审批工作流：两节点均执行、不暂停、AgentInput.approvalDecision() 恒 null（回归）")
    void noApprovalSucceedsAndDecisionNull() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // 捕获节点拿到的 approvalDecision：无审批时应恒 null
        java.util.concurrent.atomic.AtomicReference<Object> seenDecision = new java.util.concurrent.atomic.AtomicReference<>("sentinel");
        AgentFunction probe = input -> {
            calls.incrementAndGet();
            seenDecision.set(input.approvalDecision());
            return AgentOutput.of(input.mockResponse());
        };
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: A, agent: probe, mock_response: "a" }
                  - { id: B, agent: probe, mock_response: "b" }
                edges:
                  - { from: A, to: B }
                """;

        WorkflowContext result = run(yaml, Map.of("probe", probe), "wf-no-approval");

        // 两节点都执行（未暂停切断）；不产生审批单
        assertThat(calls.get()).isEqualTo(2);
        assertThat(cp.findPendingApprovals("wf-no-approval")).isEmpty();
        // 非暂停态（engine 不写 SUCCESS，那是 WorkflowExecutionService 职责）
        assertThat(cp.findStatus("wf-no-approval").orElse(null))
                .isNotEqualTo(WorkflowStatus.AWAITING_APPROVAL);
        // 下游 channel 输出存在；approvalDecision 恒 null（非审批恢复执行）
        assertThat(result.getValue("B")).isEqualTo("b");
        assertThat(seenDecision.get()).isNull();
    }

    // ────────────────────── 场景 5：paused 指标 ──────────────────────

    @Test
    @DisplayName("paused 后：不写 workflow.executed{success|failed}，而记 approval.event{pending}")
    void pausedRecordsApprovalMetricNotExecuted() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentFlowMetrics metrics = new AgentFlowMetrics(registry);
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: gate, agent: approval }
                """;

        new BspEngine(new DAGLayerer(), null, null, null, null, metrics)
                .execute(parse(yaml), new NodeRegistry(Map.of("approval", new ApprovalGateAgent())),
                        Map.of(), cp, new ChannelReducer(), "wf-pause-metrics");

        // 不记 success/failed（暂停不是终态）
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_EXECUTED, "status", "success").count()).isZero();
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_EXECUTED, "status", "failed").count()).isZero();
        // 记审批 pending 计数
        assertThat(registry.counter(AgentFlowMetrics.WORKFLOW_APPROVAL_EVENT, "status", "pending").count())
                .isEqualTo(1.0);
    }

    // ────────────────────── 场景 6：recoverAndExecute 防御 ──────────────────────

    @Test
    @DisplayName("AWAITING_APPROVAL 状态：recoverAndExecute 拒绝恢复（抛 IllegalState，U5 前防御）")
    void recoveryRefusesAwaitingApproval() throws Exception {
        String wfId = "wf-pause-recovery-guard";
        cp.initWorkflow(wfId, "wf-pause-recovery-guard", "1.0", "test");
        cp.updateStatus(wfId, WorkflowStatus.AWAITING_APPROVAL);

        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: gate, agent: approval }
                """;
        RecoveryProtocol recovery = new RecoveryProtocol(cp);

        assertThatThrownBy(() -> new BspEngine(new DAGLayerer())
                .recoverAndExecute(recovery, parse(yaml), new NodeRegistry(Map.of()), new ChannelReducer(), wfId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWAITING_APPROVAL");
        // 状态不被误改
        assertThat(cp.findStatus(wfId)).contains(WorkflowStatus.AWAITING_APPROVAL);
    }
}
