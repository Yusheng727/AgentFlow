package com.agentflow.adapters.langchain4j;

import com.agentflow.agent.AgentExecutionException;
import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.agent.TransientException;
import com.agentflow.engine.fault.ErrorClassifier;
import com.agentflow.observability.AgentFlowMetrics;
import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.NodeTrace;
import com.agentflow.prompt.SpelPromptResolver;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.spel.SpelEvaluationException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * LangChain4j {@link AgentFunction} 实现（v1.1，R5 / KTD-7 可移植性验证）。
 *
 * <p>与 {@code SpringAiAgentAdapter} 对齐同一窄表面契约：所有 LangChain4j 调用收敛在本类，
 * 换框架只动本适配器（引擎/DSL/上游无感知）——这正是 KTD-7 可移植性约束的实证。
 *
 * <p>执行流：
 * <ol>
 *   <li>SpEL 解析 {@code promptTemplate} 的 {@code ${...}} 占位符（core 复用件
 *       {@link SpelPromptResolver}，KTD-2 安全，与 Spring 适配器同一条解析路径）</li>
 *   <li>{@code chatModel.chat(ChatRequest)} 调用 LLM；节点声明 tools 时反射
 *       {@code @Tool} 方法注册 {@link ToolSpecification}，模型返回 toolExecutionRequests
 *       时执行本地工具并把结果回填消息（有界循环 ≤ {@link #MAX_TOOL_ROUNDS} 轮防死循环，
 *       usage 跨轮累加计费）</li>
 *   <li>从 {@link ChatResponse#tokenUsage()} 取 usage，构造 {@link AgentOutput}
 *       （metadata 写 token 统计，不挤占 channelWrites/structuredOutput）</li>
 *   <li>异常经 core {@link ErrorClassifier} 映射 Transient/Fatal（U4 单一真相源，与 Spring 适配器一致）</li>
 * </ol>
 *
 * <p><b>NodeTrace 所有权</b>（OQ-3 同款决议）：trace 来源优先级——构造器注入（适配器级）
 * > {@code input.trace()}（workflow 级），两者皆空不写 trace。
 *
 * <p><b>cancel()</b>：LangChain4j {@code ChatModel.chat()} 同步阻塞、无公共 abort 钩子，
 * 与 Spring AI 2.0 同处境——best-effort 记 warn，实际中止由 NodeExecutor 的
 * {@code future.cancel(true)} 中断 VT（KTD-6 v4.3）。
 */
public class LangChain4jAgentAdapter implements AgentFunction {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jAgentAdapter.class);

    /** 工具执行循环上限：防模型反复发起 toolExecutionRequests 死循环。 */
    static final int MAX_TOOL_ROUNDS = 5;

    private final ChatModel chatModel;
    private final List<Object> toolBeans;
    private final ExecutionTrace trace;
    private final Function<String, String> redactor;
    /** v1.1 记账钩子（可空）：非空时按累计 usage 记 token/成本（Grafana 面板数据源，与 mock/Spring 记账对齐）。 */
    private final AgentFlowMetrics metrics;
    /** 成本查表模型名（可空 → CostCalculator 走 unknown/默认单价）。 */
    private final String model;
    private final SpelPromptResolver promptResolver = new SpelPromptResolver();

    /** 便捷构造：无工具、无 trace、不脱敏、不记账（测试/最小用法）。 */
    public LangChain4jAgentAdapter(ChatModel chatModel) {
        this(chatModel, List.of(), null, null, null, null);
    }

    /**
     * @param chatModel LangChain4j ChatModel（如 {@code OpenAiChatModel}）
     * @param toolBeans 含 {@code @Tool} 方法的 bean 列表（可空）；反射注册 spec + 执行器
     * @param trace     执行追踪（可空；优先级高于 input.trace()，与 Spring 适配器一致）
     * @param redactor  trace 摘要脱敏函数（可空；默认 identity）
     */
    public LangChain4jAgentAdapter(ChatModel chatModel,
                                   List<Object> toolBeans,
                                   ExecutionTrace trace,
                                   Function<String, String> redactor) {
        this(chatModel, toolBeans, trace, redactor, null, null);
    }

    /**
     * 完整构造：额外支持记账。
     *
     * @param metrics AgentFlowMetrics（可空；非空则按累计 usage 记 token/成本）
     * @param model   成本查表模型名（可空）
     */
    public LangChain4jAgentAdapter(ChatModel chatModel,
                                   List<Object> toolBeans,
                                   ExecutionTrace trace,
                                   Function<String, String> redactor,
                                   AgentFlowMetrics metrics,
                                   String model) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.toolBeans = toolBeans == null ? List.of() : List.copyOf(toolBeans);
        this.trace = trace;
        this.redactor = redactor == null ? Function.identity() : redactor;
        this.metrics = metrics;
        this.model = model;
    }

    @Override
    public AgentOutput execute(AgentInput input) throws AgentExecutionException {
        // trace 来源优先级：构造器注入（适配器级，OQ-3）> input.trace()（workflow 级，KTD-2）
        ExecutionTrace effectiveTrace = this.trace != null ? this.trace : input.trace();
        NodeTrace nodeTrace = new NodeTrace(input.nodeId(), input.agentName());
        if (effectiveTrace != null) {
            effectiveTrace.addNode(nodeTrace);
        }

        // 1. SpEL 解析 prompt 模板（core 复用件，与 Spring 适配器同路径）
        String resolvedPrompt;
        try {
            resolvedPrompt = promptResolver.resolve(input.promptTemplate(), input.context(), input.inputs());
        } catch (SpelEvaluationException e) {
            nodeTrace.fail("SpEL 解析失败: " + e.getMessage());
            throw new FatalException("Prompt SpEL 解析失败 [" + input.nodeId() + "]: " + e.getMessage(), e);
        }

        // 1.5 v1.1 对齐提示：本适配器不做结构化输出 schema 校验（structuredOutput 恒空），
        // 节点声明了 output_schema 时 warn 明示，避免与 Spring 适配器行为静默分歧
        if (!input.outputSchema().isEmpty()) {
            log.warn("LangChain4j 适配器 v1.1 不支持 output_schema 结构化输出校验（structuredOutput 将为空），节点={}",
                    input.nodeId());
        }

        // 2. LLM 调用（+ 工具执行循环）
        LlmResult result;
        try {
            result = chatWithTools(resolvedPrompt);
        } catch (FatalException e) {
            // 工具轮数耗尽等确定失败：NodeTrace 终态化后原样抛，避免被外层 RuntimeException 分支
            // 再包装成误导性的 "LLM 调用失败"（与 Spring 适配器 schema 路径处理对齐）
            nodeTrace.fail(e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            Throwable cause = (e.getCause() instanceof Exception) ? e.getCause() : e;
            nodeTrace.fail(cause.getMessage());
            throw mapException(cause);
        }

        // 2.5 v1.1 记账（Grafana token/成本可见性；metrics 为 null 则 no-op）。放在成功路径：截断失败已抛
        if (metrics != null) {
            metrics.recordTokens(input.agentName(), model, result.promptTokens(), result.completionTokens());
        }

        // 3. 构造 AgentOutput（content + metadata{tokens}，与 Spring 适配器同 schema）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("promptTokens", result.promptTokens());
        metadata.put("completionTokens", result.completionTokens());
        metadata.put("totalTokens", result.promptTokens() + result.completionTokens());

        nodeTrace.succeed(redact(result.content()), result.promptTokens(), result.completionTokens());
        log.debug("agent executed: node={} agent={} tokens={}", input.nodeId(), input.agentName(),
                result.promptTokens() + result.completionTokens());
        return new AgentOutput(result.content(), Map.of(), Map.of(), Map.copyOf(metadata));
    }

    /** 单次节点调用结果（content + 跨轮累加 usage，避免共享可变字段，VT 安全）。 */
    private record LlmResult(String content, long promptTokens, long completionTokens) {
    }

    /**
     * LLM 调用主循环：注册 tools → chat → 若模型发起 toolExecutionRequests 则本地执行并回填
     * → 再 chat，直到模型给纯文本或轮数达上限。
     *
     * <p>LangChain4j 裸 ChatModel 不像 Spring AI ChatClient 内置工具回调循环，需本适配器自行
     * 驱动（AiServices 是另一套接口抽象，为保持与 Spring 适配器同构的 ChatModel 窄表面而未采用）。
     * usage 跨轮累加（每轮都真实计费）。
     */
    private LlmResult chatWithTools(String prompt) throws FatalException {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(prompt));
        List<ToolSpecification> specs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();
        collectTools(specs, executors);

        long promptTokens = 0;
        long completionTokens = 0;
        for (int round = 0; ; round++) {
            // v1.1 review REL-1：NodeExecutor 取消节点（future.cancel(true) 中断 VT）后不再新开付费 LLM
            // 轮次——中断要传播进当前阻塞 chat() 才会停，晚开一轮就多一轮计费。取消场景下本结果已被丢弃，
            // 此处仅尽早停手防追加计费。
            if (Thread.currentThread().isInterrupted()) {
                log.warn("工具循环检测到线程中断，提前停止（已累计 tokens={}/{}）", promptTokens, completionTokens);
                return new LlmResult(null, promptTokens, completionTokens);
            }
            ChatRequest.Builder builder = ChatRequest.builder().messages(messages);
            if (!specs.isEmpty()) {
                builder.toolSpecifications(specs);
            }
            ChatResponse response = chatModel.chat(builder.build());
            TokenUsage usage = response == null ? null : response.tokenUsage();
            promptTokens += usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
            completionTokens += usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();

            AiMessage aiMessage = response == null ? null : response.aiMessage();
            boolean hasToolRequests = aiMessage != null && aiMessage.hasToolExecutionRequests();
            if (!hasToolRequests) {
                return new LlmResult(aiMessage == null ? null : aiMessage.text(), promptTokens, completionTokens);
            }
            if (round >= MAX_TOOL_ROUNDS - 1) {
                // v1.1 review ADV-1/correctness-1：轮数耗尽仍要工具 = 工具链未完成。绝不能当"成功"返回
                // （截断会静默丢弃已排期工具 + content=null 让下游 SpEL 崩溃）——以 FAILED 明示失败。
                log.error("工具执行轮数达上限 {} 仍有 {} 个 toolExecutionRequest 未执行，判定节点失败",
                        MAX_TOOL_ROUNDS, aiMessage.toolExecutionRequests().size());
                throw new FatalException("工具执行轮数达上限 " + MAX_TOOL_ROUNDS + "，工具链未完成");
            }
            messages.add(aiMessage);
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                messages.add(new ToolExecutionResultMessage(req.id(), req.name(), executeTool(executors, req)));
            }
        }
    }

    /** 执行单个工具请求。未知工具/异常都反馈给模型（不中断循环，LangChain4j AiServices 同款语义），
     *  但安全上：真实异常细节只进服务端日志，不给模型（防敏感内部信息经模型 content 外泄，SEC-1）。 */
    private static String executeTool(Map<String, ToolExecutor> executors, ToolExecutionRequest req) {
        ToolExecutor executor = executors.get(req.name());
        if (executor == null) {
            // 工具名是开发者配置（非模型自由输入高风险位），转义后再反馈保持可调试性
            return "{\"error\":\"tool not registered: " + jsonEscape(req.name()) + "\"}";
        }
        try {
            String result = executor.execute(req, null);
            // @Tool 返回 null（P3/correctness-2）：coerce 非 null 哨兵，避免 null-text 回放给 provider
            return result == null ? "null" : result;
        } catch (RuntimeException e) {
            log.warn("工具执行失败 tool={}: {}", req.name(), e.toString());
            return "{\"error\":\"tool execution failed\"}";
        }
    }

    /** 简单 JSON 字符串转义（工具名/异常消息含引号、换行时防止破坏反馈给模型的 JSON）。 */
    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /** 反射扫描 @Tool bean：方法 → ToolSpecification（注册给模型）+ DefaultToolExecutor（本地执行）。 */
    private void collectTools(List<ToolSpecification> specs, Map<String, ToolExecutor> executors) {
        for (Object bean : toolBeans) {
            for (Method m : bean.getClass().getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
                specs.add(spec);
                executors.put(spec.name(), new DefaultToolExecutor(bean, m));
            }
        }
    }

    /**
     * 中止在飞 LLM 调用（KTD-6 v4.3）：LangChain4j 同步阻塞无公共 abort 钩子，与 Spring 适配器同款
     * best-effort——实际中止由 NodeExecutor 的 {@code future.cancel(true)} 中断 VT（HTTP client
     * 响应中断则止 token 计费）。
     */
    @Override
    public void cancel(AgentInput input) {
        if (input == null) {
            return;
        }
        log.warn("cancel: LangChain4j ChatModel 同步阻塞无公共 HTTP abort 钩子；依赖 NodeExecutor "
                + "future.cancel(true) 中断 VT（best-effort）nodeId={} agent={}",
                input.nodeId(), input.agentName());
    }

    /** 异常映射：委托 core ErrorClassifier（U4 canonical，与 Spring 适配器共用单一真相源）。 */
    private AgentExecutionException mapException(Throwable cause) {
        if (ErrorClassifier.defaultClassifier().isTransient(cause)) {
            return new TransientException("Transient LLM 调用失败: " + cause.getMessage(), cause);
        }
        return new FatalException("LLM 调用失败: " + cause.getMessage(), cause);
    }

    private String redact(String text) {
        return text == null ? null : redactor.apply(text);
    }
}
