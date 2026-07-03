package com.agentflow.engine;

import java.util.List;

/**
 * 工作流执行异常：某 super-step 内一个或多个节点失败（barrier 后聚合抛出）。
 *
 * <p>BSP 异常隔离语义：失败节点不影响同层其他节点完成，barrier 阶段统一收集所有 Failure
 * 后抛本异常。U4 的 ErrorHandler 将在此抛出<b>之前</b>插入补偿逻辑（写 context 补偿字段），
 * 之后引擎把工作流转为 FAILED（abort，不推进下游 super-step，避免 SpEL 引用缺失 channel 递归失败）。
 */
public class WorkflowExecutionException extends RuntimeException {

    private final int superStep;
    private final List<Throwable> failures;

    public WorkflowExecutionException(int superStep, List<Throwable> failures) {
        super("super-step " + superStep + " 失败: " + failures.size() + " 个节点异常");
        this.superStep = superStep;
        this.failures = List.copyOf(failures);
    }

    public int superStep() {
        return superStep;
    }

    public List<Throwable> failures() {
        return failures;
    }
}
