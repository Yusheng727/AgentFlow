package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.ApprovalRequiredException;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.checkpoint.ApprovalDecision;
import com.agentflow.engine.checkpoint.ApprovalStatus;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.WorkflowStatus;

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
 * U5 引擎审批恢复（approveAndResume）：批准/拒绝后从审批单恢复并续跑下游。
 *
 * <p>覆盖 plan U5 全部测试场景：APPROVE happy（待批节点重跑注入决策 + 下游续跑 + 审批 APPROVED）/
 * REJECT → FAILED 下游不跑 / 多重审批链（重跑仍请求批）/ 上下文恢复（审批前 barrier 输出可见）/
 * 兄弟输出保留不重跑 / takenEdges 预置防覆盖丢边 / 无审批记录明确报错 / 已决策幂等 no-op。
 */
class BspEngineApprovalResumeTest {

    private InMemoryCheckpointManager cp;

    @BeforeEach
    void setUp() {
        cp = new InMemoryCheckpointManager();
    }

    private static WorkflowDefinition parse(String yaml) throws Exception {
        return new WorkflowDSLParser().parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    /** U4 触发暂停：跑审批工作流到 AWAITING_APPROVAL，返回唯一待批审批单 id。 */
    private String runToPaused(String yaml, Map<String, AgentFunction> agents, String wfId) throws Exception {
        new BspEngine(new DAGLayerer(), null, null, null, null, null)
                .execute(parse(yaml), new NodeRegistry(agents), Map.of(), cp, new ChannelReducer(), wfId);
        assertThat(cp.findStatus(wfId)).contains(WorkflowStatus.AWAITING_APPROVAL);
        assertThat(cp.findPendingApprovals(wfId)).hasSize(1);
        return cp.findPendingApprovals(wfId).get(0).approvalId();
    }

    private WorkflowContext approveAndResume(String yaml, Map<String, AgentFunction> agents, String wfId,
                                            String approvalId, ApprovalDecision decision) throws Exception {
        return new BspEngine(new DAGLayerer(), null, null, null, null, null)
                .approveAndResume(parse(yaml), new NodeRegistry(agents), new ChannelReducer(),
                        cp, wfId, approvalId, decision, "approver");
    }

    private static AgentFunction echo() {
        return input -> AgentOutput.of(input.mockResponse());
    }

    // ────────────────────── 场景 1：APPROVE happy ──────────────────────

    @Test
    @DisplayName("APPROVE：待批节点以 decision=APPROVE 重跑产出真实输出，下游续跑至 SUCCESS，审批 APPROVED")
    void approveResumesAndRunsDownstream() throws Exception {
        AtomicInteger payCalls = new AtomicInteger();
        AgentFunction pay = input -> {
            payCalls.incrementAndGet();
            return AgentOutput.of("result:" + input.context().getValue("paygate"));
        };
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: paygate, agent: approval }
                  - { id: pay, agent: pay }
                edges:
                  - { from: paygate, to: pay }
                """;
        String wfId = "u5-approve";
        String approvalId = runToPaused(yaml, Map.of("approval", new ApprovalGateAgent(), "pay", pay), wfId);
        assertThat(payCalls.get()).isZero(); // 暂停时下游未跑

        WorkflowContext result = approveAndResume(yaml, Map.of("approval", new ApprovalGateAgent(), "pay", pay),
                wfId, approvalId, ApprovalDecision.APPROVE);

        assertThat(cp.findStatus(wfId)).contains(WorkflowStatus.SUCCESS);
        // 待批节点重跑注入决策 → ApprovalGateAgent 恢复跑返回真实输出（写 channel=nodeId）
        assertThat(result.getValue("paygate")).isEqualTo("审批已通过（decision=APPROVE）");
        // 下游续跑且只跑一次
        assertThat(payCalls.get()).isEqualTo(1);
        assertThat(result.getValue("pay")).isEqualTo("result:审批已通过（decision=APPROVE）");
        assertThat(cp.findApprovalById(approvalId)).get()
                .extracting(r -> r.status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    // ────────────────────── 场景 2：REJECT ──────────────────────

    @Test
    @DisplayName("REJECT：工作流 FAILED，下游不跑，审批 REJECTED")
    void rejectFailsWorkflowAndSkipsDownstream() throws Exception {
        AtomicInteger payCalls = new AtomicInteger();
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: paygate, agent: approval }
                  - { id: pay, agent: pay }
                edges:
                  - { from: paygate, to: pay }
                """;
        Map<String, AgentFunction> agents = Map.of("approval", new ApprovalGateAgent(), "pay", input -> {
            payCalls.incrementAndGet();
            return AgentOutput.of("ran");
        });
        String wfId = "u5-reject";
        String approvalId = runToPaused(yaml, agents, wfId);

        approveAndResume(yaml, agents, wfId, approvalId, ApprovalDecision.REJECT);

        assertThat(cp.findStatus(wfId)).contains(WorkflowStatus.FAILED);
        assertThat(payCalls.get()).isZero();
        assertThat(cp.findApprovalById(approvalId)).get()
                .extracting(r -> r.status()).isEqualTo(ApprovalStatus.REJECTED);
    }

