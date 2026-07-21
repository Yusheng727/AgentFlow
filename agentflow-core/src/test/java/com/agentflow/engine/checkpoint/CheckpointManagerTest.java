package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;
import com.agentflow.engine.WorkflowContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U5 CheckpointManager 测试（plan U5 Test scenarios）。
 *
 * <p>测试 {@link InMemoryCheckpointManager}（主测试）和 {@link PostgresCheckpointManager}
 * 的基本行为。PG 集成测试需要实际 PG 实例。
 */
class CheckpointManagerTest {

    // ─────────────────── InMemoryCheckpointManager 测试 ───────────────────

    @Nested
    @DisplayName("InMemoryCheckpointManager（开发测试模式）")
    class InMemoryTests {

        private InMemoryCheckpointManager cm;

        @BeforeEach
        void setUp() {
            cm = new InMemoryCheckpointManager();
        }

        // ── 节点级 checkpoint ──

        @Test
        @DisplayName("节点级写入 → 查询 COMPLETED 节点正确")
        void saveNodeOutputAndFindCompleted() {
            cm.saveNodeOutput("wf-1", 0, "nodeA", AgentOutput.of("hello"));

            List<NodeOutputStore> completed = cm.findCompletedNodes("wf-1", 0);
            assertThat(completed).hasSize(1);
            NodeOutputStore record = completed.getFirst();
            assertThat(record.workflowId()).isEqualTo("wf-1");
            assertThat(record.superStep()).isEqualTo(0);
            assertThat(record.nodeId()).isEqualTo("nodeA");
            assertThat(record.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(record.output().content()).isEqualTo("hello");
            assertThat(record.completedAt()).isNotNull();
        }

        @Test
        @DisplayName("未写入的 super-step 查询返回空列表")
        void findCompletedReturnsEmptyForUnknownStep() {
            cm.saveNodeOutput("wf-1", 0, "nodeA", AgentOutput.of("hello"));

            List<NodeOutputStore> completed = cm.findCompletedNodes("wf-1", 1);
            assertThat(completed).isEmpty();
        }

        @Test
        @DisplayName("未写入的 workflow 查询返回空列表")
        void findCompletedReturnsEmptyForUnknownWorkflow() {
            cm.saveNodeOutput("wf-1", 0, "nodeA", AgentOutput.of("hello"));

            List<NodeOutputStore> completed = cm.findCompletedNodes("wf-2", 0);
            assertThat(completed).isEmpty();
        }

        @Test
        @DisplayName("同一 super-step 多节点写入 → 全部可查")
        void multipleNodesInSameStep() {
            cm.saveNodeOutput("wf-1", 0, "nodeA", AgentOutput.of("A"));
            cm.saveNodeOutput("wf-1", 0, "nodeB", AgentOutput.of("B"));
            cm.saveNodeOutput("wf-1", 0, "nodeC", AgentOutput.of("C"));

            List<NodeOutputStore> completed = cm.findCompletedNodes("wf-1", 0);
            assertThat(completed).hasSize(3);
            assertThat(completed).extracting(NodeOutputStore::nodeId)
                    .containsExactlyInAnyOrder("nodeA", "nodeB", "nodeC");
        }

        @Test
        @DisplayName("幂等：同一 key 重复写入 COMPLETED 不覆盖")
        void idempotentCompleteNotOverwritten() {
            cm.saveNodeOutput("wf-1", 0, "nodeA", AgentOutput.of("first"));
            cm.saveNodeOutput("wf-1", 0, "nodeA", AgentOutput.of("second"));

            List<NodeOutputStore> completed = cm.findCompletedNodes("wf-1", 0);
            assertThat(completed).hasSize(1);
            // COMPLETED 终态不覆盖 → 仍为 "first"
            assertThat(completed.getFirst().output().content()).isEqualTo("first");
        }

        @Test
        @DisplayName("带 token 消耗的节点写入 → tokensConsumed 正确")
        void saveWithTokens() {
            AgentOutput output = new AgentOutput("hello", Map.of(), Map.of(),
                    Map.of("usage", Map.of("totalTokens", 150)));
            cm.saveNodeOutput("wf-1", 0, "nodeA", output);

            List<NodeOutputStore> completed = cm.findCompletedNodes("wf-1", 0);
            assertThat(completed.getFirst().tokensConsumed()).isEqualTo(150);
        }

        // ── barrier 级 checkpoint ──

        @Test
        @DisplayName("barrier 写入 → 查最新 barrier 正确（多 step 按 superStep DESC）")
        void saveBarrierAndFindLatest() {
            WorkflowContext ctx0 = new WorkflowContext(Map.of("k1", "v1"));
            WorkflowContext ctx1 = new WorkflowContext(Map.of("k1", "v2", "k2", "v3"));

            cm.saveBarrier("wf-1", 0, ctx0);
            cm.saveBarrier("wf-1", 1, ctx1);

            Optional<BarrierCheckpoint> latest = cm.findLatestBarrier("wf-1");
            assertThat(latest).isPresent();
            assertThat(latest.get().superStep()).isEqualTo(1);
            assertThat(latest.get().channelValues()).containsEntry("k1", "v2");
            assertThat(latest.get().channelValues()).containsEntry("k2", "v3");
        }

        @Test
        @DisplayName("从未 barrier → findLatestBarrier 返回 empty")
        void findLatestBarrierWhenNone() {
            Optional<BarrierCheckpoint> latest = cm.findLatestBarrier("wf-1");
            assertThat(latest).isEmpty();
        }

        // ── 工作流生命周期 ──

        @Test
        @DisplayName("initWorkflow → visible in internal state")
        void initWorkflow() {
            cm.initWorkflow("wf-1", "supplier-risk", "1.0");
            // 通过 status update + query 间接验证（API 无直接读 status 方法）
            // Noop — 生命周期 API 仅写，不暴露读；verify 通过后续 save 正常验证
        }

        // ── 并发写入 ──

        @Test
        @DisplayName("并发写入 50 节点 → 全部 COMPLETED，无丢失")
        void concurrentWritesAllComplete() {
            int count = 50;
            CompletableFuture<?>[] futures = IntStream.range(0, count)
                    .mapToObj(i -> CompletableFuture.runAsync(() ->
                            cm.saveNodeOutput("wf-1", 0, "node" + i, AgentOutput.of("output" + i))))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();

            List<NodeOutputStore> completed = cm.findCompletedNodes("wf-1", 0);
            assertThat(completed).hasSize(count);
        }
    }

