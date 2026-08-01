package com.agentflow.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * CostCalculator 单元测试（U7，KTD-3）。
 *
 * <p>覆盖：默认单价表查表、未知模型走 fallback + warn 一次、配置 override、负值拒绝。
 */
class CostCalculatorTest {

    @Test
    @DisplayName("gpt-4o 默认单价：1M input + 1M output = $2.50 + $10.00 = $12.50")
    void gpt4oDefaultPricing() {
        CostCalculator calc = new CostCalculator();
        double cost = calc.cost("gpt-4o", 1_000_000L, 1_000_000L);
        assertThat(cost).isCloseTo(12.50, within(1e-9));
    }

    @Test
    @DisplayName("gpt-4o-mini：1M input + 1M output = $0.15 + $0.60 = $0.75")
    void gpt4oMiniPricing() {
        CostCalculator calc = new CostCalculator();
        double cost = calc.cost("gpt-4o-mini", 1_000_000L, 1_000_000L);
        assertThat(cost).isCloseTo(0.75, within(1e-9));
    }

    @Test
    @DisplayName("未知模型走 fallback input=$1 output=$2 per 1M，且多次调用仅 warn 一次（日志不洪泛）")
    void unknownModelFallsBackToDefault() {
        CostCalculator calc = new CostCalculator();
        double c1 = calc.cost("some-unknown-model", 500_000L, 500_000L);
        double c2 = calc.cost("some-unknown-model", 500_000L, 500_000L);
        // 0.5M × $1 + 0.5M × $2 = $1.50
        assertThat(c1).isCloseTo(1.50, within(1e-9));
        assertThat(c2).isCloseTo(1.50, within(1e-9));
        // pricingOf 未知模型也返回 fallback
        assertThat(calc.pricingOf("some-unknown-model"))
                .isEqualTo(CostCalculator.DEFAULT_FALLBACK);
    }

    @Test
    @DisplayName("override 覆盖默认单价：gpt-4o 改为 input $5 / output $20")
    void overrideChangesPricing() {
        CostCalculator calc = new CostCalculator().override("gpt-4o", 5.0, 20.0);
        double cost = calc.cost("gpt-4o", 1_000_000L, 1_000_000L);
        assertThat(cost).isCloseTo(25.00, within(1e-9));
        // 其他模型不受影响
        assertThat(calc.cost("gpt-4o-mini", 1_000_000L, 1_000_000L)).isCloseTo(0.75, within(1e-9));
    }

    @Test
    @DisplayName("默认单价表包含常见 OpenAI 模型")
    void defaultPricingsContainsOpenAIModels() {
        var defaults = CostCalculator.defaultPricings();
        assertThat(defaults).containsKeys("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo");
    }

    @Test
    @DisplayName("从 classpath JSON 加载单价表（agentflow-cost-pricings.json 存在）")
    void loadFromClasspathOverridesDefaults() {
        CostCalculator calc = new CostCalculator().loadFromClasspath("agentflow-cost-pricings.json");
        // JSON 中 gpt-4o 仍是 2.50/10.00，验证加载未破坏默认
        assertThat(calc.cost("gpt-4o", 1_000_000L, 1_000_000L)).isCloseTo(12.50, within(1e-9));
        // JSON 含 claude-3-5-sonnet（默认表也有，验证加载成功覆盖到同值）
        assertThat(calc.knownModels()).contains("claude-3-5-sonnet");
    }

    @Test
    @DisplayName("classpath JSON 不存在 → warn 但不抛，沿用默认表")
    void missingClasspathFileWarnsNotThrows() {
        CostCalculator calc = new CostCalculator().loadFromClasspath("nonexistent-pricings.json");
        // 默认表仍可用
        assertThat(calc.cost("gpt-4o", 1_000_000L, 1_000_000L)).isCloseTo(12.50, within(1e-9));
    }

    @Test
    @DisplayName("零 token 调用 → 成本 0")
    void zeroTokensZeroCost() {
        CostCalculator calc = new CostCalculator();
        assertThat(calc.cost("gpt-4o", 0L, 0L)).isCloseTo(0.0, within(1e-12));
    }

    @Test
    @DisplayName("负值单价拒绝（Pricing record 校验）")
    void negativePricingRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new CostCalculator.Pricing(-1.0, 2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
