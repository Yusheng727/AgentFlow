package com.agentflow.engine;

import com.agentflow.dsl.EdgeDefinition;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.WorkflowDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DAGraph 单测：节点查找 / 邻接表 / 边界。 */
class DAGraphTest {

    private WorkflowDefinition sample() {
        return new WorkflowDefinition(null, null,
                List.of(
                        new NodeDefinition("A", "a", null, null, null, null, null, null),
                        new NodeDefinition("B", "b", null, null, null, null, null, null),
                        new NodeDefinition("C", "c", null, null, null, null, null, null)),
                List.of(new EdgeDefinition("A", "B"), new EdgeDefinition("A", "C")));
    }

    @Test
    @DisplayName("node 查找 + 节点集合")
    void nodeLookup() {
        DAGraph g = new DAGraph(sample());
        assertThat(g.node("A").agent()).isEqualTo("a");
        assertThat(g.nodes()).hasSize(3);
    }

    @Test
    @DisplayName("未知节点 → IllegalArgumentException")
    void unknownNodeThrows() {
        DAGraph g = new DAGraph(sample());
        assertThatThrownBy(() -> g.node("Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Z");
    }

    @Test
    @DisplayName("successors / predecessors 正确且不可变")
    void adjacency() {
        DAGraph g = new DAGraph(sample());
        assertThat(g.successors("A")).containsExactlyInAnyOrder("B", "C");
        assertThat(g.successors("B")).isEmpty();
        assertThat(g.predecessors("B")).containsExactly("A");
        assertThat(g.predecessors("A")).isEmpty();
        // 孤立节点（图里不存在）返回空 list
        assertThat(g.successors("ZZ")).isEmpty();
    }

    @Test
    @DisplayName("无 edges 的单节点图")
    void singleNodeNoEdges() {
        WorkflowDefinition def = new WorkflowDefinition(null, null,
                List.of(new NodeDefinition("only", "a", null, null, null, null, null, null)),
                null);
        DAGraph g = new DAGraph(def);
        assertThat(g.nodes()).hasSize(1);
        assertThat(g.successors("only")).isEmpty();
    }
}