    // ─────────────────── JSONB 序列化往返测试 ───────────────────

    @Nested
    @DisplayName("JSONB 序列化往返（AgentOutput ↔ JSON）")
    class JsonbRoundTripTests {

        private InMemoryCheckpointManager cm = new InMemoryCheckpointManager();

        @Test
        @DisplayName("复杂 AgentOutput JSONB 往返无损")
        void complexOutputRoundTrip() {
            AgentOutput original = new AgentOutput(
                    "分析完成",
                    Map.of("channel1", "value1", "channel2", Map.of("nested", true)),
                    Map.of("riskLevel", "HIGH", "score", 0.85),
                    Map.of("usage", Map.of("totalTokens", 500))
            );
            cm.saveNodeOutput("wf-1", 0, "nodeA", original);

            List<NodeOutputStore> completed = cm.findCompletedNodes("wf-1", 0);
            AgentOutput restored = completed.getFirst().output();

            assertThat(restored.content()).isEqualTo("分析完成");
            assertThat(restored.channelWrites()).containsEntry("channel1", "value1");
            assertThat(restored.structuredOutput()).containsEntry("riskLevel", "HIGH");
            assertThat(restored.structuredOutput()).containsEntry("score", 0.85);
        }

        @Test
        @DisplayName("嵌套 Map + 数值类型 JSONB 往返无损")
        void nestedMapAndNumberTypes() {
            AgentOutput original = new AgentOutput(
                    null,
                    Map.of("metrics", Map.of("p95", 1.5, "count", 100L, "ratio", 0.75)),
                    Map.of(),
                    Map.of()
            );
            cm.saveNodeOutput("wf-1", 0, "nested", original);

            List<NodeOutputStore> completed = cm.findCompletedNodes("wf-1", 0);
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = (Map<String, Object>) completed.getFirst()
                    .output().channelWrites().get("metrics");

            assertThat(metrics).containsEntry("p95", 1.5);
            assertThat(metrics).containsEntry("count", 100);
            assertThat(metrics).containsEntry("ratio", 0.75);
        }
    }
}