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
import com.agentflow.prompt.OutputSchemaValidator;
import com.agentflow.prompt.SpelPromptResolver;
import com.agentflow.security.PromptRedactionFilter;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolMemoryId;
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
import dev.langchain4j.service.tool.ToolExecutor;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.spel.SpelEvaluationException;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    /** v1.1 C2 结构化输出 schema 校验器（可空；非空且节点声明 output_schema 时带反馈重试校验）。
     *  core 复用件（与 Spring 适配器共用），补上 KTD-7 "相同 DSL 相同结果"对价。 */
    private final OutputSchemaValidator schemaValidator;
    private final SpelPromptResolver promptResolver = new SpelPromptResolver();

    /** 便捷构造：无工具、无 trace、不脱敏、不记账、不校验（测试/最小用法）。 */
    public LangChain4jAgentAdapter(ChatModel chatModel) {
        this(chatModel, List.of(), null, null, null, null, null);
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
        this(chatModel, toolBeans, trace, redactor, null, null, null);
    }

    /**
     * 完整构造：支持记账。
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
        this(chatModel, toolBeans, trace, redactor, metrics, model, null);
    }

    /**
     * 最全构造：记账 + 结构化输出 schema 校验。
     *
     * @param schemaValidator OutputSchemaValidator（可空；非空且节点有 output_schema 时启用校验 + 反馈重试）
     */
    public LangChain4jAgentAdapter(ChatModel chatModel,
                                   List<Object> toolBeans,
                                   ExecutionTrace trace,
                                   Function<String, String> redactor,
                                   AgentFlowMetrics metrics,
                                   String model,
                                   OutputSchemaValidator schemaValidator) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.toolBeans = toolBeans == null ? List.of() : List.copyOf(toolBeans);
        this.trace = trace;
        // C3 收尾：不注入 redactor 时默认 PromptRedactionFilter::redact（原 identity 让 trace 摘要不脱敏，
        // 生产应默认脱敏敏感信息——sk-/Bearer/手机号/身份证）。显式注入可覆盖为自定义脱敏。
        this.redactor = redactor == null ? PromptRedactionFilter::redact : redactor;
        this.metrics = metrics;
        this.model = model;
        this.schemaValidator = schemaValidator;
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

        // 2. LLM 调用（工具循环；节点声明 output_schema 且配置了 schemaValidator → 带反馈重试校验，C2）
        String content;
        Map<String, Object> structuredOutput = Map.of();
        long promptTokens;
        long completionTokens;
        try {
            if (schemaValidator != null && !input.outputSchema().isEmpty()) {
                // schema 校验 + 重试：validator 内部多次调 chatWithTools，用 AtomicReference 捕获末次 usage
                // 供 AgentOutput.metadata（展示用）；预算/指标已在 chatWithTools 逐轮记账，重试花费不丢失
                java.util.concurrent.atomic.AtomicReference<LlmResult> last = new java.util.concurrent.atomic.AtomicReference<>();
                OutputSchemaValidator.ValidatedOutput vo = schemaValidator.validateWithRetry(
                        resolvedPrompt, input.outputSchema(), p -> callForSchema(input, p, last));
                content = vo.content();
                structuredOutput = vo.structuredOutput();
                LlmResult lastResult = last.get();
                promptTokens = lastResult == null ? 0 : lastResult.promptTokens();
                completionTokens = lastResult == null ? 0 : lastResult.completionTokens();
            } else {
                LlmResult r = chatWithTools(input, resolvedPrompt);
                content = r.content();
                promptTokens = r.promptTokens();
                completionTokens = r.completionTokens();
            }
        } catch (FatalException e) {
            // 确定失败（schema 校验耗尽 / 工具链截断）：NodeTrace 终态化后原样抛
            // （避免被 RuntimeException 分支再包装成误导性的 "LLM 调用失败"）
            nodeTrace.fail(e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            Throwable cause = (e.getCause() instanceof Exception) ? e.getCause() : e;
            // schema 回调里 chatWithTools 的检查型 FatalException 被包装成 RuntimeException——解包后原样抛，
            // 不落入 mapException（否则再包一层 "LLM 调用失败"）
            if (cause instanceof FatalException fatal) {
                nodeTrace.fail(fatal.getMessage());
                throw fatal;
            }
            nodeTrace.fail(cause.getMessage());
            throw mapException(e); // 传原始 e（保留 LC4j 框架标记，toExecutionException 沿 cause 兜底）
        }

        // 3. 构造 AgentOutput（content + structuredOutput + metadata{tokens}，与 Spring 适配器同 schema）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("promptTokens", promptTokens);
        metadata.put("completionTokens", completionTokens);
        metadata.put("totalTokens", promptTokens + completionTokens);

        nodeTrace.succeed(redact(content), promptTokens, completionTokens);
        log.debug("agent executed: node={} agent={} tokens={}", input.nodeId(), input.agentName(),
                promptTokens + completionTokens);
        return new AgentOutput(content, Map.of(), structuredOutput, Map.copyOf(metadata));
    }

    /**
     * schema 校验回调：一次 LLM 调用（含工具循环）。{@link Function} 不能抛检查型异常，
     * 故把 {@code chatWithTools} 的检查型 {@link FatalException}（工具链截断）包成 RuntimeException，
     * 由 execute() 的 catch 解包原样抛。同时把本次调用 usage 存入 last（跨 schema 重试取末次，与 Spring 一致）。
     */
    private String callForSchema(AgentInput input, String prompt,
                                 java.util.concurrent.atomic.AtomicReference<LlmResult> last) {
        try {
            LlmResult r = chatWithTools(input, prompt);
            last.set(r);
            return r.content();
        } catch (FatalException fe) {
            throw new RuntimeException(fe);
        }
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
    private LlmResult chatWithTools(AgentInput input, String prompt) throws FatalException {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(prompt));
        List<ToolSpecification> specs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();
        // C4 收尾：按节点声明 input.tools() 过滤注册（空 = 注册全部，向后兼容）
        collectTools(specs, executors, input.tools());

        long promptTokens = 0;
        long completionTokens = 0;
        for (int round = 0; ; round++) {
            // v1.1 review REL-1：NodeExecutor 取消节点（future.cancel(true) 中断 VT）后不再新开付费 LLM
            // 轮次——中断要传播进当前阻塞 chat() 才会停，晚开一轮就多一轮计费。取消场景下本结果已被丢弃，
            // 此处仅尽早停手防追加计费。
            if (Thread.currentThread().isInterrupted()) {
                log.warn("工具循环检测到线程中断，提前停止（已累计 tokens={}/{}）", promptTokens, completionTokens);
                metricAndBudget(input, promptTokens, completionTokens);
                return new LlmResult(null, promptTokens, completionTokens);
            }
            ChatRequest.Builder builder = ChatRequest.builder().messages(messages);
            if (!specs.isEmpty()) {
                builder.toolSpecifications(specs);
            }
            ChatResponse response = chatModel.chat(builder.build());
            TokenUsage usage = response == null ? null : response.tokenUsage();
            long roundPrompt = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
            long roundCompletion = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
            promptTokens += roundPrompt;
            completionTokens += roundCompletion;
            // C1 review：按真实付费调用<b>逐轮</b>记账——工具多轮、schema 重试、随后失败已付费的轮次都计入
            // token/成本与 per-workflow 预算，不再只在成功路径记末次（否则重试/失败花费被静默剔除，预算被低估）
            metricAndBudget(input, roundPrompt, roundCompletion);

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

    /** 按一次真实 LLM 调用的用量记账（metrics 为 null 则 no-op；budget 为 null 只记 token/cost）。
     *  从 chatWithTools 逐轮调用——保证已付费的轮次无论最终成败都计入指标与预算（C1 review 修复）。 */
    private void metricAndBudget(AgentInput input, long promptTokensDelta, long completionTokensDelta) {
        if (metrics == null) {
            return;
        }
        metrics.recordTokens(input.agentName(), model, promptTokensDelta, completionTokensDelta);
        metrics.recordBudget(input.budget(), model, promptTokensDelta, completionTokensDelta);
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

    /**
     * 反射扫描 @Tool bean：方法 → ToolSpecification（注册给模型）+ SafeToolExecutor（本地安全执行）。
     * <p>B3/ADV-2：改用 {@link #collectToolMethods} 全层级遍历（含基类/接口），不再用
     * {@code getDeclaredMethods()}（只扫本类，漏继承 @Tool）。
     */
    private void collectTools(List<ToolSpecification> specs, Map<String, ToolExecutor> executors,
                              List<String> requestedTools) {
        List<String> requested = requestedTools == null ? List.of() : requestedTools;
        for (Object bean : toolBeans) {
            for (Method m : collectToolMethods(bean.getClass())) {
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
                // C4 收尾：节点声明了 tools（非空）时只注册被请求的名称，其余 @Tool 不对模型暴露
                // （per-node 运行时工具过滤；空 = 注册全部，向后兼容）
                if (!requested.isEmpty() && !requested.contains(spec.name())) {
                    continue;
                }
                // C1 review（agent-native+adversarial）：executors 键是 spec.name()（last-wins）。同名 @Tool
                // （重载，或 B3 扩大枚举后两个共享类层级的 bean）会给模型重复 ToolSpecification 名——只在
                // 首次遇到某名字时 add 一次，执行器仍按 name 映射到具体 Method
                if (!executors.containsKey(spec.name())) {
                    specs.add(spec);
                }
                executors.put(spec.name(), new SafeToolExecutor(bean, m));
            }
        }
    }

    /**
     * B3 收集类层级上所有 @Tool 方法：本类 + 基类 + 接口（含其父接口）。不用 {@code getMethods()}——
     * 那会丢非 public @Tool（本项目/工具多有包私有方法，如 SafeToolExecutor 的测试）；也不能只看
     * {@code getDeclaredMethods()}——那漏基类/接口。故逐层用 {@code getDeclaredMethods()} 扫 + 签名去重
     * （类先于接口/基类），保证具体实现优先、同一逻辑方法不重复注册。
     */
    private static List<Method> collectToolMethods(Class<?> type) {
        Map<String, Method> bySignature = new LinkedHashMap<>();
        walkHierarchy(type, bySignature);
        return new ArrayList<>(bySignature.values());
    }

    private static void walkHierarchy(Class<?> type, Map<String, Method> acc) {
        if (type == null || type == Object.class) {
            return;
        }
        for (Method m : type.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                acc.putIfAbsent(signature(m), m);
            }
        }
        for (Class<?> itf : type.getInterfaces()) {
            walkHierarchy(itf, acc);
        }
        walkHierarchy(type.getSuperclass(), acc);
    }

    /** 方法签名（名 + 参数类型）作去重键：类实现/接口抽象/基类同逻辑方法只留最先那个（类优先）。 */
    private static String signature(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(p.getName()).append(',');
        }
        return sb.append(')').toString();
    }

    /**
     * 安全工具执行器（B1/SEC-1）：直接反射调用 @Tool 方法，异常真实细节只进服务端日志，
     * 返回泛化错误给模型。
     *
     * <p><b>为何不包装 {@code DefaultToolExecutor}</b>：其 {@code execute()} 在 @Tool 抛异常时
     * 捕获 {@code InvocationTargetException} 并直接 <b>返回原始异常消息字符串</b>（不抛出）——
     * 从外部包装根本拦不到异常。故自持 bean+method 自行 invoke，在异常边界把敏感内部细节
     * （DB 连接串/文件路径/内网地址）挡在递给模型之前，只留服务端日志。
     */
    static final class SafeToolExecutor implements ToolExecutor {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final Object bean;
        private final Method method;

        SafeToolExecutor(Object bean, Method method) {
            this.bean = bean;
            this.method = method;
        }

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            try {
                Object[] args = prepareArguments(request.arguments(), memoryId);
                Object result = invoke(args);
                return render(result);
            } catch (Exception e) { // 覆盖 InvocationTargetException(checked) + 参数解析 Runtime
                Throwable root = deepestThrowable(e);
                // 中断恢复（C1 review reliability）：cancel 在工具阻塞于可中断操作时到达 → InterruptedException
                // 清除 flag；不恢复则上层 REL-1 中断检查失效，已取消节点会再开付费 LLM 轮
                if (root instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // 真实 cause 只进服务端日志——@Tool 异常 msg 可能含敏感内部细节，绝不能进模型/result（SEC-1）
                log.warn("工具执行失败 tool={}: {}", method.getName(), root);
                return "{\"error\":\"tool execution failed\"}";
            }
        }

        private Object[] prepareArguments(String jsonArgs, Object memoryId) {
            Parameter[] params = method.getParameters();
            if (params.length == 0) {
                return new Object[0];
            }
            Map<String, Object> args = parseArguments(jsonArgs);
            Object[] out = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Parameter p = params[i];
                if (p.isAnnotationPresent(ToolMemoryId.class)) {
                    out[i] = memoryId;
                    continue;
                }
                out[i] = coerce(args.get(p.getName()), p.getType());
            }
            return out;
        }

        /** 解析工具参数 JSON 为 Map（容忍空串/空/畸形——失败按无参数处理，异常由本方法兜底）。 */
        @SuppressWarnings("unchecked")
        private Map<String, Object> parseArguments(String jsonArgs) {
            if (jsonArgs == null || jsonArgs.isBlank()) {
                return Map.of();
            }
            try {
                Object v = MAPPER.readValue(jsonArgs, Object.class);
                return v instanceof Map ? (Map<String, Object>) v : Map.of();
            } catch (Exception e) {
                log.warn("工具参数 JSON 解析失败 tool={}: {}", method.getName(), jsonArgs);
                return Map.of();
            }
        }

        /**
         * 参数值 → 目标类型（对齐 DefaultToolExecutor/Jackson 语义）。
         * <p>C1 review（maintainability+testing+adversarial 三评审合流）：删掉手写 int/long/double/boolean
         * 分支——它们对「字符串数字」（{"count":"3"}→int）会 ClassCastException、对越界静默截断、对数字 1→boolean
         * 误判。全部交给 {@link ObjectMapper#convertValue}：字符串→数字能转、无法转换抛清晰异常而非常驻泛化错误的
         * 静默截断，与框架 `coerceArgument` 行为对齐。
         */
        private Object coerce(Object value, Class<?> type) {
            if (value == null || type.isInstance(value)) {
                return value;
            }
            if (type == String.class) {
                return String.valueOf(value);
            }
            return MAPPER.convertValue(value, type);
        }

        private Object invoke(Object[] args) throws ReflectiveOperationException {
            if (!method.canAccess(bean)) {
                method.setAccessible(true); // 支持包私有 @Tool（本项目工具即包私有）
            }
            return method.invoke(bean, args);
        }

        /** void → "Success"；String → 原样；其余 → JSON（对齐 DefaultToolExecutor 结果语义）。 */
        private String render(Object result) {
            if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
                return "Success";
            }
            if (result == null) {
                return "null";
            }
            if (result instanceof String s) {
                return s;
            }
            try {
                return MAPPER.writeValueAsString(result);
            } catch (Exception e) {
                return String.valueOf(result);
            }
        }

        private static Throwable deepestThrowable(Throwable t) {
            Throwable cur = t;
            while (cur.getCause() != null) {
                cur = cur.getCause();
            }
            return cur;
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

    /** LangChain4j 框架特有 transient 分类器（v1.1 B2：LC4j 自带的 {@code RetriableException} 标记覆盖
     *  {@code RateLimitException}/{@code InternalServerException}/{@code TimeoutException} 等可重试子类；
     *  {@code NonRetriableException} 子类（Authentication/InvalidRequest/ModelNotFound）不匹配 → 正确判 fatal）。
     *  知识放适配器、不放 core（core 保持框架无关）。 */
    private static final ErrorClassifier LC4J_CLASSIFIER = ErrorClassifier.composed(
            cause -> cause instanceof dev.langchain4j.exception.RetriableException);

    /** 异常映射：委托 core ErrorClassifier（U4 canonical）+ composed/LC4j 规则 + 沿 cause 兜底（B2 review P1）。
     *  传<b>原始</b>异常（frame 标记在外层，如 RateLimitException(httpException)），不先 unwrap。 */
    private AgentExecutionException mapException(Throwable thrown) {
        return ErrorClassifier.toExecutionException(LC4J_CLASSIFIER, thrown);
    }

    private String redact(String text) {
        return text == null ? null : redactor.apply(text);
    }
}
