package com.agentflow.api;

import com.agentflow.agent.NodeRegistry;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.engine.BspEngine;
import com.agentflow.engine.ChannelReducer;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import com.agentflow.version.WorkflowVersionManager;

import java.util.Map;
import java.util.Optional;

/**
 * 共享工作流执行逻辑（KTD-B）：把「RUNNING → engine.execute → SUCCESS/FAILED」收敛到一处。
 *
 * <p>本地 VT 调度（{@link LocalVirtualThreadDispatcher}）、Kafka 消费者（kafka-starter）、
 * retry 路径复用同一语义，避免两处漂移。定义按 (workflowName, version) 从
 * {@link WorkflowVersionManager#loadDefinition} 重取（与提交时 {@code recordWorkflowDefinition}
 * 对称，KTD-A）；缺失视为瞬时，短暂重试后再判失败（为未来跨节点留容忍窗口）。
 */
public class WorkflowExecutionService {

    private static final int DEF_LOOKUP_RETRY = 3;
    private static final long DEF_LOOKUP_BACKOFF_MS = 1000L;

    private final BspEngine engine;
    private final NodeRegistry nodeRegistry;
    private final CheckpointManager checkpointManager;
    private final ChannelReducer reducer;
    private final WorkflowVersionManager versionManager;
    private final int lookupRetry;
    private final long lookupBackoffMs;

    public WorkflowExecutionService(BspEngine engine,
                                    NodeRegistry nodeRegistry,
                                    CheckpointManager checkpointManager,
                                    ChannelReducer reducer,
                                    WorkflowVersionManager versionManager) {
        this(engine, nodeRegistry, checkpointManager, reducer, versionManager,
                DEF_LOOKUP_RETRY, DEF_LOOKUP_BACKOFF_MS);
    }

    /** 包私有：测试可注入重试参数（瞬时重试语义不变，仅控制耗时）。 */
    WorkflowExecutionService(BspEngine engine,
                             NodeRegistry nodeRegistry,
                             CheckpointManager checkpointManager,
                             ChannelReducer reducer,
                             WorkflowVersionManager versionManager,
                             int lookupRetry,
                             long lookupBackoffMs) {
        this.engine = engine;
        this.nodeRegistry = nodeRegistry;
        this.checkpointManager = checkpointManager;
        this.reducer = reducer;
        this.versionManager = versionManager;
        this.lookupRetry = lookupRetry;
        this.lookupBackoffMs = lookupBackoffMs;
    }

    /**
     * 同步执行一个工作流（由调用方决定在哪个线程跑）。定义先取、取不到不进 RUNNING（保持 PENDING）。
     */
    public void run(String workflowId, String workflowName, String version, Map<String, Object> inputs) {
        WorkflowDefinition def = loadDefinitionWithRetry(workflowName, version);
        checkpointManager.updateStatus(workflowId, WorkflowStatus.RUNNING);
        try {
            engine.execute(def, nodeRegistry, inputs, checkpointManager, reducer, workflowId);
            checkpointManager.updateStatus(workflowId, WorkflowStatus.SUCCESS);
        } catch (Exception e) {
            checkpointManager.updateStatus(workflowId, WorkflowStatus.FAILED);
            throw e;
        }
    }

    private WorkflowDefinition loadDefinitionWithRetry(String workflowName, String version) {
        for (int i = 0; i < lookupRetry; i++) {
            Optional<WorkflowDefinition> def = versionManager.loadDefinition(workflowName, version);
            if (def.isPresent()) {
                return def.get();
            }
            if (i < lookupRetry - 1) {
                sleepQuietly(lookupBackoffMs);
            }
        }
        throw new IllegalStateException(
                "工作流定义不存在: name=" + workflowName + " version=" + version);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("中断等待工作流定义", e);
        }
    }
}
