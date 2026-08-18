package com.agentflow.api;

import java.util.Map;

/**
 * 异步执行派发请求（KTD-A）：只携带身份 + 入参，定义由执行侧按 (workflowName, version) 重取。
 */
public record WorkflowDispatchRequest(
        String workflowId,
        String workflowName,
        String version,
        Map<String, Object> inputs
) {}
