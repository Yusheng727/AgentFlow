package com.agentflow.adapters.springai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 结构化日志 Advisor（U3-4，KTD-可观测 / plan U6 StructuredLogger）。
 *
 * <p>每次 LLM 调用输出一行结构化日志（{@code before} 记 prompt，{@code after} 记 response + tokens），
 * 供 U6 DiagnosisService 分析 5 类问题（连续超时 / token 异常 / SpEL 失败 / channel 缺失 / 死循环）。
 *
 * <p><b>脱敏 seam（U14 PromptRedactionFilter）</b>：构造时注入 {@code redactor}（默认 identity），
 * prompt / response 文本经脱敏后再入日志——避免 API Key / 手机号 / 身份证落日志。
 * U14 的 PromptRedactionFilter 将作为 redactor 注入此处。
 *
 * <p><b>与 ExecutionTrace 的关系（OQ-3 决议）</b>：本 advisor 只写结构化日志，
 * <b>不</b>写 ExecutionTrace 的 NodeTrace——适配器拥有 NodeTrace（持有 nodeId + chatResponse，
 * 避免并行 VT 共享可变状态）。结构化日志是持久化的执行轨迹（DiagnosisService 读），
 * ExecutionTrace 是内存树（U7 TraceController 读），两者分离。
 */
public class LoggingAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LoggingAdvisor.class);

    private final Function<String, String> redactor;
    private final Consumer<String> logSink;

    /** 默认：SLF4J info 级别输出，不脱敏。 */
    public LoggingAdvisor() {
        this(Function.identity(), log::info);
    }

    /**
     * @param redactor prompt/response 脱敏函数（U14 PromptRedactionFilter 注入）
     * @param logSink  日志输出 sink（测试可注入 list collector；生产用 SLF4J）
     */
    public LoggingAdvisor(Function<String, String> redactor, Consumer<String> logSink) {
        this.redactor = redactor == null ? Function.identity() : redactor;
        this.logSink = logSink == null ? log::info : logSink;
    }

    @Override
    public String getName() {
        return "LoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (request == null) {
            return null;
        }
        String prompt = redact(safePromptText(request));
        logSink.accept("agentflow.agent.call.start prompt=" + prompt);
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (response == null) {
            return null;
        }
        String content = "";
        long promptTokens = 0;
        long completionTokens = 0;
        String model = "unknown";
        if (response.chatResponse() != null) {
            if (response.chatResponse().getResult() != null
                    && response.chatResponse().getResult().getOutput() != null) {
                content = safeText(response.chatResponse().getResult().getOutput().getText());
            }
            if (response.chatResponse().getMetadata() != null) {
                UsageTokens tokens = UsageTokens.from(response.chatResponse());
                promptTokens = tokens.promptTokens();
                completionTokens = tokens.completionTokens();
                if (response.chatResponse().getMetadata().getModel() != null) {
                    model = response.chatResponse().getMetadata().getModel();
                }
            }
        }
        logSink.accept("agentflow.agent.call.end model=" + model
                + " promptTokens=" + promptTokens
                + " completionTokens=" + completionTokens
                + " response=" + redact(content));
        return response;
    }

    private String safePromptText(ChatClientRequest request) {
        try {
            if (request.prompt() != null && !request.prompt().getInstructions().isEmpty()) {
                return safeText(request.prompt().getInstructions().get(0).getText());
            }
        } catch (RuntimeException ignored) {
            // 防御：取 prompt 文本失败不阻塞链
        }
        return "";
    }

    private String safeText(String s) {
        return s == null ? "" : s;
    }

    private String redact(String s) {
        return s == null ? null : redactor.apply(s);
    }
}
