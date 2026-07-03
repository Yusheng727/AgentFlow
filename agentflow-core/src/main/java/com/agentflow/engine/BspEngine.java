package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.dsl.ChannelDefinition;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.Reducer;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.NoopCheckpointManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BSP 执行引擎（KTD-1 核心）。
 *
 * <p>循环：{@code Plan（分层）→ Execute（Virtual Threads 并行执行当前 super-step 节点，
 * 只读快照互不可见）→ Barrier（按声明序 Reducer 合并到全局 context）→ Checkpoint（seam）
 * → 检查下一层}。
 *
 * <ul>
 *   <li>分层复用 {@link DAGLayerer}（0-based super-step）</li>
 *   <li>同 super-step 节点用 {@link CompletableFuture#allOf} 统一等待——最快也要等最慢的（barrier 语义）</li>
 *   <li>任一节点抛异常 → {@link NodeResult.Failure}（不影响其他节点完成）→ barrier 后聚合抛
 *       {@link WorkflowExecutionException}（U2 测试场景 6）</li>
 *   <li>channel 并发写按节点声明序 Reducer 合并，结果确定（U2 测试场景 4）</li>
 * </ul>
 *
 * <p>Checkpoint seam：节点级（完成当下）+ barrier 级，默认 {@link NoopCheckpointManager}，
 * U5 注入 PG 实现。Recovery（U5）从 nextSuperStep 恢复——本类 v1 不内置 recover 入口，
 * U5 将扩展 {@code recoverAndExecute}。
 *
 * <p>静态 DAG only（KTD-9）：无条件分支，留给 v2。
 */
public final class BspEngine {

    private static final Logger log = LoggerFactory.getLogger(BspEngine.class);

    private final DAGLayerer layerer;

    public BspEngine() {
        this(new DAGLayerer());
    }

    public BspEngine(DAGLayerer layerer) {
        this.layerer = Objects.requireNonNull(layerer, "layerer");
    }

    /** 便捷入口：无 checkpoint、无自定义 reducer（开发/测试）。 */
    public WorkflowContext execute(WorkflowDefinition def,
                                   Map<String, AgentFunction> agents,
                                   Map<String, Object> inputs) {
        return execute(def, agents, inputs, new NoopCheckpointManager(), new ChannelReducer(), null);
    }

    /**
     * 完整入口。
     *
     * @param def        已校验的工作流定义
     * @param agents     agent 名 → AgentFunction（U3 的 NodeRegistry 将作为该 Map 的来源）
     * @param inputs     工作流启动入参
     * @param checkpoint 持久化 seam（U5 注入 PG 实现）
     * @param reducer    channel 合并器（含 CUSTOM reducer 注册）
     * @param workflowId 工作流执行 id（checkpoint 关联用，可 null）
     * @return 最终 context（含所有 channel 终值）
     */
    public WorkflowContext execute(WorkflowDefinition def,
                                   Map<String, AgentFunction> agents,
                                   Map<String, Object> inputs,
                                   CheckpointManager checkpoint,
                                   ChannelReducer reducer,
                                   String workflowId) {
        Objects.requireNonNull(def, "def");
        Objects.requireNonNull(agents, "agents");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(reducer, "reducer");
        CheckpointManager cp = checkpoint;
        DAGraph dag = new DAGraph(def);
        List<SuperStep> steps = buildSuperSteps(layerer.computeSuperSteps(def));
        Map<String, Object> effInputs = inputs == null ? Map.of() : inputs;
        WorkflowContext context = new WorkflowContext(effInputs);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        NodeExecutor nodeExecutor = new NodeExecutor(agents::get, executor);
        try {
            for (SuperStep step : steps) {
                WorkflowContext snapshot = context.readOnlySnapshot();
                List<NodeResult> results = runSuperStep(step, dag, snapshot, nodeExecutor, cp, workflowId, executor, effInputs);
                applyBarrier(step, results, context, dag, def, reducer, cp, workflowId);
            }
            return context;
        } finally {
            executor.shutdownNow();
        }
    }

    /** 并行执行 super-step 内所有节点，barrier 等待全部完成。 */
    private List<NodeResult> runSuperStep(SuperStep step, DAGraph dag, WorkflowContext snapshot,
                                          NodeExecutor nodeExecutor, CheckpointManager cp, String workflowId,
                                          ExecutorService executor, Map<String, Object> inputs) {
        List<CompletableFuture<NodeResult>> futures = new ArrayList<>(step.nodeIds().size());
        for (String id : step.nodeIds()) {
            NodeDefinition node = dag.node(id);
            AgentInput input = new AgentInput(id, node.agent(), node.promptTemplate(), snapshot, inputs);
            // 并行执行 + 节点级 checkpoint（完成当下即持久化，R3）
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    NodeResult r = nodeExecutor.execute(node, input);
                    if (r instanceof NodeResult.Success s) {
                        // 节点级 checkpoint 失败不应崩溃工作流（U5 决定 fatal/non-fatal 策略）；
                        // 降级 warn，主结果保留——recovery 可能因此重跑该节点（LLM 重复计费风险由 U5 兜底）
                        try {
                            cp.saveNodeOutput(workflowId, step.index(), id, s.output());
                        } catch (RuntimeException ce) {
                            log.warn("saveNodeOutput 失败 wf={} step={} node={}: {}",
                                    workflowId, step.index(), id, ce.toString());
                        }
                    }
                    return r;
                } catch (RuntimeException ex) {
                    // 防御性 catch-all：保持 no-throw 不变量，避免 allOf exceptional 丢失兄弟节点结果
                    return new NodeResult.Failure(id, ex);
                }
            }, executor));
        }
        // barrier：等最慢的节点完成。lambda 内已 catch-all，故 allOf 不会 exceptional
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<NodeResult> results = new ArrayList<>(futures.size());
        for (CompletableFuture<NodeResult> f : futures) {
            results.add(f.join());
        }
        return results;
    }

    /** Barrier 阶段：按声明序合并成功节点输出，收集失败，失败则聚合抛出；仅成功 super-step 写 barrier checkpoint。 */
    private void applyBarrier(SuperStep step, List<NodeResult> results, WorkflowContext context,
                              DAGraph dag, WorkflowDefinition def, ChannelReducer reducer,
                              CheckpointManager cp, String workflowId) {
        List<Throwable> failures = new ArrayList<>();
        // results 已按声明序（提交序），保证 Reducer 合并确定
        for (NodeResult r : results) {
            if (r instanceof NodeResult.Success s) {
                applyOutput(context, dag.node(s.nodeId()), s.output(), def, reducer);
            } else if (r instanceof NodeResult.Failure f) {
                failures.add(f.error());
            }
        }
        if (!failures.isEmpty()) {
            // U4 ErrorHandler 将在此前插入补偿（写 context.errorHandled=true 等）
            // 失败 super-step 不写 barrier checkpoint——KTD-3：barrier checkpoint 记录"已完成"super-step，
            // 失败层未完成；U5 Recovery 查 nextSuperStep 的节点级 COMPLETED 输出重跑失败节点
            throw new WorkflowExecutionException(step.index(), failures);
        }
        cp.saveBarrier(workflowId, step.index(), context);
    }

    /** 把 AgentOutput 的 channelWrites 合并进全局 context（按 channel 的 Reducer）。 */
    private void applyOutput(WorkflowContext context, NodeDefinition node, AgentOutput output,
                             WorkflowDefinition def, ChannelReducer reducer) {
        if (output == null) {
            // 防御：NodeExecutor 已把 null output 转为 Failure，此处兜底
            return;
        }
        Map<String, Object> writes;
        if (output.channelWrites() != null && !output.channelWrites().isEmpty()) {
            writes = output.channelWrites();
        } else if (output.content() != null) {
            // 便捷约定：无显式 channelWrites 时按 channel=节点 id 写入 content
            writes = Map.of(node.id(), output.content());
        } else {
            return;
        }
        for (Map.Entry<String, Object> e : writes.entrySet()) {
            Reducer r = channelReducer(def, e.getKey());
            Object current = context.getValue(e.getKey());
            Object merged = reducer.merge(e.getKey(), current, e.getValue(), r);
            context.put(e.getKey(), merged);
        }
    }

    /** 取 channel 声明的 Reducer，未声明默认 OVERWRITE。 */
    private static Reducer channelReducer(WorkflowDefinition def, String channel) {
        ChannelDefinition cd = def.channels() == null ? null : def.channels().get(channel);
        return cd == null || cd.reducer() == null ? Reducer.OVERWRITE : cd.reducer();
    }

    private static List<SuperStep> buildSuperSteps(List<List<String>> layers) {
        List<SuperStep> steps = new ArrayList<>(layers.size());
        for (int i = 0; i < layers.size(); i++) {
            steps.add(new SuperStep(i, layers.get(i)));
        }
        return steps;
    }
}
