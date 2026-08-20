package com.agentflow.engine.checkpoint;

import com.agentflow.agent.AgentOutput;
import com.agentflow.engine.ChannelValue;
import com.agentflow.engine.WorkflowContext;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 内存 CheckpointManager（v1 默认，开发测试用，重启丢失）。
 *
 * <p>所有数据存储在 {@link ConcurrentHashMap} 中，线程安全但进程重启后全丢失。
 * 生产环境请用 {@link PostgresCheckpointManager}。
 *
 * <h3>数据结构</h3>
 * <ul>
 *   <li>{@code nodeOutputs} — key = "{workflowId}:{superStep}:{nodeId}"，value = NodeOutputStore</li>
 *   <li>{@code barriers} — per-workflow 有序 BarrierCheckpoint 列表</li>
 *   <li>{@code workflowStatuses} — workflowId → WorkflowStatus</li>
 *   <li>{@code workflowMeta} — workflowId → {name, version}</li>
 * </ul>
 *
 * <p>实现约束（对齐 plan U5 的 Recovery 需求）：
 * <ul>
 *   <li>{@link #saveNodeOutput} 写入 COMPLETED 状态（引擎仅在节点成功时调用）</li>
 *   <li>{@link #findCompletedNodes} 严格按 status=COMPLETED 过滤，output 非空二次保护</li>
 *   <li>{@link #findLatestBarrier} 按 superStep 降序取第一个</li>
 * </ul>
 */
public final class InMemoryCheckpointManager implements CheckpointManager {

    private final ConcurrentHashMap<String, NodeOutputStore> nodeOutputs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<BarrierCheckpoint>> barriers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorkflowStatus> workflowStatuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String[]> workflowMeta = new ConcurrentHashMap<>(); // [name, version]
    private final ConcurrentHashMap<String, String> workflowCreatedBy = new ConcurrentHashMap<>(); // U14 所有权校验
    private final ConcurrentHashMap<String, Instant> workflowCreatedAt = new ConcurrentHashMap<>(); // U10 后续 #12 列表排序（createdAt 展示）
    private final ConcurrentHashMap<String, Long> workflowCreatedSeq = new ConcurrentHashMap<>();   // 单调插入序号（防同 created_at 时钟碰撞 flaky）
    private final AtomicLong seq = new AtomicLong();
    private final ConcurrentHashMap<String, List<String>> routingDecisions = new ConcurrentHashMap<>(); // v2 路由决策（累计）
    private final ConcurrentHashMap<String, ApprovalRequest> approvals = new ConcurrentHashMap<>();      // U1 HITL：approvalId → ApprovalRequest
    private final ConcurrentHashMap<String, List<String>> workflowApprovals = new ConcurrentHashMap<>(); // U1 HITL：workflowId → approvalId 列表（按序）

    // ──────────────────────────── 写入 ────────────────────────────

    @Override
    public void saveNodeOutput(String workflowId, int superStep, String nodeId, AgentOutput output) {
        saveNodeOutput(workflowId, 0, superStep, nodeId, output);
    }

    @Override
    public void saveNodeOutput(String workflowId, int round, int superStep, String nodeId, AgentOutput output) {
        // 从 AgentOutput.metadata 提取 token 消耗（若存在）
        Integer tokens = extractTokens(output);
        NodeOutputStore record = NodeOutputStore.completed(
                workflowId, round, superStep, nodeId, output, tokens, Instant.now());
        String key = nodeKey(workflowId, round, superStep, nodeId);
        // 幂等：COMPLETED 终态不可覆盖（plan v4.2 修正：避免 retry 成功后 DO NOTHING 丢弃）
        nodeOutputs.merge(key, record, (old, incoming) ->
                old.status() == NodeStatus.COMPLETED ? old : incoming);
    }

    @Override
    public void saveBarrier(String workflowId, int superStep, WorkflowContext context) {
        saveBarrier(workflowId, 0, superStep, context);
    }

    @Override
    public void saveBarrier(String workflowId, int round, int superStep, WorkflowContext context) {
        // 从 WorkflowContext 提取 channel 原始值（ChannelValue → value）
        Map<String, Object> channelValues = context.values().entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().value()));
        BarrierCheckpoint cp = new BarrierCheckpoint(
                workflowId, round, superStep, Map.copyOf(channelValues), Instant.now());
        barriers.computeIfAbsent(workflowId, k -> new CopyOnWriteArrayList<>()).add(cp);
    }

    // ────────────────────────── 查询 ──────────────────────────

    @Override
    public Optional<BarrierCheckpoint> findLatestBarrier(String workflowId) {
        List<BarrierCheckpoint> list = barriers.get(workflowId);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return list.stream().max(Comparator
                .comparingInt(BarrierCheckpoint::round)
                .thenComparingInt(BarrierCheckpoint::superStep));
    }

    @Override
    public List<NodeOutputStore> findCompletedNodes(String workflowId, int superStep) {
        return findCompletedNodes(workflowId, 0, superStep);
    }

    @Override
    public List<NodeOutputStore> findCompletedNodes(String workflowId, int round, int superStep) {
        return nodeOutputs.values().stream()
                .filter(n -> n.workflowId().equals(workflowId))
                .filter(n -> n.round() == round)
                .filter(n -> n.superStep() == superStep)
                .filter(n -> n.status() == NodeStatus.COMPLETED)
                .filter(n -> n.output() != null) // 双重保护：output 非空
                .collect(Collectors.toList());
    }

    // ─────────────────── 工作流生命周期 ───────────────────

    @Override
    public void initWorkflow(String workflowId, String workflowName, String version, String createdBy) {
        workflowMeta.put(workflowId, new String[]{workflowName, version});
        workflowStatuses.put(workflowId, WorkflowStatus.PENDING);
        workflowCreatedAt.put(workflowId, Instant.now()); // U10 后续 #12 列表排序（就近创建优先）
        workflowCreatedSeq.put(workflowId, seq.incrementAndGet());
        if (createdBy != null) {
            workflowCreatedBy.put(workflowId, createdBy);
        }
    }

    @Override
    public List<WorkflowExecutionRecord> listByCreatedBy(String createdBy) {
        // 按创建时间倒序（最近优先），供看板「最近执行」；createdBy 为空则返回全部（兼容 U5 未设 createdBy）
        return workflowCreatedAt.entrySet().stream()
                .filter(e -> createdBy == null || createdBy.equals(workflowCreatedBy.get(e.getKey())))
                // 主键：单调插入序号倒序（后提交在前，不一定依赖 created_at 时钟精度）
                .sorted(Comparator.<Map.Entry<String, Instant>>comparingLong(
                        e -> workflowCreatedSeq.getOrDefault(e.getKey(), 0L)).reversed()) // 单调插入序号倒序，后提交在前，不依赖 created_at 时钟精度
                .map(e -> {
                    String[] meta = workflowMeta.get(e.getKey());
                    WorkflowStatus status = workflowStatuses.getOrDefault(e.getKey(), WorkflowStatus.PENDING);
                    return new WorkflowExecutionRecord(e.getKey(),
                            meta != null && meta[0] != null ? meta[0] : e.getKey(),
                            status, e.getValue());
                })
                .collect(Collectors.toList());
    }

    @Override
    public void updateStatus(String workflowId, WorkflowStatus status) {
        workflowStatuses.put(workflowId, status);
    }

    @Override
    public Optional<WorkflowStatus> findStatus(String workflowId) {
        return Optional.ofNullable(workflowStatuses.get(workflowId));
    }

    @Override
    public boolean tryClaim(String workflowId) {
        AtomicBoolean claimed = new AtomicBoolean(false);
        // ConcurrentHashMap.compute：per-key 原子，PENDING→RUNNING 只发生一次（并发重复投递去重）
        workflowStatuses.compute(workflowId, (id, old) -> {
            if (old == WorkflowStatus.PENDING) {
                claimed.set(true);
                return WorkflowStatus.RUNNING;
            }
            return old; // null（未 staged）/ RUNNING / 终态 → 不 claim
        });
        return claimed.get();
    }

    @Override
    public Optional<String> findCreatedBy(String workflowId) {
        return Optional.ofNullable(workflowCreatedBy.get(workflowId));
    }

    @Override
    public Optional<String> findWorkflowName(String workflowId) {
        String[] meta = workflowMeta.get(workflowId);
        return meta == null ? Optional.empty() : Optional.ofNullable(meta[0]);
    }

    @Override
    public Optional<String> findVersion(String workflowId) {
        String[] meta = workflowMeta.get(workflowId);
        return meta == null ? Optional.empty() : Optional.ofNullable(meta[1]);
    }

    @Override
    public void saveRoutingDecisions(String workflowId, int superStep, List<String> decisions) {
        saveRoutingDecisions(workflowId, 0, superStep, decisions);
    }

    @Override
    public void saveRoutingDecisions(String workflowId, int round, int superStep, List<String> decisions) {
        routingDecisions.put(routingKey(workflowId, round), List.copyOf(decisions));
    }

    @Override
    public List<String> findRoutingDecisions(String workflowId) {
        return findRoutingDecisions(workflowId, 0);
    }

    @Override
    public List<String> findRoutingDecisions(String workflowId, int round) {
        return routingDecisions.getOrDefault(routingKey(workflowId, round), List.of());
    }

    // ──────────────────── HITL 审批（U1） ────────────────────

    @Override
    public String saveApprovalRequest(String workflowId, ApprovalRequest request) {
        approvals.put(request.approvalId(), request);
        workflowApprovals.computeIfAbsent(workflowId, k -> new CopyOnWriteArrayList<>()).add(request.approvalId());
        return request.approvalId();
    }

    @Override
    public List<ApprovalRequest> findPendingApprovals(String workflowId) {
        List<String> ids = workflowApprovals.getOrDefault(workflowId, List.of());
        return ids.stream()
                .map(approvals::get)
                .filter(r -> r != null && r.status() == ApprovalStatus.PENDING)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<ApprovalRequest> findApprovalById(String approvalId) {
        return Optional.ofNullable(approvals.get(approvalId));
    }

    @Override
    public boolean confirmApproval(String approvalId, ApprovalDecision decision, String decidedBy) {
        AtomicBoolean moved = new AtomicBoolean(false);
        // compute：per-key 原子，PENDING→APPROVED/REJECTED 只发生一次（并发审批去重）
        approvals.compute(approvalId, (id, req) -> {
            if (req == null || req.status() != ApprovalStatus.PENDING) {
                return req; // 不存在 / 已决策 → 不转移
            }
            moved.set(true);
            ApprovalStatus st = decision == ApprovalDecision.APPROVE
                    ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
            return new ApprovalRequest(req.approvalId(), req.workflowId(), req.nodeId(), req.round(),
                    req.superStep(), req.description(), req.requestPayload(), req.contextSnapshot(),
                    st, decidedBy, req.createdAt());
        });
        return moved.get();
    }

    // ──────────────────────── 辅助方法 ────────────────────────

    private static String nodeKey(String workflowId, int round, int superStep, String nodeId) {
        return workflowId + ":" + round + ":" + superStep + ":" + nodeId;
    }

    private static String routingKey(String workflowId, int round) {
        return workflowId + ":" + round;
    }

    private static Integer extractTokens(AgentOutput output) {
        if (output == null || output.metadata() == null) {
            return null;
        }
        Object usage = output.metadata().get("usage");
        if (usage instanceof Map<?, ?> usageMap) {
            Object total = usageMap.get("totalTokens");
            if (total instanceof Number n) {
                return n.intValue();
            }
        }
        return null;
    }
}