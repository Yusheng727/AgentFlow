package com.agentflow.starter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Grafana Dashboard 指标名防漂移测试（U10 后续 #12，advisory 加固）。
 *
 * <p>看板 JSON 里 PromQL 引用的每个 Micrometer→Prometheus 指标族（点号转下划线、Counter 加
 * {@code _total}、Timer 加 {@code _seconds_bucket}）必须都存在——避免「Java 常量改名后面板静默空」。
 * 若 AgentFlowMetrics 的常量被改（如 {@code agentflow.workflow.executed} 改名），此类名族不再匹配
 * 面板引用，本测试即失败。
 */
class GrafanaDashboardMetricAlignmentTest {

    /** AgentFlowMetrics 5 常量 → Prometheus 指标族（Micrometer 命名转换后的形态）。 */
    private static final Set<String> EXPECTED_FAMILIES = Set.of(
            "agentflow_workflow_executed_total",          // WORKFLOW_EXECUTED (Counter)
            "agentflow_node_duration_seconds_bucket",     // NODE_DURATION (Timer, percentile histogram)
            "agentflow_tokens_consumed_total",            // TOKENS_CONSUMED (Counter)
            "agentflow_workflow_cost_estimated_total",    // WORKFLOW_COST_ESTIMATED (Counter USD)
            "agentflow_workflow_cost_budget_exceeded_total" // WORKFLOW_COST_BUDGET_EXCEEDED (Counter)
    );

    @Test
    @DisplayName("Grafana JSON 引用的每个指标族都存在于 AgentFlowMetrics 常量族（防面板静默空）")
    void dashboardMetricsAlignWithAgentFlowMetrics() throws Exception {
        String json = new String(
                new ClassPathResource("grafana/agentflow-dashboard.json").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        // 提取面板 PromQL 引用的指标族（agentflow_ 前缀 + 小写词）
        Set<String> referenced = new java.util.HashSet<>();
        Matcher m = Pattern.compile("agentflow_[a-z_]+").matcher(json);
        while (m.find()) {
            referenced.add(m.group());
        }

        assertThat(referenced).describedAs("看板引用的指标族必须被 AgentFlowMetrics 常量族覆盖").isNotEmpty();
        assertThat(EXPECTED_FAMILIES).containsAll(referenced);
    }
}
