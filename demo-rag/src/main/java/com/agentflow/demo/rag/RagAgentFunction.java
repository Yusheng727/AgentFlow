package com.agentflow.demo.rag;

import com.agentflow.agent.AgentExecutionException;
import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.demo.rag.InMemoryVectorStore.Result;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG Agent（U8）：「检索 → 增强 → 委托」全流程，验证 KTD-6「引擎层零改动、Agent 扩展点成立」。
 *
 * <p><b>流程</b>：
 * <ol>
 *   <li>读 {@code input.promptTemplate()} 为 query</li>
 *   <li>从 {@link InMemoryVectorStore} top-k 检索相关文档</li>
 *   <li>把命中文档拼进 <b>augmented prompt</b>（{@code [上下文] + 原文 query}）</li>
 *   <li>委托 wrapped {@link AgentFunction}（mock 或真实 LLM）处理增强后 prompt</li>
 *   <li>返回委托的 {@link AgentOutput}（channel 写入透传）</li>
 * </ol>
 *
 * <p>委托 wrapped 抛异常 → 原样透传 {@link AgentExecutionException}（error path，不吞）。
 * 非 Agent 技术栈：embeding 用确定性词袋（离线路），无外部服务依赖。
 */
public class RagAgentFunction implements AgentFunction {

    private final InMemoryVectorStore vectorStore;
    private final AgentFunction delegate;
    private final int topK;

    /**
     * @param vectorStore 内置内存向量库（含业务文档）
     * @param delegate    下游处理 agent（mock 或真实 LLM）
     * @param topK        检索条数（默认 3）
     */
    public RagAgentFunction(InMemoryVectorStore vectorStore, AgentFunction delegate, int topK) {
        this.vectorStore = vectorStore;
        this.delegate = delegate;
        this.topK = topK;
    }

    public RagAgentFunction(InMemoryVectorStore vectorStore, AgentFunction delegate) {
        this(vectorStore, delegate, 3);
    }

    @Override
    public AgentOutput execute(AgentInput input) throws AgentExecutionException {
        String originalQuery = input.promptTemplate() == null ? "" : input.promptTemplate();
        List<Result> hits = vectorStore.topK(originalQuery, topK);
        String augmentedPrompt = augment(originalQuery, hits);
        // 委托处理增强后 prompt（真实处理路径：用增强 prompt，弃原始 query）
        AgentInput delegateInput = new AgentInput(
                input.nodeId(), input.agentName(), augmentedPrompt,
                input.context(), input.inputs(), input.tools(), input.outputSchema(),
                input.mockResponse(), input.trace(), input.budget(), input.round(), input.approvalDecision());
        return delegate.execute(delegateInput);
    }

    // ──────────────────────────── 增强 prompt ────────────────────────────

    /** 把 top-k 命中文档拼进 prompt：[上下文] 段 + 原文 query。无命中 → 仅原文 query。 */
    private static String augment(String query, List<Result> hits) {
        if (hits.isEmpty()) {
            return query;
        }
        String ctx = hits.stream()
                .map(r -> "- " + r.doc().text())
                .collect(Collectors.joining("\n"));
        return "检索到的相关文档：\n" + ctx + "\n\n基于上述上下文（仅作参考）回答问题：\n" + query;
    }
}