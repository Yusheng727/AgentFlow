package com.agentflow.dsl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 最长路径分层（KTD-1 算法补充）。
 *
 * <p>{@code level[v] = max(level[u] for u in predecessors(v)) + 1}；
 * {@code superStep_k = { v | level[v] = k }}。入度为 0 的节点 level 0。
 * BFS + 剩余入度递减，节点入度归零时入队，保证所有前驱已确定 level（Kahn 变体）。
 *
 * <p>返回 0-based super-step 列表，每步是该层节点 id 列表。调用前需先经
 * {@link SemanticValidator#validate} 确认无环。
 */
public class DAGLayerer {

    public List<List<String>> computeSuperSteps(WorkflowDefinition def) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        for (String id : def.nodeIds()) {
            inDegree.put(id, 0);
            successors.put(id, new ArrayList<>());
        }
        List<EdgeDefinition> edges = def.edges() == null ? List.of() : def.edges();
        for (EdgeDefinition e : edges) {
            successors.get(e.from()).add(e.to());
            inDegree.merge(e.to(), 1, Integer::sum);
        }

        Map<String, Integer> level = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> en : inDegree.entrySet()) {
            if (en.getValue() == 0) {
                level.put(en.getKey(), 0);
                queue.add(en.getKey());
            }
        }
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String succ : successors.get(cur)) {
                level.merge(succ, level.get(cur) + 1, Math::max);
                int remain = inDegree.merge(succ, -1, Integer::sum);
                if (remain == 0) {
                    queue.add(succ);
                }
            }
        }

        int maxLevel = level.values().stream().max(Integer::compare).orElse(-1);
        List<List<String>> steps = new ArrayList<>();
        for (int i = 0; i <= maxLevel; i++) {
            steps.add(new ArrayList<>());
        }
        // 按 def.nodes() 声明顺序填入，保证可读性
        for (NodeDefinition node : def.nodes()) {
            steps.get(level.get(node.id())).add(node.id());
        }
        return steps;
    }
}
