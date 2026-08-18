package com.agentflow.api;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 默认本地派发（KTD-C）：本进程虚拟线程池执行，等价 v1 的 {@code executor.submit} 行为。
 *
 * <p>无 Kafka 时 WorkflowController 默认用此实现，向后兼容。执行语义委托
 * {@link WorkflowExecutionService#run}（KTD-B）。
 */
public class LocalVirtualThreadDispatcher implements WorkflowDispatcher {

    private final WorkflowExecutionService service;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LocalVirtualThreadDispatcher(WorkflowExecutionService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public void dispatch(WorkflowDispatchRequest request) {
        executor.submit(() ->
                service.run(request.workflowId(), request.workflowName(), request.version(), request.inputs()));
    }
}
