package com.agentflow.api;

/**
 * 工作流异步执行派发抽象（v1.1，KTD-8）。
 *
 * <p>把「提交后的异步执行」从 WorkflowController 内部虚拟线程池解耦出来：
 * 默认 {@link LocalVirtualThreadDispatcher}（本进程 VT，等价 v1 行为）；kafka-starter 提供
 * Kafka 实现（submit 走消息队列），实现提交/执行解耦。装配由 {@code agentflow.kafka.enabled}
 * 属性门控（KTD-C），不启用时回落本地，向后兼容。
 */
public interface WorkflowDispatcher {

    /**
     * 异步派发一个工作流执行（提交方调用后立即返回）。
     *
     * <p>定义按 {@code (workflowName, version)} 从 WorkflowVersionManager 重取（KTD-A），
     * 执行语义统一收敛在 {@link WorkflowExecutionService#run}（KTD-B）。
     */
    void dispatch(WorkflowDispatchRequest request);
}
