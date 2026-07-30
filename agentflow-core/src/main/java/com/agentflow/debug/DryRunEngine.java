package com.agentflow.debug;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.WorkflowDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dry-run 执行引擎（U6，R11）。
 *
 * <p>复用 {@link com.agentflow.engine.BspEngine} 的 DAG 拓扑逻辑（分层），但所有 AgentFunction
 * 替换为核心本地内置的 {@link DryRunMockAgentFunction}——不调真实 LLM，不依赖 U9 的
 * {@code MockAgentFunction}（消除 core→adapter 反向依赖）。
 *
 * <p>走完整个 DAG 后返回每步的预期输入输出 schema，开发者可据此验证拓扑正确性。
 */
public final class DryRunEngine {

    private final DAGLayerer layerer = new DAGLayerer();

    /**
     * Dry-run 单步结果：节点 id + agent 名 + 预期输入 channels + 预期输出。
     */
    public record StepResult(String nodeId, String agentName, int superStep,
                             Map<String, Object> expectedInputs, String expectedOutput) {
    }

    /**
     * Dry-run 工作流，返回每个 super-step 的各节点预期输入输出。
     *
     * @param def    工作流定义
     * @param inputs 启动入参（供 SpEL 上下文用）
     * @return 按 super-step → 节点顺序的步骤列表
     */
    public List<StepResult> dryRun(WorkflowDefinition def, Map<String, Object> inputs) {
        List<List<String>> layers = layerer.computeSuperSteps(def);
        List<StepResult> results = new ArrayList<>();
        // 按 nodeId 索引 NodeDefinition 便于查找
        Map<String, NodeDefinition> nodeMap = def.nodes() != null
                ? def.nodes().stream().collect(Collectors.toMap(NodeDefinition::id, n -> n))
                : Map.of();

        // 模拟 BSP 执行：super-step 间串行推进，同层节点按声明序，输出写 channel
        Map<String, Object> channelState = new LinkedHashMap<>(inputs != null ? inputs : Map.of());
        AgentFunction agent = new DryRunMockAgentFunction();

        for (int step = 0; step < layers.size(); step++) {
            for (String nodeId : layers.get(step)) {
                NodeDefinition node = nodeMap.get(nodeId);
                if (node == null) {
                    continue;
                }
                // 节点预期的输入 channels（从当前 channelState + def 的 edges 推断）
                Map<String, Object> expectedInputs = buildExpectedInputs(def, nodeId, channelState);
                String expectedOutput;
                try {
                    expectedOutput = agent.execute(
                            new AgentInput(nodeId, node.agent(), node.promptTemplate(), null,
                                    inputs, node.tools(), node.outputSchema(), node.mockResponse()))
                            .content();
                } catch (com.agentflow.agent.AgentExecutionException e) {
                    expectedOutput = "<error: " + e.getMessage() + ">";
                }

                // 写输出到 channel（同引擎便捷约定：channel = nodeId）
                channelState.put(nodeId, expectedOutput);

                results.add(new StepResult(nodeId, node.agent(), step, expectedInputs, expectedOutput));
            }
        }
        return results;
    }

    /** 构建节点预期输入 channels——从入边 + 当前 channelState 推断。 */
    private Map<String, Object> buildExpectedInputs(WorkflowDefinition def, String nodeId,
                                                     Map<String, Object> channelState) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (def.edges() == null) {
            return inputs;
        }
        for (var edge : def.edges()) {
            if (edge.to().equals(nodeId)) {
                String channel = edge.from();
                inputs.put(channel, channelState.getOrDefault(channel, null));
            }
        }
        return inputs;
    }
}
