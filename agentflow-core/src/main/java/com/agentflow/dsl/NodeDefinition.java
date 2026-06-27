package com.agentflow.dsl;

import java.util.List;
import java.util.Map;

/**
 * 节点定义。每个节点是一个 Agent 调用单元（R5）。
 *
 * <pre>
 * nodes:
 *   - id: financial-analysis
 *     agent: finance-agent
 *     prompt_template: "分析供应商财务..."
 *     tools: [finance-db-query]
 *     timeout: 120s
 *     retry: { max_attempts: 3, initial_backoff: 1s }
 *     output_schema: { type: object, properties: { riskLevel: { type: string } } }
 *     mock_response: "..."
 * </pre>
 */
public record NodeDefinition(
        String id,
        String agent,
        String promptTemplate,
        List<String> tools,
        String timeout,
        RetryConfig retry,
        Map<String, Object> outputSchema,
        String mockResponse
) {
}
