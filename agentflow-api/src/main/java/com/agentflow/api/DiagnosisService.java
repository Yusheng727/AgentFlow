package com.agentflow.api;

import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.NodeTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工作流诊断服务（U6，R12）。
 *
 * <p>分析 {@link ExecutionTrace.Snapshot}，识别 5 类常见问题并输出诊断报告 + 修复建议。
 */
public class DiagnosisService {

    /**
     * 诊断结果：问题类型 + 节点 + 描述 + 建议。
     */
    public record Diagnosis(String problemType, String nodeId, String description, String suggestion) {
    }

    /** 诊断报告。 */
    public record DiagnosisReport(String workflowId, int totalNodes, int failedNodes,
                                   List<Diagnosis> findings) {
    }

    /**
     * 分析 ExecutionTrace 快照，识别 5 类问题：
     * <ol>
     *   <li>连续超时（同一节点 status=FAILED + error 含 timeout）</li>
     *   <li>Token 异常消耗（某节点 token 远超其他节点均值 ×3）</li>
     *   <li>SpEL 解析失败（error 含 "SpEL" 或 "SpelEvaluation"）</li>
     *   <li>Channel 缺失（error 含 "channel" 或 "null"）</li>
     *   <li>节点重复执行（同一 nodeId 出现多次，非正常跨 super-step 重复）</li>
     * </ol>
     */
    public DiagnosisReport diagnose(ExecutionTrace.Snapshot trace) {
        List<Diagnosis> findings = new ArrayList<>();
        List<NodeTrace> nodes = trace.nodes() != null ? trace.nodes() : List.of();
        if (nodes.isEmpty()) {
            return new DiagnosisReport(trace.workflowId(), 0, 0, findings);
        }

        int failedCount = (int) nodes.stream().filter(n -> n.status() == NodeTrace.Status.FAILED).count();

        // 1. 连续超时
        findTimeoutFailures(nodes, findings);
        // 2. Token 异常消耗
        findTokenAnomalies(nodes, findings);
        // 3-4. SpEL / channel 错误
        findErrorPatterns(nodes, findings);
        // 5. 节点重复
        findDuplicateNodes(nodes, findings);

        return new DiagnosisReport(trace.workflowId(), nodes.size(), failedCount, findings);
    }

    private void findTimeoutFailures(List<NodeTrace> nodes, List<Diagnosis> findings) {
        List<NodeTrace> timeouts = nodes.stream()
                .filter(n -> n.status() == NodeTrace.Status.FAILED)
                .filter(n -> n.error() != null && n.error().toLowerCase().contains("timeout"))
                .collect(Collectors.toList());
        if (!timeouts.isEmpty()) {
            for (NodeTrace t : timeouts) {
                findings.add(new Diagnosis("连续超时", t.nodeId(),
                        "Agent " + t.agentName() + " 执行超时（" + t.duration().toMillis() + "ms）",
                        "建议延长 TimeoutPolicy 或检查 LLM 连通性"));
            }
        }
    }

    private void findTokenAnomalies(List<NodeTrace> nodes, List<Diagnosis> findings) {
        List<NodeTrace> succeeded = nodes.stream()
                .filter(n -> n.status() == NodeTrace.Status.SUCCESS && n.totalTokens() > 0)
                .collect(Collectors.toList());
        if (succeeded.size() < 2) {
            return;
        }
        double avg = succeeded.stream().mapToLong(NodeTrace::totalTokens).average().orElse(0);
        for (NodeTrace n : succeeded) {
            if (n.totalTokens() > avg * 3 && n.totalTokens() > 100) {
                findings.add(new Diagnosis("Token 异常消耗", n.nodeId(),
                        "Agent " + n.agentName() + " 消耗 " + n.totalTokens() + " tokens"
                                + "（均值 " + (long) avg + "，超 3 倍阈值）",
                        "检查 prompt_template 是否过长或模型参数（temperature/max_tokens）配置"));
            }
        }
    }

    private void findErrorPatterns(List<NodeTrace> nodes, List<Diagnosis> findings) {
        for (NodeTrace n : nodes) {
            if (n.status() != NodeTrace.Status.FAILED || n.error() == null) {
                continue;
            }
            String err = n.error();
            if (err.contains("SpEL") || err.contains("SpelEvaluation")) {
                findings.add(new Diagnosis("SpEL 解析失败", n.nodeId(),
                        "节点 " + n.agentName() + " 的 prompt_template 中 " + err,
                        "检查 ${channel} 引用是否指向已存在的 channel 名；确认上游节点产出该 channel"));
            } else if (err.toLowerCase().contains("channel") || err.contains("null") && err.contains("channel")) {
                findings.add(new Diagnosis("Channel 缺失", n.nodeId(),
                        "节点 " + n.agentName() + " 引用的 channel 可能不存在：" + err,
                        "检查 YAML edges 拓扑，确认上游节点产出下游所需的 channel"));
            }
        }
    }

    private void findDuplicateNodes(List<NodeTrace> nodes, List<Diagnosis> findings) {
        Map<String, Long> counts = nodes.stream()
                .filter(n -> n.status() == NodeTrace.Status.SUCCESS)
                .collect(Collectors.groupingBy(NodeTrace::nodeId, Collectors.counting()));
        // 同一 nodeId 出现 > 1 次 = 可能重复执行（正常跨 super-step 每个 nodeId 只执行一次）
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > 1) {
                findings.add(new Diagnosis("节点重复执行", entry.getKey(),
                        "节点 " + entry.getKey() + " 执行了 " + entry.getValue() + " 次（正常为 1 次/工作流）",
                        "检查 RecoveryProtocol 是否正确跳过 completedNodeIds；确认无 retry 后仍成功并记录两次 trace"));
            }
        }
    }
}
