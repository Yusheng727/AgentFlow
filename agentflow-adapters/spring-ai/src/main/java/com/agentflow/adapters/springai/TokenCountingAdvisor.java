package com.agentflow.adapters.springai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;

/**
 * Token 计数 Advisor（U3-4，KTD-可观测）。
 *
 * <p>拦截每次 LLM 调用的 {@code after()} 钩子，从 {@link Usage} 取 prompt/completion tokens，
 * 累加到 Micrometer Counter {@code agentflow.tokens.consumed}（tag: model）。U7 接入真实
 * {@link MeterRegistry} + 加 agent tag；U3 用注入的 registry（测试用 {@link io.micrometer.core.instrument.simple.SimpleMeterRegistry}）。
 *
 * <p><b>与 AgentOutput.metadata 的关系（OQ-3 决议）</b>：本 advisor 只负责 Micrometer 指标，
 * <b>不</b>回写 AgentOutput.metadata——适配器在 .call() 后直接读 chatResponse().getUsage()
 * 写 metadata，避开 ThreadLocal / advisor-context 传值（VT 脆弱）。两者数据源同（Usage），
 * 互不依赖：指标落 Micrometer 给 Grafana，metadata 落 AgentOutput 给下游 Agent/trace。
 */
public class TokenCountingAdvisor implements BaseAdvisor {

    /** Counter 名：U7 Grafana dashboard 约定。 */
    public static final String COUNTER_NAME = "agentflow.tokens.consumed";

    private final MeterRegistry meterRegistry;
    private final String agentName;

    /**
     * @param meterRegistry Micrometer registry（可空——空时 after 仍跑但不记指标，便于无 actuator 环境跑测试）
     * @param agentName     agent 名（作为 tag；可空）
     */
    public TokenCountingAdvisor(MeterRegistry meterRegistry, String agentName) {
        this.meterRegistry = meterRegistry;
        this.agentName = agentName;
    }

    @Override
    public String getName() {
        return "TokenCountingAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (meterRegistry == null || response == null || response.chatResponse() == null
                || response.chatResponse().getMetadata() == null
                || response.chatResponse().getMetadata().getUsage() == null) {
            return response;
        }
        Usage usage = response.chatResponse().getMetadata().getUsage();
        // getTotalTokens() 在某些 DefaultUsage 构造路径下可能返回 null，故从 prompt+completion 安全累加
        Integer pt = usage.getPromptTokens();
        Integer ct = usage.getCompletionTokens();
        long total = (pt == null ? 0 : pt) + (ct == null ? 0 : ct);
        String model = response.chatResponse().getMetadata().getModel();
        // getModel() 在未设置时可能返回 null 或空串，统一归为 "unknown"
        String agentTag = (agentName == null || agentName.isBlank()) ? "unknown" : agentName;
        String modelTag = (model == null || model.isBlank()) ? "unknown" : model;
        Tags tags = Tags.of("agent", agentTag).and("model", modelTag);
        meterRegistry.counter(COUNTER_NAME, tags).increment(total);
        return response;
    }
}
