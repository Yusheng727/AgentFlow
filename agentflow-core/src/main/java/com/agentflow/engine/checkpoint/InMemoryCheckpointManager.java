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

    // ──────────────────────────── 写入 ────────────────────────────

    @Override
    public void saveNodeOutput(String workflowId, int superStep, String nodeId, AgentOutput output) {
        // 从 AgentOutput.metadata 提取 token 消耗（若存在）
        Integer tokens = extractTokens(output);
        NodeOutputStore record = NodeOutputStore.completed(
                workflowId, superStep, nodeId, output, tokens, Instant.now());
        String key = nodeKey(workflowId, superStep, nodeId);
        // 幂等：COMPLETED 终态不可覆盖（plan v4.2 修正：避免 retry 成功后 DO NOTHING 丢弃）
        nodeOutputs.merge(key, record, (old, incoming) ->
                old.status() == NodeStatus.COMPLETED ? old : incoming);
    }

    @Override
    public void saveBarrier(String workflowId, int superStep, WorkflowContext context) {
        // 从 WorkflowContext 提取 channel 原始值（ChannelValue → value）
        Map<String, Object> channelValues = context.values().entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().value()));
        BarrierCheckpoint cp = new BarrierCheckpoint(
                workflowId, superStep, Map.copyOf(channelValues), Instant.now());
        barriers.computeIfAbsent(workflowId, k -> new CopyOnWriteArrayList<>()).add(cp);
    }

    // ────────────────────────── 查询 ──────────────────────────

    @Override
    public Optional<BarrierCheckpoint> findLatestBarrier(String workflowId) {
        List<BarrierCheckpoint> list = barriers.get(workflowId);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return list.stream().max(Comparator.comparingInt(BarrierCheckpoint::superStep));
    }

    @Override
    public List<NodeOutputStore> findCompletedNodes(String workflowId, int superStep) {
        return nodeOutputs.values().stream()
                .filter(n -> n.workflowId().equals(workflowId))
                .filter(n -> n.superStep() == superStep)
                .filter(n -> n.status() == NodeStatus.COMPLETED)
                .filter(n -> n.output() != null) // 双重保护：output 非空
                .collect(Collectors.toList());
    }

    // ─────────────────── 工作流生命周期 ───────────────────

    @Override
    public void initWorkflow(String workflowId, String workflowName, String version) {
        workflowMeta.put(workflowId, new String[]{workflowName, version});
        workflowStatuses.put(workflowId, WorkflowStatus.PENDING);
    }

    @Override
    public void updateStatus(String workflowId, WorkflowStatus status) {
        workflowStatuses.put(workflowId, status);
    }

    // ──────────────────────── 辅助方法 ────────────────────────

    private static String nodeKey(String workflowId, int superStep, String nodeId) {
        return workflowId + ":" + superStep + ":" + nodeId;
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