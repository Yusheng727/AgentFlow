package com.agentflow.demo.rag;

import com.agentflow.agent.AgentExecutionException;
import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.engine.WorkflowContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U8：{@link RagAgentFunction} 测试——query → 检索 → 增强 prompt（含命中 doc）→ 委托结果透传；
 * 委托抛异常 → 原样透传（error path）。
 */
class RagAgentFunctionTest {

    private static final String DOC_TEXT = "串行 DAG 编排 引擎 BSP 驱动";

    private static AgentInput input(String query) {
        return AgentInput.of("rag-node", "rag", query, new WorkflowContext(), Map.of());
    }

    @Test
    @DisplayName("检索命中 → 增强 prompt 含命中 doc → 委托处理增强后 query 并透传输出")
    void retrievesAugmentsAndDelegates() throws Exception {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add(DOC_TEXT);
        // 捕获 delegate 收到的 prompt
        AtomicReference<String> received = new AtomicReference<>();
        AgentFunction delegate = in -> {
            received.set(in.promptTemplate());
            return AgentOutput.of("rag-answer");
        };
        RagAgentFunction rag = new RagAgentFunction(store, delegate);

        AgentOutput out = rag.execute(input("串行 编排"));

        // 委托输出透传
        assertThat(out.content()).isEqualTo("rag-answer");
        // 增强 prompt 含命中 doc + 原文 query
        assertThat(received.get()).contains(DOC_TEXT).contains("串行 编排");
    }

    @Test
    @DisplayName("无命中 → 增强 prompt 仅原文 query（无上下文段）")
    void noHitAugmentsOnlyQuery() throws Exception {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add(DOC_TEXT);
        AtomicReference<String> received = new AtomicReference<>();
        RagAgentFunction rag = new RagAgentFunction(store, in -> {
            received.set(in.promptTemplate());
            return AgentOutput.of("fallback");
        });

        rag.execute(input("完全不相关 词汇"));

        // query 无重叠 → 无上下文段，只有原文 query
        assertThat(received.get()).isEqualTo("完全不相关 词汇").doesNotContain("检索到的相关文档");
    }

    @Test
    @DisplayName("委托抛异常 → 原样透传 AgentExecutionException（不吞）")
    void delegateExceptionPropagates() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add(DOC_TEXT);
        AgentFunction boom = in -> {
            throw new AgentExecutionException("delegate boom");
        };
        RagAgentFunction rag = new RagAgentFunction(store, boom);

        assertThatThrownBy(() -> rag.execute(input("串行 编排")))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("delegate boom");
    }
}
