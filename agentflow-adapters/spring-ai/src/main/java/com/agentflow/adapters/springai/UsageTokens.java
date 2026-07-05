package com.agentflow.adapters.springai;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;

/**
 * 从 {@link ChatResponse} 安全提取 token 用量（KTD-7 窄表面收敛点）。
 *
 * <p>Spring AI 2.0 的 {@link Usage#getPromptTokens()}/{@code getCompletionTokens()} 返回
 * {@link Integer}（可能为 null，如流式或部分 provider），直接拆箱成 long 会 NPE。
 * 本 helper 统一做 null 防御，供 {@link SpringAiAgentAdapter}、{@link LoggingAdvisor}、
 * {@link TokenCountingAdvisor} 共用——Spring AI 2.0→2.1 若改 Usage API，只改本类一处。
 *
 * @param promptTokens     prompt token 数（null → 0）
 * @param completionTokens completion token 数（null → 0）
 */
public record UsageTokens(long promptTokens, long completionTokens) {

    /** 从 ChatResponse 安全提取；response/metadata/usage 任一为 null → (0,0)。 */
    public static UsageTokens from(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return new UsageTokens(0, 0);
        }
        Usage u = response.getMetadata().getUsage();
        long pt = u.getPromptTokens() == null ? 0 : u.getPromptTokens();
        long ct = u.getCompletionTokens() == null ? 0 : u.getCompletionTokens();
        return new UsageTokens(pt, ct);
    }

    /** 总 token 数。 */
    public long total() {
        return promptTokens + completionTokens;
    }
}
