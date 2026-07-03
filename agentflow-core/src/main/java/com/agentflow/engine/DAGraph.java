package com.agentflow.engine;

import com.agentflow.dsl.EdgeDefinition;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.WorkflowDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DAG 数据结构：节点查找 + 前驱/后继邻接表。
 *
 * <p>从已校验的 {@link WorkflowDefinition} 构造（调用方保证已过 {@link com.agentflow.dsl.SemanticValidator}）。
 * 分层（super-step 划分）仍由 {@link com.agentflow.dsl.DAGLayerer} 负责；本类只提供图查询。
 */
public final class DAGraph {

    private final Map<String, NodeDefinition> nodes;
    private final Map<String, List<String>> successors;
    private final Map<String, List<String>> predecessors;

    public DAGraph(WorkflowDefinition def) {
        this.nodes = new HashMap<>();
        this.successors = new HashMap<>();
        this.predecessors = new HashMap<>();
        if (def.nodes() != null) {
            for (NodeDefinition n : def.nodes()) {
                nodes.put(n.id(), n);
                successors.put(n.id(), new ArrayList<>());
                predecessors.put(n.id(), new ArrayList<>());
            }
        }
        List<EdgeDefinition> edges = def.edges() == null ? List.of() : def.edges();
        for (EdgeDefinition e : edges) {
            successors.get(e.from()).add(e.to());
            predecessors.get(e.to()).add(e.from());
        }
    }

    public NodeDefinition node(String id) {
        NodeDefinition n = nodes.get(id);
        if (n == null) {
            throw new IllegalArgumentException("未知节点: " + id);
        }
        return n;
    }

    public Collection<NodeDefinition> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    /** 直接后继（不可变）。 */
    public List<String> successors(String id) {
        return Collections.unmodifiableList(successors.getOrDefault(id, List.of()));
    }

    /** 直接前驱（不可变）。 */
    public List<String> predecessors(String id) {
        return Collections.unmodifiableList(predecessors.getOrDefault(id, List.of()));
    }
}
