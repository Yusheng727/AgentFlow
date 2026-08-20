package com.agentflow.engine.checkpoint;

import com.agentflow.engine.checkpoint.ApprovalDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** U2 审批持久化 SPI + InMemory 实现。 */
class InMemoryCheckpointManagerApprovalTest {

    private final InMemoryCheckpointManager mgr = new InMemoryCheckpointManager();

    private ApprovalRequest pending(String flow, String node, int step) {
        return ApprovalRequest.pending(flow, node, 0, step, "desc", Map.of("k", "v"), Map.of("ctx", 1));
    }

    @Test
    @DisplayName("save → findPending 可见，status=PENDING，快照随行")
    void saveAndFindPending() {
        String id = mgr.saveApprovalRequest("wf1", pending("wf1", "gate", 1));
        List<ApprovalRequest> pendingList = mgr.findPendingApprovals("wf1");
        assertThat(pendingList).hasSize(1);
        ApprovalRequest got = mgr.findApprovalById(id).orElseThrow();
        assertThat(got.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(got.contextSnapshot()).containsEntry("ctx", 1);
        assertThat(got.requestPayload()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("confirm APPROVE → APPROVED；重复 confirm 幂等 false")
    void confirmApprove() {
        String id = mgr.saveApprovalRequest("wf1", pending("wf1", "n", 0));
        assertThat(mgr.confirmApproval(id, ApprovalDecision.APPROVE, "caller-1")).isTrue();
        ApprovalRequest after = mgr.findApprovalById(id).orElseThrow();
        assertThat(after.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(after.decidedBy()).isEqualTo("caller-1");
        assertThat(mgr.confirmApproval(id, ApprovalDecision.APPROVE, "caller-2")).isFalse();
    }

    @Test
    @DisplayName("confirm REJECT→REJECTED")
    void confirmReject() {
        String id = mgr.saveApprovalRequest("wf1", pending("wf1", "n", 0));
        assertThat(mgr.confirmApproval(id, ApprovalDecision.REJECT, "caller-1")).isTrue();
        assertThat(mgr.findApprovalById(id).orElseThrow().status()).isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    @DisplayName("已决策审批从 pending 列表消失")
    void decidedNotPending() {
        String id = mgr.saveApprovalRequest("wf1", pending("wf1", "n", 0));
        mgr.confirmApproval(id, ApprovalDecision.APPROVE, "caller");
        assertThat(mgr.findPendingApprovals("wf1")).isEmpty();
    }

    @Test
    @DisplayName("多工作流隔离")
    void workflowIsolation() {
        mgr.saveApprovalRequest("wfA", pending("wfA", "n", 0));
        mgr.saveApprovalRequest("wfB", pending("wfB", "m", 0));
        assertThat(mgr.findPendingApprovals("wfA")).hasSize(1);
        assertThat(mgr.findPendingApprovals("wfB")).hasSize(1);
        mgr.confirmApproval(mgr.findPendingApprovals("wfA").get(0).approvalId(), ApprovalDecision.APPROVE, "c");
        assertThat(mgr.findPendingApprovals("wfB")).hasSize(1);
    }

    @Test
    @DisplayName("未知 id → empty / confirm false")
    void unknownId() {
        assertThat(mgr.findApprovalById("nope")).isEmpty();
        assertThat(mgr.confirmApproval("nope", ApprovalDecision.APPROVE, "c")).isFalse();
    }

    @Test
    @DisplayName("并发 confirm 恰一成功")
    void concurrentConfirm() throws Exception {
        String id = mgr.saveApprovalRequest("wf1", pending("wf1", "n", 0));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger wins = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (mgr.confirmApproval(id, ApprovalDecision.APPROVE, "c" + idx)) {
                        wins.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(wins.get()).isEqualTo(1);
        assertThat(mgr.findApprovalById(id).orElseThrow().status()).isEqualTo(ApprovalStatus.APPROVED);
        pool.shutdownNow();
    }
}