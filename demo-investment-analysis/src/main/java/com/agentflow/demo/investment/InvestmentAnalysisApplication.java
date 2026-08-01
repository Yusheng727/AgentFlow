package com.agentflow.demo.investment;

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
 * 投资分析决策辅 Demo（U12，R17）。
 *
 * <p>双层 fork-join 混合拓扑（6 节点 4 super-step）：
 * <ul>
 *   <li>super-step 0：company_finance + market_data（2 并行采集）</li>
 *   <li>super-step 1：feasibility_analysis（1 串行，依赖 step 0 两路）</li>
 *   <li>super-step 2：risk_assessment + return_forecast（2 并行，依赖 step 1）</li>
 *   <li>super-step 3：investment_decision（1 汇总，引用前 3 层全部输出）</li>
 * </ul>
 * 验证 {@link com.agentflow.dsl.DAGLayerer#computeSuperSteps} 最长路径分层对复杂混合拓扑的泛用性，
 * 以及 BSP super-step 间上下文传递（下游 mock_response 的 ${channel} 占位符替换验证上游输出可达）。
 *
 * <h3>运行模式</h3>
 * <ul>
 *   <li><b>mock 模式</b>（默认，{@code agentflow.mock.enabled=true}）：用 {@link MockAgentFunction}
 *       从 YAML {@code mock_response} 读预设响应，零 LLM 成本，验证拓扑 + 占位符传递 + BSP 双层并行</li>
 *   <li><b>真实模式</b>（{@code agentflow.mock.enabled=false} + 配 OpenAI key）：6 个 AgentFunction Bean
 *       走 Spring AI ChatClient</li>
 * </ul>
 *
 * <p>U12 范围：编程式组装引擎跑通 mock 模式，验证引擎复杂拓扑能力。
 */
@SpringBootApplication
public class InvestmentAnalysisApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvestmentAnalysisApplication.class, args);
    }

    /**
     * 启动入口 Bean：解析 investment-analysis.yml 并跑通工作流（mock 模式下零 LLM 成本）。
     */
    @Bean
    public String runInvestmentWorkflow() {
        MockAgentFunction mock = new MockAgentFunction();
        Map<String, AgentFunction> agents = Map.of(
                "finance-agent", mock,
                "market-agent", mock,
                "feasibility-agent", mock,
                "risk-agent", mock,
                "return-agent", mock,
                "decision-agent", mock);

        try (InputStream yaml = new ClassPathResource("workflows/investment-analysis.yml").getInputStream()) {
            WorkflowDefinition def = new WorkflowDSLParser().parse(yaml);
            WorkflowContext result = new BspEngine().execute(
                    def, agents, Map.of("target", "Acme Tech"),
                    new InMemoryCheckpointManager(), new ChannelReducer(), "demo-investment-analysis-1");
            System.out.println("=== 投资分析决策结果 ===");
            System.out.println(result.getValue("investment_decision"));
            return (String) result.getValue("investment_decision");
        } catch (Exception e) {
            throw new RuntimeException("投资分析决策工作流执行失败", e);
        }
    }
}
