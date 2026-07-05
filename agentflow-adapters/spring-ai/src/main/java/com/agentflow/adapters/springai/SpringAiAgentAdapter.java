package com.agentflow.adapters.springai;

import com.agentflow.agent.AgentExecutionException;
import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.agent.TransientException;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.NodeTrace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.expression.spel.SpelEvaluationException;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Spring AI 2.0 {@link AgentFunction} 实现（KTD-6 / KTD-7）。
 *
 * <p>所有 Spring AI 调用收敛在本适配器窄表面，2.0→2.1 迁移只碰本类（KTD-7 可移植性）。
 *
 * <p>执行流：
 * <ol>
 *   <li>SpEL 解析 {@code promptTemplate} 的 {@code ${...}} 占位符（{@link SpelPromptResolver}，
 *       SimpleEvaluationContext 禁反射，KTD-2 安全）</li>
 *   <li>{@code chatClient.prompt(resolved).tools(...).call()} 调用 LLM</li>
 *   <li>从 {@link ChatResponse} 取 content + usage，构造 {@link AgentOutput}
 *       （metadata 写 token 统计，不挤占 channelWrites/structuredOutput）</li>
 *   <li>异常按 cause 映射 Transient/Fatal（U4 ErrorClassifier 据此重试）</li>
 * </ol>
 *
 * <p><b>open question #3 决议（metadata 回传路径）</b>：适配器在 {@code .call()} 后直接读
 * {@code chatResponse().getMetadata().getUsage()} 写入 {@code AgentOutput.metadata}——
 * 不经 ThreadLocal / advisor-context 传值（VT 下 ThreadLocal 脆弱）。
 * {@code LoggingAdvisor}（U3-4）只做结构化 SLF4J 日志，{@code TokenCountingAdvisor} 只接 Micrometer，
 * ExecutionTrace 的 NodeTrace 由本适配器拥有（它持有 nodeId + chatResponse，避免并行 VT 共享可变状态）。
 *
 * <p><b>异常映射（v1）</b>：Spring AI 2.0 已移除 1.0 的 {@code TransientAiException}/
 * {@code NonTransientAiException} 标记类，故按 cause 粗分类——SpEL 违例 → Fatal；
 * IOException/TimeoutException/网络/RestClient → Transient；其余 → Fatal（保守不重试）。
 * U4 的 ErrorClassifier 将做细分类（KTD-4 精神）。
 *
 * <p><b>工具注册（v1 简化）</b>：构造时注入的 {@code toolBeans}（@Tool bean）全部注册到每次调用。
 * YAML 节点级 {@code input.tools()} 的运行时过滤延后到 U14 CallerToolAllowlist（授权层），
 * 本适配器不做运行时按名过滤。
 *
 * <p><b>cancel() / OutputSchemaValidator</b>：cancel 真中止 HTTP 见 U3-6（本骨架为 default noop），
 * schema 校验见 U3-5（本骨架不校验，structuredOutput 留空）。
 */
