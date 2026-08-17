package com.agentflow.demo.loop;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentOutput;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.engine.WorkflowExecutionException;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;
import com.agentflow.observability.ExecutionTraceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v2 循环/回边端到端测试（plan U7：反思循环 + 迭代轮次 + 收敛/上限）。
 */
class ReflectionLoopDemoTest {

    private WorkflowDSLParser parser;
    private ExecutionTraceRegistry traceRegistry;

    @BeforeEach
    void setUp() {
        parser = new WorkflowDSLParser();
        traceRegistry = new ExecutionTraceRegistry();
    }

    @Test
    @DisplayName("AE1 反思循环端到端：draft 执行 2 次、finalize 1 次、trace 含迭代轮次")
    void reflectionLoopConverges() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        AtomicInteger draftCalls = new AtomicInteger();
        AtomicInteger critiqueCalls = new AtomicInteger();
        AtomicInteger finalizeCalls = new AtomicInteger();
        Map<String, AgentFunction> agents = Map.of(
                "drafter", input -> { draftCalls.incrementAndGet(); return AgentOutput.of("draft-" + draftCalls.get()); },
                // critic 逐轮提升：第 1 次 score=0.5（回边重跑）、第 2 次 score=0.9（退出定稿）
                "critic", input -> {
                    int n = critiqueCalls.incrementAndGet();
                    double score = n == 1 ? 0.5 : 0.9;
                    return new AgentOutput("score=" + score, Map.of(), Map.of("score", score), Map.of());
                },
                "finalizer", input -> { finalizeCalls.incrementAndGet(); return AgentOutput.of("final"); });

        BspEngine engine = new BspEngine(new DAGLayerer(), null, null, null, traceRegistry);
        WorkflowContext result = engine.execute(
                def, agents, Map.of(), new InMemoryCheckpointManager(), new ChannelReducer(), "demo-loop-1");

        assertThat(draftCalls.get()).isEqualTo(2);
        assertThat(critiqueCalls.get()).isEqualTo(2);
        assertThat(finalizeCalls.get()).isEqualTo(1);
        assertThat(result.getValue("finalize")).isEqualTo("final");
        // trace 记录迭代轮次：round 0 + round 1 → maxRound == 1
        assertThat(traceRegistry.get("demo-loop-1").maxRound()).isEqualTo(1);
    }

    @Test
    @DisplayName("迭代上限：score 恒低 → 达到 max_iterations 后 FAILED「迭代超限」")
    void iterationCapFails() throws Exception {
        WorkflowDefinition def = loadWorkflow();
        Map<String, AgentFunction> agents = Map.of(
                "drafter", input -> AgentOutput.of("draft"),
                "critic", input -> new AgentOutput("score=0.1", Map.of(), Map.of("score", 0.1), Map.of()),
                "finalizer", input -> AgentOutput.of("final"));

        BspEngine engine = new BspEngine(new DAGLayerer(), null, null, null, traceRegistry);
        assertThatThrownBy(() -> engine.execute(
                def, agents, Map.of(), new InMemoryCheckpointManager(), new ChannelReducer(), "demo-loop-2"))
                .isInstanceOf(WorkflowExecutionException.class);
    }

    // ─────────────────── 辅助 ───────────────────

    private WorkflowDefinition loadWorkflow() throws java.io.IOException {
        try (var yaml = new ClassPathResource("workflows/reflection-loop.yml").getInputStream()) {
            return parser.parse(yaml);
        }
    }
}
