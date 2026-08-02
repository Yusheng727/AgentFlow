package com.agentflow.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * LLM 成本核算器（U7，KTD-3，R10）。
 *
 * <p>持模型单价表（model → {input, output} USD per 1M tokens），{@link #cost} 返回
 * promptTokens × input 单价 + completionTokens × output 单价。
 *
 * <h3>单价表来源（R4 风险：硬编码会过时）</h3>
 * <ol>
 *   <li>默认单价表：{@link #defaultPricings()}（OpenAI 2024 价目，硬编码在代码里作 fallback）</li>
 *   <li>配置覆盖：构造时传入 {@link #loadFromClasspath(String)} JSON 文件覆盖/扩展默认表
 *       （{@code agentflow-cost-pricings.json}，便于不改正码更新单价）</li>
 *   <li>程序覆盖：{@link #override(String, double, double)} 单条覆盖</li>
 * </ol>
 *
 * <h3>未知模型（R4）</h3>
 * <p>查不到的模型走 {@link #DEFAULT_FALLBACK}（input $1.00, output $2.00 per 1M tokens），
 * 并在日志 warn 一次（每 model 仅 warn 一次，避免日志洪泛）。
 *
 * <p>线程安全：单价表用 {@link ConcurrentHashMap}（由 {@link Collections#synchronizedMap} 包装的 LinkedHashMap 初始化后冻结默认部分，
 * override 加写锁——v1 用 volatile 整表替换简化，并发写罕见）。实测并发读为主。
 */
public final class CostCalculator {

    private static final Logger log = LoggerFactory.getLogger(CostCalculator.class);

    /** 默认兜底单价（未知模型）：input $1.00 / output $2.00 per 1M tokens。 */
    public static final Pricing DEFAULT_FALLBACK = new Pricing(1.00, 2.00);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    /** 单条模型单价（USD per 1M tokens）。 */
    public record Pricing(double inputPerMillion, double outputPerMillion) {
        public Pricing {
            if (inputPerMillion < 0 || outputPerMillion < 0) {
                throw new IllegalArgumentException("单价不能为负: " + inputPerMillion + "/" + outputPerMillion);
            }
        }
    }

    private volatile Map<String, Pricing> pricings;
    private final java.util.Set<String> warnedUnknown = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** 默认构造：用内置默认单价表。 */
    public CostCalculator() {
        this.pricings = new java.util.concurrent.ConcurrentHashMap<>(defaultPricings());
    }

    /**
     * 从 classpath 加载 JSON 单价表覆盖默认表。JSON 结构：
     * <pre>{@code
     * {
     *   "gpt-4o": { "input_per_million": 2.50, "output_per_million": 10.00 },
     *   "claude-sonnet-4": { "input_per_million": 3.00, "output_per_million": 15.00 }
     * }
     * }</pre>
     * 文件不存在时 warn 并保留默认表（启动不失败）。畸形 JSON（非数字单价、缺字段）
     * 逐条跳过并 warn，整体沿用默认表——不抛 ClassCastException 阻断启动
     *（reliability REL-3 + adversarial + security SEC-R3 三方确认）。
     *
     * @return this（链式）
     */
    public CostCalculator loadFromClasspath(String classpathResource) {
        Objects.requireNonNull(classpathResource, "classpathResource");
        try (InputStream in = CostCalculator.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                log.warn("成本单价表 {} 未找到，沿用默认单价表", classpathResource);
                return this;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = MAPPER.readValue(in, Map.class);
            Map<String, Pricing> merged = new java.util.concurrent.ConcurrentHashMap<>(this.pricings);
            raw.forEach((model, vals) -> {
                // 跳过非 Map 条目（如 _comment 注释字段，String 类型无法取单价）
                if (!(vals instanceof Map)) {
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> vm = (Map<String, Object>) vals;
                Object inVal = vm.get("input_per_million");
                Object outVal = vm.get("output_per_million");
                // 畸形条目（缺字段或非数字类型如 String/Array）跳过而非抛 ClassCastException
                if (!(inVal instanceof Number) || !(outVal instanceof Number)) {
                    log.warn("成本单价表 {} 条目 {} 非数字单价（input={}, output={}），跳过",
                            classpathResource, model, inVal, outVal);
                    return;
                }
                double input = ((Number) inVal).doubleValue();
                double output = ((Number) outVal).doubleValue();
                merged.put(model, new Pricing(input, output));
            });
            this.pricings = merged;
            log.info("成本单价表 {} 加载完成，共 {} 个模型", classpathResource, merged.size());
        } catch (IOException e) {
            log.warn("成本单价表 {} 加载失败，沿用默认单价表: {}", classpathResource, e.toString());
        }
        return this;
    }

    /**
     * 单条覆盖单价（程序化，最高优先级）。
     *
     * @return this（链式）
     */
    public CostCalculator override(String model, double inputPerMillion, double outputPerMillion) {
        Objects.requireNonNull(model, "model");
        Map<String, Pricing> merged = new java.util.concurrent.ConcurrentHashMap<>(this.pricings);
        merged.put(model, new Pricing(inputPerMillion, outputPerMillion));
        this.pricings = merged;
        return this;
    }

    /**
     * 计算单次 LLM 调用成本（USD）。
     *
     * @param model            模型名（查表，未知走 {@link #DEFAULT_FALLBACK} 并 warn 一次）
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     * @return USD 成本（double，精度 1e-10）
     */
    public double cost(String model, long promptTokens, long completionTokens) {
        Pricing p = model == null ? null : pricings.get(model);
        if (p == null) {
            if (model != null && warnedUnknown.add(model)) {
                log.warn("未知模型 [{}] 走默认单价 input=${}/output=${} per 1M tokens；可用 override() 或成本单价表配置覆盖",
                        model, DEFAULT_FALLBACK.inputPerMillion(), DEFAULT_FALLBACK.outputPerMillion());
            }
            p = DEFAULT_FALLBACK;
        }
        return (promptTokens / 1_000_000.0) * p.inputPerMillion()
                + (completionTokens / 1_000_000.0) * p.outputPerMillion();
    }

    /** 查某模型当前单价（测试/诊断用，未知返回 {@link #DEFAULT_FALLBACK}）。 */
    public Pricing pricingOf(String model) {
        Pricing p = model == null ? null : pricings.get(model);
        return p == null ? DEFAULT_FALLBACK : p;
    }

    /** 已注册模型集合（不可变视图）。 */
    public java.util.Set<String> knownModels() {
        return Collections.unmodifiableSet(pricings.keySet());
    }

    /**
     * 内置默认单价表（OpenAI 2024 价目，USD per 1M tokens）。
     * 来源：OpenAI 官网 2024 公开价目。模型单价变化频繁，应通过 classpath JSON 覆盖更新。
     */
    public static Map<String, Pricing> defaultPricings() {
        Map<String, Pricing> m = new LinkedHashMap<>();
        m.put("gpt-4o", new Pricing(2.50, 10.00));
        m.put("gpt-4o-mini", new Pricing(0.15, 0.60));
        m.put("gpt-4-turbo", new Pricing(10.00, 30.00));
        m.put("gpt-4", new Pricing(30.00, 60.00));
        m.put("gpt-3.5-turbo", new Pricing(0.50, 1.50));
        m.put("o1", new Pricing(15.00, 60.00));
        m.put("o1-mini", new Pricing(3.00, 12.00));
        m.put("o3-mini", new Pricing(1.10, 4.40));
        // 常见非 OpenAI 模型（社区价目，仅供估算，应按实际 provider 覆盖）
        m.put("claude-3-5-sonnet", new Pricing(3.00, 15.00));
        m.put("claude-3-haiku", new Pricing(0.25, 1.25));
        m.put("deepseek-chat", new Pricing(0.14, 0.28));
        return m;
    }
}
