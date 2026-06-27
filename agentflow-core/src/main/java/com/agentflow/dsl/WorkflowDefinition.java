package com.agentflow.dsl;

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
}