public class SpringAiAgentAdapter implements AgentFunction {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAgentAdapter.class);

    private final ChatClient chatClient;
    private final List<Advisor> advisors;
    private final List<Object> toolBeans;
    private final SpelPromptResolver promptResolver = new SpelPromptResolver();
    private final Function<String, String> redactor;
    private final ExecutionTrace trace;
    private final OutputSchemaValidator schemaValidator;

    /** 便捷构造：无 advisor、无工具、无 trace、不脱敏、无 schema 校验（测试/最小用法）。 */
    public SpringAiAgentAdapter(ChatClient chatClient) {
        this(chatClient, List.of(), List.of(), null, Function.identity(), null);
    }

    /**
     * @param chatClient      已配置好的 ChatClient
     * @param advisors        每次 call 注入的 Advisor（U3-4 注入 TokenCounting/Logging；
     *                        Spring AI 2.0 零 advisor 时 .call() 抛 "No CallAdvisors"，故至少需 1 个）
     * @param toolBeans       @Tool 注解 bean 列表，每次调用注册（可空）
     * @param trace           执行追踪（可空；非空则每节点追加 NodeTrace，供 U6/U7 读）
     * @param redactor        trace 摘要脱敏函数（U14 PromptRedactionFilter 注入；默认 identity）
     * @param schemaValidator LLM 输出 schema 校验器（可空；非空且节点有 output_schema 时启用）
     */
    public SpringAiAgentAdapter(ChatClient chatClient,
                                List<Advisor> advisors,
                                List<Object> toolBeans,
                                ExecutionTrace trace,
                                Function<String, String> redactor,
                                OutputSchemaValidator schemaValidator) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.advisors = advisors == null ? List.of() : List.copyOf(advisors);
        this.toolBeans = toolBeans == null ? List.of() : List.copyOf(toolBeans);
        this.trace = trace;
        this.redactor = redactor == null ? Function.identity() : redactor;
        this.schemaValidator = schemaValidator;
    }

    @Override
    public AgentOutput execute(AgentInput input) throws AgentExecutionException {
        NodeTrace nodeTrace = new NodeTrace(input.nodeId(), input.agentName());
        if (trace != null) {
            trace.addNode(nodeTrace);
        }

        // 1. SpEL 解析 prompt 模板
        Map<String, Object> channelValues = flattenChannels(input.context());
        String resolvedPrompt;
        try {
            resolvedPrompt = promptResolver.resolve(input.promptTemplate(), channelValues, input.inputs());
        } catch (SpelEvaluationException e) {
            nodeTrace.fail("SpEL 解析失败: " + e.getMessage());
            throw new FatalException("Prompt SpEL 解析失败 [" + input.nodeId() + "]: " + e.getMessage(), e);
        }

        // 2. 调用 LLM（advisors 每次 call 注入：U3-4 的 TokenCounting/Logging 在此入链）
        // 若节点声明了 output_schema 且配置了 schemaValidator → 带反馈重试校验（U3-5）
        String content;
        Map<String, Object> structuredOutput = Map.of();
        long promptTokens;
        long completionTokens;
        try {
            if (schemaValidator != null && !input.outputSchema().isEmpty()) {
                // schema 校验 + 重试：validator 内部多次 callLlm，用 AtomicReference 捕获最后一次 usage
                java.util.concurrent.atomic.AtomicReference<LlmResult> last = new java.util.concurrent.atomic.AtomicReference<>();
                OutputSchemaValidator.ValidatedOutput vo = schemaValidator.validateWithRetry(
                        resolvedPrompt, input.outputSchema(), p -> {
                            LlmResult r = callLlm(p);
                            last.set(r);
                            return r.content();
                        });
                content = vo.content();
                structuredOutput = vo.structuredOutput();
                LlmResult lastResult = last.get();
                promptTokens = lastResult == null ? 0 : lastResult.promptTokens();
                completionTokens = lastResult == null ? 0 : lastResult.completionTokens();
            } else {
                LlmResult r = callLlm(resolvedPrompt);
                content = r.content();
                promptTokens = r.promptTokens();
                completionTokens = r.completionTokens();
            }
        } catch (RuntimeException e) {
            Throwable cause = (e.getCause() instanceof Exception) ? e.getCause() : e;
            nodeTrace.fail(cause.getMessage());
            throw mapException(cause);
        }

        // 3. 构造 AgentOutput（content + metadata{tokens} + structuredOutput）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("promptTokens", promptTokens);
        metadata.put("completionTokens", completionTokens);
        metadata.put("totalTokens", promptTokens + completionTokens);

        nodeTrace.succeed(redact(content), promptTokens, completionTokens);
        log.debug("agent executed: node={} agent={} tokens={}", input.nodeId(), input.agentName(),
                promptTokens + completionTokens);
        return new AgentOutput(content, Map.of(), structuredOutput, Map.copyOf(metadata));
    }

    /** 单次 LLM 调用结果（content + usage，避免共享可变字段，VT 安全）。 */
    private record LlmResult(String content, long promptTokens, long completionTokens) {
    }

    /**
     * 单次 LLM 调用（advisors + tools 注入）。返回 content + usage。
     *
     * <p>Spring AI 2.0：.chatResponse() / .content() 各自触发一次 advisor 链执行，
     * callAdvisors deque 是 mutable（nextCall pop），故只调一次 .chatResponse()。
     */
    private LlmResult callLlm(String prompt) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt(prompt);
        if (!advisors.isEmpty()) {
            spec = spec.advisors(advisors.toArray(new Advisor[0]));
        }
        if (!toolBeans.isEmpty()) {
            spec = spec.tools(toolBeans.toArray());
        }
        ChatResponse chatResponse = spec.call().chatResponse();
        String content = null;
        long promptTokens = 0;
        long completionTokens = 0;
        if (chatResponse != null) {
            if (chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null) {
                content = chatResponse.getResult().getOutput().getText();
            }
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                Usage usage = chatResponse.getMetadata().getUsage();
                promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            }
        }
        return new LlmResult(content, promptTokens, completionTokens);
    }

    /** 把 WorkflowContext 的 ChannelValue 扁平成 channel 名 → 值，供 SpEL 根对象 context 视图。 */
    private Map<String, Object> flattenChannels(WorkflowContext context) {
        Map<String, Object> flat = new HashMap<>();
        if (context == null) {
            return flat;
        }
        context.values().forEach((k, cv) -> flat.put(k, cv.value()));
        return flat;
    }

    private AgentExecutionException mapException(Throwable cause) {
        if (cause instanceof SpelEvaluationException) {
            return new FatalException("SpEL 解析失败: " + cause.getMessage(), cause);
        }
        if (cause instanceof IOException || cause instanceof TimeoutException
                || cause instanceof InterruptedException) {
            return new TransientException("Transient LLM 调用失败: " + cause.getMessage(), cause);
        }
        String cn = cause.getClass().getName();
        if (cn.startsWith("java.net.") || cn.startsWith("org.springframework.web.client.")) {
            return new TransientException("Transient 网络错误: " + cause.getMessage(), cause);
        }
        return new FatalException("LLM 调用失败: " + cause.getMessage(), cause);
    }

    private String redact(String text) {
        return text == null ? null : redactor.apply(text);
    }
}
