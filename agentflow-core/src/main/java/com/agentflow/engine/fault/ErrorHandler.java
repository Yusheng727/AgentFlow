package com.agentflow.engine.fault;

import com.agentflow.engine.WorkflowContext;

/**
 * 错误补偿处理器（plan U4）。
 *
 * <p>节点重试耗尽或 fatal 后，BspEngine 在转 FAILED（abort）前调用本处理器做 context 补偿——
 * 例如写 {@code errorHandled=true}、记录失败原因供下游/DiagnosisService 读。
 *
 * <p><b>v1 约束</b>：ErrorHandler <b>仅能修改 context</b>，不能跳转路径（跳转路径是 v2 动态路由能力）。
 * 补偿写入的是 BspEngine 全局 context（非 agent 的只读快照），故在 abort 阶段调用——不在 super-step
 * 执行中调（避免破坏 BSP 互不可见）。
 */
@FunctionalInterface
public interface ErrorHandler {

    /**
     * @param globalContext BspEngine 全局 context（可写），补偿数据写入此处
     * @param cause         节点失败的根因（post-retry）
     */
    void handle(WorkflowContext globalContext, Throwable cause);

    /**
     * 默认处理器：写 {@code errorHandled=true} + {@code errorCause=<message>} 到 context。
     */
    static ErrorHandler defaultHandler() {
        return (globalContext, cause) -> {
            if (globalContext != null) {
                globalContext.put("errorHandled", true);
                globalContext.put("errorCause", cause == null ? "unknown" : cause.getMessage());
            }
        };
    }
}