    // ────────────────────── 场景 3：多级审批链 ──────────────────────

    @Test
    @DisplayName("待批节点重跑仍抛 ApprovalRequired → 再次 AWAITING_APPROVAL（多级审批链）")
    void reApprovalKeepsPausing() throws Exception {
        AgentFunction alwaysApproval = input -> {
            throw new ApprovalRequiredException(input.nodeId(), "always need approval");
        };
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: gate, agent: always }
                  - { id: finalize, agent: echo }
                edges:
                  - { from: gate, to: finalize }
                """;
        Map<String, AgentFunction> agents = Map.of("always", alwaysApproval, "echo", echo());
        String wfId = "u5-reapprove";
        String approvalId = runToPaused(yaml, agents, wfId);

        approveAndResume(yaml, agents, wfId, approvalId, ApprovalDecision.APPROVE);

        // 再次暂停：新审批单 PENDING，工作流仍在 AWAITING_APPROVAL
        assertThat(cp.findStatus(wfId)).contains(WorkflowStatus.AWAITING_APPROVAL);
        assertThat(cp.findPendingApprovals(wfId)).hasSize(1);
        // 原审批单已被 APPROVED
        assertThat(cp.findApprovalById(approvalId)).get()
                .extracting(r -> r.status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    // ────────────────────── 场景 4：上下文恢复 ──────────────────────

    @Test
    @DisplayName("审批点之前的 barrier 输出在恢复后的新 context 可见")
    void priorBarrierOutputVisibleAfterResume() throws Exception {
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: prep, agent: echo, mock_response: "prepped" }
                  - { id: paygate, agent: approval }
                  - { id: pay, agent: pay }
                edges:
                  - { from: prep, to: paygate }
                  - { from: paygate, to: pay }
                """;
        Map<String, AgentFunction> agents = Map.of(
                "echo", echo(),
                "approval", new ApprovalGateAgent(),
                "pay", input -> AgentOutput.of("paid:" + input.context().getValue("prep")));
        String wfId = "u5-ctx";
        String approvalId = runToPaused(yaml, agents, wfId);

        WorkflowContext result = approveAndResume(yaml, agents, wfId, approvalId, ApprovalDecision.APPROVE);

