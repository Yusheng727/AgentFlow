package com.agentflow.demo.contract;

import com.agentflow.agent.AgentFunction;
import com.agentflow.adapters.mock.MockAgentFunction;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.dsl.WorkflowDSLParser;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.WorkflowContext;
import com.agentflow.engine.checkpoint.InMemoryCheckpointManager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Map;

/**
 * 合同审核串行流水线对比 Demo（U11，R15）。
 *
 * <p>工作流：4 步串行依赖链（合同解析 → 法律风险 → 合规建议 → 最终报告）。
 * BSP 分层：每步依赖前一步 → 4 个 super-step，每个 super-step 恰 1 个节点，与 U10
 * 并行拓扑（3 并行 + 1 汇总）形成对比，验证引擎串行依赖链 + 上下文传递能力。
 *
 * <h3>运行模式</h3>
 * <ul>
 *   <li><b>mock 模式</b>（默认，{@code agentflow.mock.enabled=true}）：用 {@link MockAgentFunction}
 *       从 YAML {@code mock_response} 读预设响应，每步用 {@code ${previousStep}} 占位符引用上一步输出，
 *       零 LLM 成本验证拓扑 + 上下文传递 + 串行依赖</li>
 *   <li><b>真实模式</b>（{@code agentflow.mock.enabled=false} + 配 OpenAI key）：4 个 AgentFunction Bean
 *       走 Spring AI ChatClient（合同解析 / 法律风险 / 合规建议 / 报告汇总 Agent）</li>
 * </ul>
 *
 * <p>U11 范围（v4.3 解耦）：编程式组装引擎跑通 mock 模式，验证引擎核心能力。
 * {@code @EnableAgentFlow} 一键启动 + REST 端点在 U13 Starter 封装落地。
 */
@SpringBootApplication
public class ContractReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContractReviewApplication.class, args);
    }

    /**
     * 启动入口 Bean：解析 contract-review.yml 并跑通工作流（mock 模式下零 LLM 成本）。
     */
    @Bean
    public String runContractReviewWorkflow() {
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = Map.of(
                "contract-agent", mock,
                "legal-agent", mock,
                "compliance-agent", mock,
                "report-agent", mock);

        try (InputStream yaml = new ClassPathResource("workflows/contract-review.yml").getInputStream()) {
            WorkflowDefinition def = new WorkflowDSLParser().parse(yaml);
            WorkflowContext result = new BspEngine().execute(
                    def, agents, Map.of("contractTitle", "某 SaaS 服务订阅合同"),
                    new InMemoryCheckpointManager(), new ChannelReducer(), "demo-contract-review-1");
            System.out.println("=== 合同审核最终报告 ===");
            System.out.println(result.getValue("final-report"));
            return (String) result.getValue("final-report");
        } catch (Exception e) {
            throw new RuntimeException("合同审核工作流执行失败", e);
        }
    }
}
