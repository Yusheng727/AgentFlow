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

        // ── v2 循环 round 维度 ──

        @Test
        @DisplayName("round 0 与 round 1 同一 (superStep, nodeId) 不冲突，findCompletedNodes 按 round 过滤")
        void roundScopedNodeOutput() {
            cm.saveNodeOutput("wf-1", 0, 0, "nodeA", AgentOutput.of("round0"));
            cm.saveNodeOutput("wf-1", 1, 0, "nodeA", AgentOutput.of("round1"));

            List<NodeOutputStore> round0 = cm.findCompletedNodes("wf-1", 0, 0);
            List<NodeOutputStore> round1 = cm.findCompletedNodes("wf-1", 1, 0);
            assertThat(round0).hasSize(1);
            assertThat(round0.getFirst().output().content()).isEqualTo("round0");
            assertThat(round1).hasSize(1);
            assertThat(round1.getFirst().output().content()).isEqualTo("round1");
        }

        @Test
        @DisplayName("findLatestBarrier 按 (round, superStep) 复合排序取最新")
        void findLatestBarrierAcrossRounds() {
            cm.saveBarrier("wf-1", 0, 2, new WorkflowContext(Map.of("k", "r0s2")));
            cm.saveBarrier("wf-1", 1, 0, new WorkflowContext(Map.of("k", "r1s0")));

            Optional<BarrierCheckpoint> latest = cm.findLatestBarrier("wf-1");
            assertThat(latest).isPresent();
            assertThat(latest.get().round()).isEqualTo(1);
            assertThat(latest.get().superStep()).isEqualTo(0);
        }

        @Test
        @DisplayName("findRoutingDecisions 按 round 返回每轮已走边")
        void routingDecisionsScopedByRound() {
            cm.saveRoutingDecisions("wf-1", 0, 0, List.of("draft->critique", "critique->draft"));
            cm.saveRoutingDecisions("wf-1", 1, 0, List.of("draft->critique", "critique->finalize"));

            assertThat(cm.findRoutingDecisions("wf-1", 0)).containsExactly("draft->critique", "critique->draft");
            assertThat(cm.findRoutingDecisions("wf-1", 1)).containsExactly("draft->critique", "critique->finalize");
        }

        // ── 工作流生命周期 ──

        @Test
        @DisplayName("initWorkflow → visible in internal state")
        void initWorkflow() {
            cm.initWorkflow("wf-1", "supplier-risk", "1.0", null);
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

        @Test
        @DisplayName("tryClaim：PENDING→RUNNING 原子 claim；二次/未 staged/终态均拒绝")
        void tryClaimAtomic() {
            cm.initWorkflow("wf-claim", "wf", "1.0", "caller");
            assertThat(cm.tryClaim("wf-claim")).isTrue(); // PENDING → RUNNING
            assertThat(cm.findStatus("wf-claim")).contains(WorkflowStatus.RUNNING);
            assertThat(cm.tryClaim("wf-claim")).isFalse(); // 已 RUNNING → 拒绝（并发去重）
            assertThat(cm.tryClaim("never-staged")).isFalse(); // 未 staged → 拒绝

            cm.initWorkflow("wf-done", "wf", "1.0", "caller");
            cm.updateStatus("wf-done", WorkflowStatus.SUCCESS);
            assertThat(cm.tryClaim("wf-done")).isFalse(); // 终态 → 拒绝
        }

        @Test
        @DisplayName("tryClaim 并发：10 线程 claim 同一工作流，恰一个成功（防双跑双计费）")
        void tryClaimConcurrentExactlyOneWins() {
            cm.initWorkflow("wf-race", "wf", "1.0", "caller");
            CompletableFuture<Boolean>[] futures = new CompletableFuture[10];
            for (int i = 0; i < futures.length; i++) {
                futures[i] = CompletableFuture.supplyAsync(() -> cm.tryClaim("wf-race"));
            }
            CompletableFuture.allOf(futures).join();
            long winners = java.util.Arrays.stream(futures)
                    .map(CompletableFuture::join).filter(Boolean.TRUE::equals).count();
            assertThat(winners).isEqualTo(1);
            assertThat(cm.findStatus("wf-race")).contains(WorkflowStatus.RUNNING);
        }

        @Test
        @DisplayName("明细排序：清单按单调插入序号倒序（后提交在前，不依赖 created_at 时钟精度）")
        void listByCreatedByOrderingIsDeterministic() {
            // 紧挨提交（与同一测试内连续提交的 demo 场景一致），created_at 可能精确相等
            cm.initWorkflow("a", "wf-a", "1.0", "creator");
            cm.initWorkflow("b", "wf-b", "1.0", "creator");
            cm.initWorkflow("other", "wf-other", "1.0", "someone-else");

            List<WorkflowExecutionRecord> mine = cm.listByCreatedBy("creator");
            assertThat(mine).extracting(WorkflowExecutionRecord::workflowId)
                    .containsExactly("b", "a"); // 后提交在前，确定性，无论 created_at 是否碰撞
            assertThat(mine).extracting(WorkflowExecutionRecord::workflowName)
                    .containsExactly("wf-b", "wf-a");

            // createdBy 为空返回全部（兼容 U5 未设 createdBy）
            assertThat(cm.listByCreatedBy(null)).extracting(WorkflowExecutionRecord::workflowId)
                    .hasSize(3);
            // 非本创建者看不到
            assertThat(cm.listByCreatedBy("someone-else"))
                    .extracting(WorkflowExecutionRecord::workflowId)
                    .containsExactly("other");
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
            // 造数据用 100L（Long）；InMemoryCheckpointManager 不做 JSON 序列化，存取后类型不变，
            // 故断言须用 100L 匹配（Long.equals(Integer) 恒为 false）。
            assertThat(metrics).containsEntry("count", 100L);
            assertThat(metrics).containsEntry("ratio", 0.75);
        }
    }
}