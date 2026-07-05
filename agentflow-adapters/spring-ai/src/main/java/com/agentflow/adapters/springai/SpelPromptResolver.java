package com.agentflow.adapters.springai;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.MapAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板 SpEL 解析器（KTD-2 安全约束）。
 *
 * <p>定界符 {@code ${...}}（plan 约定，与 demo 引用同路径，<b>非</b> Spring 标准的 {@code #{}}）。
 * 占位符内为 SpEL 表达式，针对只读根对象求值后字符串化替换。
 *
 * <p>根对象结构（合并 channel + inputs，U3 冒烟定）：
 * <pre>
 *   {
 *     "context": { channel 名 → 值 },        // AgentInput.context() 的 channel 扁平视图
 *     "inputs":   { 工作流入参键 → 值 }       // AgentInput.inputs()
 *   }
 * </pre>
 * 引用示例：{@code ${context.financeAnalysis.riskScore}}、{@code ${inputs.supplier}}。
 *
 * <p>安全（KTD-2）：用 {@link SimpleEvaluationContext#forReadOnlyDataBinding()} 禁反射/类引用/构造器——
 * {@code ${T(java.lang.System).exit(0)}} 等表达式会抛 {@link SpelEvaluationException}，不执行。
 *
 * <p>缺占位符的模板原样返回；占位符求值为 null 替换为空串（避免字面 "null" 污染 prompt）。
 */
final class SpelPromptResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(.*?)}", Pattern.DOTALL);

    private final SpelExpressionParser parser = new SpelExpressionParser();

    /** 解析 prompt 模板中的 ${...} 占位符。 */
    String resolve(String template, Map<String, Object> channelValues, Map<String, Object> inputs) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Map<String, Object> contextView = new HashMap<>();
        if (channelValues != null) {
            contextView.putAll(channelValues);
        }
        // 用 record 作根对象：context/inputs 是 record 组件（bean 属性），
        // SimpleEvaluationContext 的 ReflectivePropertyAccessor 能解析组件返回的嵌套 Map
        // （直接用 HashMap 作根时，根级 Map 属性访问不被 forReadOnlyDataBinding 识别）。
        Root root = new Root(contextView, inputs == null ? Map.of() : inputs);

        // Spring 7 的 forReadOnlyDataBinding() 不再注册 MapAccessor（Map 属性访问移出
        // ReflectivePropertyAccessor），故用 forPropertyAccessors 显式注册两者：
        //   - DataBindingPropertyAccessor：解析 record 组件 / bean 属性（root 的 context/inputs），
        //     SimpleEvaluationContext 拒绝裸 ReflectivePropertyAccessor，必须用其数据绑定子类
        //   - MapAccessor：解析嵌套 Map 的键作为属性（context.financeAnalysis.riskScore）
        // 安全（KTD-2）：forPropertyAccessors 的 Builder 不设 TypeLocator / MethodResolver，
        // 故 T() 类型引用与方法调用均抛 SpelEvaluationException，禁反射/系统调用。
        EvaluationContext evalContext = SimpleEvaluationContext
                .forPropertyAccessors(
                        DataBindingPropertyAccessor.forReadOnlyAccess(),
                        new MapAccessor())
                .withRootObject(root)
                .build();

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String expr = matcher.group(1);
            Object value;
            try {
                Expression expression = parser.parseExpression(expr);
                value = expression.getValue(evalContext);
            } catch (SpelEvaluationException e) {
                // 安全违例（T() 类引用 / 构造器 / 反射）或解析错误 → 重新抛出，由适配器映射为 FatalException
                throw e;
            }
            String replacement = value == null ? "" : value.toString();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** SpEL 根对象：context（channel 扁平视图）+ inputs（工作流入参）。 */
    private record Root(Map<String, Object> context, Map<String, Object> inputs) {
    }
}