        // 审批前层（prep）输出在新 context 可见
        assertThat(result.getValue("prep")).isEqualTo("prepped");
        assertThat(result.getValue("pay")).isEqualTo("paid:prepped");
        assertThat(cp.findStatus(wfId)).contains(WorkflowStatus.SUCCESS);
    }

    // ────────────────────── 场景 5：兄弟输出保留 ──────────────────────

    @Test
    @DisplayName("审批层含兄弟 Success → 恢复后兄弟不重跑、其输出仍在新 context")
    void siblingOutputPreservedWithoutRerun() throws Exception {
        AtomicInteger prepCalls = new AtomicInteger();
        AgentFunction prep = input -> {
            prepCalls.incrementAndGet();
            return AgentOutput.of("sibling");
        };
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: prep, agent: prep }
                  - { id: paygate, agent: approval }
                  - { id: pay, agent: pay }
                edges:
                  - { from: prep, to: pay }
                  - { from: paygate, to: pay }
                """;
        Map<String, AgentFunction> agents = Map.of(
                "prep", prep,
                "approval", new ApprovalGateAgent(),
                "pay", input -> AgentOutput.of("pay:" + input.context().getValue("prep")));
        String wfId = "u5-sibling";
        String approvalId = runToPaused(yaml, agents, wfId);
        assertThat(prepCalls.get()).isEqualTo(1); // 暂停前兄弟执行一次

        approveAndResume(yaml, agents, wfId, approvalId, ApprovalDecision.APPROVE);

        // 恢复后兄弟不重跑（输出已含于快照）
        assertThat(prepCalls.get()).isEqualTo(1);
        assertThat(cp.findStatus(wfId)).contains(WorkflowStatus.SUCCESS);
    }

    // ────────────────────── 场景 6：takenEdges 预置 ──────────────────────

    @Test
    @DisplayName("审批前已有 when 路由决策 → 恢复后预置不覆盖丢边，下游仍可达（review P2）")
    void takenEdgesPreSetPreservesRouting() throws Exception {
        AgentFunction route = input -> AgentOutput.of("yes");
        AtomicInteger doneCalls = new AtomicInteger();
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: pre, agent: route }
                  - { id: gate, agent: approval }
                  - { id: done, agent: payoff }
                edges:
                  - { from: pre, to: gate, when: "output.content == 'yes'" }
                  - { from: gate, to: done }
                """;
        Map<String, AgentFunction> agents = Map.of(
                "route", route,
                "approval", new ApprovalGateAgent(),
                "payoff", input -> {
                    doneCalls.incrementAndGet();
                    return AgentOutput.of("done");
                });
        String wfId = "u5-routing";
        String approvalId = runToPaused(yaml, agents, wfId);

        approveAndResume(yaml, agents, wfId, approvalId, ApprovalDecision.APPROVE);

        // done 可达：预置的 pre->gate 边（审批前 when 决策）+ 恢复时补记的 gate->done
        assertThat(cp.findStatus(wfId)).contains(WorkflowStatus.SUCCESS);
        assertThat(doneCalls.get()).isEqualTo(1);
    }

    // ────────────────────── 场景 7：无审批记录 ──────────────────────

    @Test
    @DisplayName("无审批记录 → 明确报错（IllegalStateException），不静默 no-op")
    void missingApprovalThrows() throws Exception {
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: gate, agent: approval }
                """;
        String wfId = "u5-missing";
        cp.initWorkflow(wfId, "missing", "1.0", "test");

        assertThatThrownBy(() -> approveAndResume(yaml, Map.of("approval", new ApprovalGateAgent()),
                wfId, "no-such-approval", ApprovalDecision.APPROVE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("审批单不存在");
    }

    // ────────────────────── 场景 8：幂等 no-op ──────────────────────

    @Test
    @DisplayName("已 APPROVED 的审批单再次 approve → 幂等 no-op，状态不变、下游不重跑")
    void secondApproveIsNoop() throws Exception {
        AtomicInteger payCalls = new AtomicInteger();
        String yaml = """
                agentflow:
                  version: "1.0"
                nodes:
                  - { id: paygate, agent: approval }
                  - { id: pay, agent: pay }
                edges:
                  - { from: paygate, to: pay }
                """;
        Map<String, AgentFunction> agents = Map.of("approval", new ApprovalGateAgent(), "pay", input -> {
            payCalls.incrementAndGet();
            return AgentOutput.of("ran");
        });
        String wfId = "u5-idempotent";
        String approvalId = runToPaused(yaml, agents, wfId);
        approveAndResume(yaml, agents, wfId, approvalId, ApprovalDecision.APPROVE);
        assertThat(cp.findStatus(wfId)).contains(WorkflowStatus.SUCCESS);
        int ran = payCalls.get();
        assertThat(ran).isEqualTo(1);

        // 再次 approve 已 APPROVED → no-op：状态不变、下游不重跑
        approveAndResume(yaml, agents, wfId, approvalId, ApprovalDecision.APPROVE);
        assertThat(cp.findStatus(wfId)).contains(WorkflowStatus.SUCCESS);
        assertThat(payCalls.get()).isEqualTo(ran);
    }
}
