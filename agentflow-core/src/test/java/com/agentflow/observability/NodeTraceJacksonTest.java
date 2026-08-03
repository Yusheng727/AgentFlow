package com.agentflow.observability;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NodeTrace Jackson 序列化契约测试（UI Trace 端点的 wire 形状）。
 *
 * <p>锁定 UI 端 {@code NodeTrace} 类型依赖的字段名：nodeId / agentName / status /
 * durationMs / promptTokens / completionTokens / totalTokens / outputSummary / error。
 * 之前 NodeTrace 是非 bean 的 plain class（private 字段 + record 风格 accessor、无
 * @JsonProperty），默认 Jackson 会序列化成空对象「{}」导致真实 trace 轨迹渲染失败；
 * 本测试防止该回归（见 ce-code-review 对 U2 UI 的验证）。
 */
class NodeTraceJacksonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("succeed 后序列化：字段名与 UI NodeTrace 契约一致，含 durationMs")
    void serializesWithUiContractFieldNames() throws Exception {
        NodeTrace trace = new NodeTrace("contract-parse", "contract-parse-agent", 1_000_000L);
        trace.succeed("解析完成", 100, 50);

        String json = mapper.writeValueAsString(trace);

        assertThat(json)
                .contains("\"nodeId\":\"contract-parse\"")
                .contains("\"agentName\":\"contract-parse-agent\"")
                .contains("\"status\":\"SUCCESS\"")
                .contains("\"promptTokens\":100")
                .contains("\"completionTokens\":50")
                .contains("\"totalTokens\":150")
                .contains("\"outputSummary\":\"解析完成\"")
                // 关键：UI/PipelineView 读 durationMs（真实轨迹此前渲染 "undefinedms"）
                .contains("\"durationMs\"");
        // duration()/isTerminal 不外泄（内部实现/派生布尔，非 wire 契约）
        assertThat(json).doesNotContain("\"duration\":").doesNotContain("\"terminal\"");
    }

    @Test
    @DisplayName("fail 后序列化：捕获 status=FAILED 与 error")
    void serializesFailedState() throws Exception {
        NodeTrace trace = new NodeTrace("legal-risk", "legal-risk-agent", 1_000_000L);
        trace.fail("合同金额缺失");

        assertThat(mapper.writeValueAsString(trace))
                .contains("\"status\":\"FAILED\"")
                .contains("\"error\":\"合同金额缺失\"")
                .contains("\"totalTokens\":0");
    }

    @Test
    @DisplayName("durationMs 是毫秒单位（非纳秒）")
    void durationMsIsMillis() {
        NodeTrace trace = new NodeTrace("a", "b", 1_000_000L);
        trace.endNanosForTest(150_000_000L); // 1ms start -> 150ms end
        assertThat(trace.durationMs()).isEqualTo(149L);
        assertThat(Duration.ofNanos(150_000_000L - 1_000_000L).toMillis()).isEqualTo(149L);
    }
}
