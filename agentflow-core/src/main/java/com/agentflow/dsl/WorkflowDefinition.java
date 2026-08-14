package com.agentflow.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 解析后的工作流定义（不可变）。承载 version / channels / nodes / edges。
 * 校验由 {@link SemanticValidator} 完成，分层由 {@link DAGLayerer} 完成。
 */
public record WorkflowDefinition(
        AgentflowMeta agentflow,
        Map<String, ChannelDefinition> channels,
        List<NodeDefinition> nodes,
        List<EdgeDefinition> edges
) {

    /** 所有节点 id 集合。 */
    public Set<String> nodeIds() {
        if (nodes() == null) {
            return Set.of();
        }
        return nodes().stream().map(NodeDefinition::id).collect(Collectors.toSet());
    }

    /** 版本号；agentflow.version 缺失时默认 "1.0"（R14）。 */
    public String version() {
        if (agentflow() != null && agentflow().version() != null && !agentflow().version().isBlank()) {
            return agentflow().version();
        }
        return "1.0";
    }

    /**
     * 所有边（普通边 + on_error 隐式边）。
     * on_error 目标作为 from=节点、to=目标的隐式边参与环校验与分层（KTD-4），
     * 使纯 cleanup 节点（无正常入边）被正确分层到其触发节点之后，而非 level-0 源。
     */
    public List<EdgeDefinition> allEdges() {
        List<EdgeDefinition> result = new ArrayList<>();
        if (edges() != null) {
            result.addAll(edges());
        }
        if (nodes() != null) {
            for (NodeDefinition n : nodes()) {
                if (n.onError() != null && !n.onError().isBlank()) {
                    result.add(new EdgeDefinition(n.id(), n.onError()));
                }
            }
        }
        return result;
    }
}
