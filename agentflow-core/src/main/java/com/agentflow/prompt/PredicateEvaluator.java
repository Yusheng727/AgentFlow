package com.agentflow.prompt;

import com.agentflow.agent.FatalException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.MapAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.Map;

/**
 * v2 条件分支谓词求值器：把 `when` 布尔表达式对 from 节点输出求值。
 *
 * <p>复用 {@link SpelPromptResolver} 的 hardened {@link SimpleEvaluationContext}
 * （禁 T()/反射/方法调用，KTD-2 安全），但这是「原始表达式 → boolean」的布尔求值入口，
 * 非 SpelPromptResolver 的 {@code ${...}} 字符串模板替换。根对象 {@code { output: ... }}，
 * 谓词用 {@code output.<field>} 引用。
 *
 * <p>求值错误（解析错误 / 类型不匹配 / T() 违例）与非 boolean 结果按 {@link FatalException}
 * 抛出（对齐「SpEL 错误即 Fatal」约定）；只有成功求值为 false 才表示「该边不命中」。
 * 注：Map 缺失键经 MapAccessor 返回 null（不抛异常），故 {@code output.missing == 'x'} 得 false
 * 而非错误；裸字段 {@code output.missing} 得 null → 非 boolean → 抛 Fatal。
 */
public final class PredicateEvaluator {

    private final SpelExpressionParser parser = new SpelExpressionParser();

    /**
     * 求值布尔谓词。
     *
     * @param expression SpEL 布尔表达式（如 {@code output.verdict == 'approved'}）
     * @param output     from 节点输出（调用方拼好 structuredOutput 优先 / content 降级的 Map）
     * @return 谓词求值结果
     * @throws FatalException 求值错误或结果非 boolean
     */
    public boolean evaluate(String expression, Map<String, Object> output) throws FatalException {
        Root root = new Root(output == null ? Map.of() : output);
        EvaluationContext evalContext = SimpleEvaluationContext
                .forPropertyAccessors(
                        DataBindingPropertyAccessor.forReadOnlyAccess(),
                        new MapAccessor())
                .withRootObject(root)
                .build();

        Object value;
        try {
            Expression expr = parser.parseExpression(expression);
            value = expr.getValue(evalContext);
        } catch (SpelEvaluationException e) {
            // 解析错误 / 类型不匹配 / T() 安全违例 → 暴露为 Fatal，而非静默「不命中」
            throw new FatalException("when 谓词求值失败: " + expression, e);
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new FatalException("when 谓词结果非 boolean: " + expression
                + " → " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    /** SpEL 根对象：output（from 节点输出 Map）。 */
    private record Root(Map<String, Object> output) {
    }
}
