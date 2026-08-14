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

        // DAG 无环（Kahn，含 on_error 隐式边）
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
}
