package com.agentflow.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentFlow 配置属性（U9 起引入，U13 扩展）。
 *
 * <pre>
 * agentflow:
 *   mock:
 *     enabled: true   # 开启 Mock LLM 模式，所有 Agent 替换为 MockAgentFunction
 * </pre>
 */
@ConfigurationProperties(prefix = "agentflow")
public class AgentFlowProperties {

    private final Mock mock = new Mock();

    public Mock getMock() {
        return mock;
    }

    public static class Mock {
        /** 开启 Mock LLM 模式：所有 AgentFunction 解析为 MockAgentFunction，不发真实 LLM 请求。 */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
