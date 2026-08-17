package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.dsl.EdgeDefinition;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.engine.checkpoint.RecoveryProtocol;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U6 验证：恢复 round 维度——轮次转换检测 + 恢复后继续迭代，不重跑已完成轮次。
 */
class RecoveryLoopTest {

    private final BspEngine engine = new BspEngine();

    private static NodeDefinition node(String id, String agent) {
        return new NodeDefinition(id, agent, null, null, null, null, null, null);
    }

    private static EdgeDefinition edge(String from, String to) {
        return new EdgeDefinition(from, to);
    }

    private static EdgeDefinition loopEdge(String from, String to, String when, int maxIterations) {
        return new EdgeDefinition(from, to, when, true, maxIterations);
    }

    private static WorkflowDefinition reflectionLoop() {
        return new WorkflowDefinition(null, null,
                List.of(node("draft", "d"), node("critique", "c"), node("finalize", "f")),
                List.of(
                        edge("draft", "critique"),
                        loopEdge("critique", "draft", "output.score < 0.8", 3),
                        edge("critique", "finalize")));
    }

    @Test
    @DisplayName("AE6 第 2 轮崩溃（轮边界）→ 恢复从第 2 轮层 0 继续，第 1 轮节点不重跑")
    void recoveryResumesFromRoundBoundary() {
        WorkflowDefinition def = reflectionLoop();

        // 模拟：round 0 完成（draft + critique 执行，critique 回边命中），崩溃在 round 1 层 0 前。
        // round 0 的 barrier（层 0/1/2 均写入，含不可达的 finalize 层）+ 路由决策含回边。
        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();
        cp.initWorkflow("wf", "test", "1.0", null);
        cp.saveBarrier("wf", 0, 0, new WorkflowContext(Map.of("draft", "draft-v1")));
        cp.saveBarrier("wf", 0, 1, new WorkflowContext(Map.of("draft", "draft-v1", "critique", "score=0.5")));
        cp.saveBarrier("wf", 0, 2, new WorkflowContext(Map.of("draft", "draft-v1", "critique", "score=0.5")));
        cp.saveRoutingDecisions("wf", 0, 2, List.of("draft->critique", "critique->draft"));
        cp.updateStatus("wf", WorkflowStatus.RUNNING);

        // 恢复：critique 返回 score=0.9（退出），round 1 应执行 draft + critique + finalize。
        AtomicInteger draftCalls = new AtomicInteger();
        AtomicInteger finalizeCalls = new AtomicInteger();
        Map<String, AgentFunction> agents = Map.of(
                "d", input -> { draftCalls.incrementAndGet(); return AgentOutput.of("draft-v2"); },
                "c", input -> new AgentOutput("score=0.9", Map.of(), Map.of("score", 0.9), Map.of()),
                "f", input -> { finalizeCalls.incrementAndGet(); return AgentOutput.of("final"); });

        RecoveryProtocol recovery = new RecoveryProtocol(cp);
        engine.recoverAndExecute(recovery, def, agents::get, new ChannelReducer(), "wf");

        // 轮次转换：round 0 最后一层 barrier + 回边命中 → 进入 round 1 从层 0 续跑，不重跑 round 0。
        assertThat(draftCalls.get()).isEqualTo(1);
        assertThat(finalizeCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("恢复后继续迭代：round 1 恢复后 critique 回边 → round 2 收敛")
    void recoveryThenContinuesIterating() {
        WorkflowDefinition def = reflectionLoop();

        // 模拟：round 0 完成（回边命中），崩溃在 round 1 层 0 前。
        InMemoryCheckpointManager cp = new InMemoryCheckpointManager();
        cp.initWorkflow("wf", "test", "1.0", null);
        cp.saveBarrier("wf", 0, 0, new WorkflowContext(Map.of("draft", "v1")));
        cp.saveBarrier("wf", 0, 1, new WorkflowContext(Map.of("draft", "v1", "critique", "score=0.5")));
        cp.saveBarrier("wf", 0, 2, new WorkflowContext(Map.of("draft", "v1", "critique", "score=0.5")));
        cp.saveRoutingDecisions("wf", 0, 2, List.of("draft->critique", "critique->draft"));
        cp.updateStatus("wf", WorkflowStatus.RUNNING);

        // 恢复后 critique 第 1 次仍回边（score=0.5），第 2 次退出（score=0.9）→ round 1 + round 2 两轮。
        AtomicInteger draftCalls = new AtomicInteger();
        AtomicInteger critiqueCalls = new AtomicInteger();
        Map<String, AgentFunction> agents = Map.of(
                "d", input -> { draftCalls.incrementAndGet(); return AgentOutput.of("v2"); },
                "c", input -> {
                    int n = critiqueCalls.incrementAndGet();
                    double score = n == 1 ? 0.5 : 0.9;
                    return new AgentOutput("score=" + score, Map.of(), Map.of("score", score), Map.of());
                },
                "f", input -> AgentOutput.of("final"));

        RecoveryProtocol recovery = new RecoveryProtocol(cp);
        engine.recoverAndExecute(recovery, def, agents::get, new ChannelReducer(), "wf");

        // round 1（draft + critique 回边）+ round 2（draft + critique 退出 + finalize）
        assertThat(draftCalls.get()).isEqualTo(2);
        assertThat(critiqueCalls.get()).isEqualTo(2);
    }
}
