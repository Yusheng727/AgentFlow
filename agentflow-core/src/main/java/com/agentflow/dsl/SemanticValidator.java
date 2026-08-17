package com.agentflow.dsl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义层 + DAG 完整性校验（第二、三层）。
 *
 * <ul>
 *   <li>nodes 非空、id 唯一、id 非空</li>
 *   <li>edges from/to 引用已声明节点</li>
 *   <li>无自环、无重复边</li>
 *   <li>DAG 无环（Kahn 拓扑排序，处理节点数 &lt; 总数 → 有环）</li>
 *   <li>channels reducer 合法（非 null）</li>
 *   <li>version 缺失 → 默认 "1.0"（由 {@link WorkflowDefinition#version()} 兜底，此处不阻断）</li>
 * </ul>
 *
 * <p>注：孤立节点（无任何边引用）在 v1 允许——单节点工作流是合法的；多节点孤立
 * 不阻断但语义可疑，留待 Diagnosis 端点（U6）提示。
 */
public class SemanticValidator {

    public void validate(WorkflowDefinition def) {
        if (def == null) {
            throw new WorkflowValidationException("workflow definition 为空");
        }
        if (def.nodes() == null || def.nodes().isEmpty()) {
            throw new WorkflowValidationException("nodes 段缺失或为空");
        }

        // 节点 id 唯一且非空
        Set<String> ids = new HashSet<>();
        for (NodeDefinition node : def.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                throw new WorkflowValidationException("node 缺少 id 字段");
            }
            if (!ids.add(node.id())) {
                throw new WorkflowValidationException("重复 node id: " + node.id());
            }
        }

        List<EdgeDefinition> edges = def.edges() == null ? List.of() : def.edges();
        // 边引用存在 + 无自环 + 无重复
        Set<String> edgeKeys = new HashSet<>();
        for (EdgeDefinition e : edges) {
            if (e.from() == null || e.to() == null) {
                throw new WorkflowValidationException("edge 缺少 from/to 字段");
            }
            if (e.from().equals(e.to())) {
                throw new WorkflowValidationException("自环边禁止: " + e.from() + " → " + e.to());
            }
            if (!ids.contains(e.from())) {
                throw new WorkflowValidationException("edge from 引用不存在节点: " + e.from());
            }
            if (!ids.contains(e.to())) {
                throw new WorkflowValidationException("edge to 引用不存在节点: " + e.to());
            }
            String key = EdgeDefinition.edgeKey(e.from(), e.to());
            if (!edgeKeys.add(key)) {
                throw new WorkflowValidationException("重复 edge: " + e.from() + " → " + e.to());
            }
        }

        // v2 条件边 + on_error 校验
        Map<String, Integer> whenEdgeCount = new HashMap<>();
        Map<String, Integer> defaultEdgeCount = new HashMap<>();
        for (EdgeDefinition e : edges) {
            if (e.when() != null && !e.when().isBlank()) {
                whenEdgeCount.merge(e.from(), 1, Integer::sum);
            } else {
                defaultEdgeCount.merge(e.from(), 1, Integer::sum);
            }
        }
        for (NodeDefinition node : def.nodes()) {
            // on_error 目标必须存在
            if (node.onError() != null && !node.onError().isBlank() && !ids.contains(node.onError())) {
                throw new WorkflowValidationException("on_error 目标不存在: " + node.id() + " → " + node.onError());
            }
            // 路由节点（有 when 边）最多一条默认边（无 when）；纯 fan-out 节点（无 when 边）不受限
            if (whenEdgeCount.getOrDefault(node.id(), 0) > 0
                    && defaultEdgeCount.getOrDefault(node.id(), 0) > 1) {
                throw new WorkflowValidationException("路由节点多条默认边（无 when）: " + node.id());
            }
        }

        // v2 循环回边校验：回边（loop=true）必须带 when + 正数 max_iterations；非回边不得带 max_iterations
        for (EdgeDefinition e : edges) {
            if (e.loop()) {
                if (e.when() == null || e.when().isBlank()) {
                    throw new WorkflowValidationException(
                            "无条件回边: " + e.from() + " → " + e.to() + "（回边必须带 when 退出条件）");
                }
                if (e.maxIterations() == null || e.maxIterations() <= 0) {
                    throw new WorkflowValidationException(
                            "无上限环: " + e.from() + " → " + e.to() + "（回边必须声明正数 max_iterations）");
                }
            } else if (e.maxIterations() != null) {
                throw new WorkflowValidationException(
                        "非回边声明 max_iterations: " + e.from() + " → " + e.to());
            }
        }
        // 回边方向校验：回边目标须为源节点的静态图祖先（形成环），否则是前向 loop 边（误标，被分层豁免后静默错层）
        validateBackedgeDirection(ids, edges);
        // 喂回 channel 校验：回边端点节点 id 对应的 channel 不得声明非 OVERWRITE reducer（跨轮累积污染喂回值）
        validateFeedBackChannel(def, edges);

        // DAG 无环（Kahn，含 on_error 隐式边，回边豁免）
        checkAcyclic(def.nodes().size(), ids, def.allEdges());

        // channels reducer 合法
        if (def.channels() != null) {
            for (Map.Entry<String, ChannelDefinition> entry : def.channels().entrySet()) {
                if (entry.getValue() == null) {
                    throw new WorkflowValidationException("channel 声明为空: " + entry.getKey());
                }
                if (entry.getValue().reducer() == null) {
                    throw new WorkflowValidationException("channel " + entry.getKey() + " 缺少 reducer 字段");
                }
            }
        }

        // per-workflow 预算（R10）：budget_tokens/budget_cost 可为空，但不能为负/非有限
        AgentflowMeta meta = def.agentflow();
        if (meta != null) {
            if (meta.budgetTokens() != null && meta.budgetTokens() < 0) {
                throw new WorkflowValidationException("budget_tokens 不能为负: " + meta.budgetTokens());
            }
            if (meta.budgetCost() != null
                    && (meta.budgetCost() < 0 || !Double.isFinite(meta.budgetCost()))) {
                throw new WorkflowValidationException("budget_cost 必须是非负有限数: " + meta.budgetCost());
            }
        }
    }

    private void checkAcyclic(int totalNodes, Set<String> ids, List<EdgeDefinition> edges) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        for (String id : ids) {
            inDegree.put(id, 0);
            successors.put(id, new ArrayList<>());
        }
        for (EdgeDefinition e : edges) {
            if (e.loop()) {
                continue; // 回边豁免环检测（静态图去回边须无环，回边成环由 validateBackedgeDirection 单独校验）
            }
            successors.get(e.from()).add(e.to());
            inDegree.merge(e.to(), 1, Integer::sum);
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> en : inDegree.entrySet()) {
            if (en.getValue() == 0) {
                queue.add(en.getKey());
            }
        }
        int processed = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            processed++;
            for (String succ : successors.get(cur)) {
                int remain = inDegree.merge(succ, -1, Integer::sum);
                if (remain == 0) {
                    queue.add(succ);
                }
            }
        }
        if (processed != totalNodes) {
            throw new WorkflowValidationException(
                    "DAG 检测到环路（已处理 " + processed + "/" + totalNodes + " 节点）");
        }
    }

    /**
     * v2 回边方向校验：回边（loop）目标须为源节点的静态图（去回边）祖先——即存在 to→from 的静态路径，
     * 回边因此形成环。若 to 无法在静态图到达 from，则该边是「前向 loop 边」（误标），会被分层豁免导致
     * 目标被误推迟一轮，属静默错层，须拒绝。
     */
    private void validateBackedgeDirection(Set<String> ids, List<EdgeDefinition> edges) {
        Map<String, List<String>> successors = new HashMap<>();
        for (String id : ids) {
            successors.put(id, new ArrayList<>());
        }
        for (EdgeDefinition e : edges) {
            if (!e.loop()) {
                successors.get(e.from()).add(e.to());
            }
        }
        for (EdgeDefinition e : edges) {
            if (e.loop() && !reachable(e.to(), e.from(), successors)) {
                throw new WorkflowValidationException(
                        "前向 loop 边（目标非源节点祖先，未形成环）: " + e.from() + " → " + e.to());
            }
        }
    }

    /** 静态图（去回边）可达性：from 能否经有向边到达 target。 */
    private boolean reachable(String from, String target, Map<String, List<String>> successors) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (cur.equals(target)) {
                return true;
            }
            if (!visited.add(cur)) {
                continue;
            }
            queue.addAll(successors.getOrDefault(cur, List.of()));
        }
        return false;
    }

    /** v2 喂回 channel 校验：回边端点节点 id 对应的 channel 声明非 OVERWRITE reducer 时拒绝（跨轮累积污染喂回值）。 */
    private void validateFeedBackChannel(WorkflowDefinition def, List<EdgeDefinition> edges) {
        if (def.channels() == null) {
            return;
        }
        Set<String> loopEndpoints = new HashSet<>();
        for (EdgeDefinition e : edges) {
            if (e.loop()) {
                loopEndpoints.add(e.from());
                loopEndpoints.add(e.to());
            }
        }
        for (String id : loopEndpoints) {
            ChannelDefinition cd = def.channels().get(id);
            if (cd != null && cd.reducer() != Reducer.OVERWRITE) {
                throw new WorkflowValidationException(
                        "回边节点 channel 声明非 OVERWRITE reducer: " + id + "（循环喂回需 OVERWRITE 每轮覆盖）");
            }
        }
    }
}
