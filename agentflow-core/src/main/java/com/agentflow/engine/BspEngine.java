package com.agentflow.engine;

import com.agentflow.agent.AgentFunction;
import com.agentflow.agent.AgentInput;
import com.agentflow.agent.AgentOutput;
import com.agentflow.agent.FatalException;
import com.agentflow.agent.NodeRegistry;
import com.agentflow.dsl.ChannelDefinition;
import com.agentflow.dsl.DAGLayerer;
import com.agentflow.dsl.EdgeDefinition;
import com.agentflow.dsl.NodeDefinition;
import com.agentflow.dsl.Reducer;
import com.agentflow.dsl.AgentflowMeta;
import com.agentflow.dsl.WorkflowDefinition;
import com.agentflow.engine.checkpoint.ApprovalRequest;
import com.agentflow.engine.checkpoint.CheckpointManager;
import com.agentflow.engine.checkpoint.ExecutionState;
import com.agentflow.engine.checkpoint.NodeOutputStore;
import com.agentflow.engine.checkpoint.NoopCheckpointManager;
import com.agentflow.engine.checkpoint.RecoveryProtocol;
import com.agentflow.engine.checkpoint.WorkflowStatus;
import com.agentflow.engine.fault.ErrorHandler;
import com.agentflow.engine.fault.RetryPolicy;
import com.agentflow.engine.fault.TimeoutPolicy;
import com.agentflow.observability.AgentFlowMetrics;
import com.agentflow.observability.ExecutionTrace;
import com.agentflow.observability.ExecutionTraceRegistry;
import com.agentflow.observability.WorkflowBudget;
import com.agentflow.prompt.PredicateEvaluator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /** v2 循环：引擎级迭代总轮次硬上界（纵深防御，防未声明上界/逻辑 bug 导致的死循环）。 */
    private static final int MAX_TOTAL_ROUNDS = 1000;

    private final DAGLayerer layerer;
    /** U4 容错：可空（null = 不重试，backward compat with U2）。 */
    private final RetryPolicy retryPolicy;
    /** U4 补偿：可空（null = 不调 ErrorHandler）。 */
    private final ErrorHandler errorHandler;
    /** U4 超时策略：可空（null = 无工作流总超时）。 */
    private final TimeoutPolicy timeoutPolicy;
    /** U7 可观测性：trace 注册表。可空（null = 不注册 trace，mock 模式下 TraceController 返回空树）。
     *  非空时 execute() 开头为每个 workflowId 创建 ExecutionTrace 并通过 AgentInput.trace() 透传给 AgentFunction。 */
    private final ExecutionTraceRegistry traceRegistry;
    /** U7 指标挂钩：可空（null = 不记指标，与 U7 之前行为一致，向后兼容）。
     *  非空时工作流完成记 {@code agentflow.workflow.executed}{status}、每节点完成记 {@code agentflow.node.duration}{agent}。 */
    private final AgentFlowMetrics metrics;
    /** v2 条件分支：when 谓词求值器（复用 hardened SpEL 上下文，表达式 → boolean）。 */
    private final PredicateEvaluator predicateEvaluator = new PredicateEvaluator();

    public BspEngine() {
        this(new DAGLayerer());
    }

    public BspEngine(DAGLayerer layerer) {
        this(layerer, null, null, null);
    }

    /**
     * U4 容错入口：注入 RetryPolicy / ErrorHandler / TimeoutPolicy。
     * 任一为 null 表示该层不启用（如 retryPolicy=null → 不重试，与 U2 行为一致）。
     */
    public BspEngine(DAGLayerer layerer, RetryPolicy retryPolicy,
                     ErrorHandler errorHandler, TimeoutPolicy timeoutPolicy) {
        this(layerer, retryPolicy, errorHandler, timeoutPolicy, null);
    }

    /**
     * U7 可观测性入口：在 U4 容错基础上注入 {@link ExecutionTraceRegistry}。
     * traceRegistry 为 null 表示不注册 trace（与 U2-U6 行为一致，向后兼容）。
     */
    public BspEngine(DAGLayerer layerer, RetryPolicy retryPolicy,
                     ErrorHandler errorHandler, TimeoutPolicy timeoutPolicy,
                     ExecutionTraceRegistry traceRegistry) {
        this(layerer, retryPolicy, errorHandler, timeoutPolicy, traceRegistry, null);
    }

    /**
     * U7 指标挂钩入口：在 trace 基础上注入 {@link AgentFlowMetrics}。
     * metrics 为 null 表示不记指标（与 U7 之前行为一致，向后兼容）。
     */
    public BspEngine(DAGLayerer layerer, RetryPolicy retryPolicy,
                     ErrorHandler errorHandler, TimeoutPolicy timeoutPolicy,
                     ExecutionTraceRegistry traceRegistry, AgentFlowMetrics metrics) {
        this.layerer = Objects.requireNonNull(layerer, "layerer");
        this.retryPolicy = retryPolicy;
        this.errorHandler = errorHandler;
        this.timeoutPolicy = timeoutPolicy;
        this.traceRegistry = traceRegistry;
        this.metrics = metrics;
    }

    /** 便捷入口（Map 形式）：无 checkpoint、无自定义 reducer（开发/测试）。 */
    public WorkflowContext execute(WorkflowDefinition def,
                                   Map<String, AgentFunction> agents,
                                   Map<String, Object> inputs) {
        return execute(def, agents, inputs, new NoopCheckpointManager(), new ChannelReducer(), null);
    }

    /**
     * 完整入口（Map 形式）。
     *
     * @param def        已校验的工作流定义
     * @param agents     agent 名 → AgentFunction（兼容老调用；新代码优先用 NodeRegistry 重载）
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
        Objects.requireNonNull(agents, "agents");
        return execute(def, agents::get, inputs, checkpoint, reducer, workflowId);
    }

    /** 便捷入口（NodeRegistry 形式，U3 起推荐）。 */
    public WorkflowContext execute(WorkflowDefinition def,
                                   NodeRegistry registry,
                                   Map<String, Object> inputs) {
        return execute(def, registry, inputs, new NoopCheckpointManager(), new ChannelReducer(), null);
    }

    /**
     * 完整入口（resolver 形式，U3 起推荐）。
     *
     * <p>接受 {@link NodeRegistry}（implements {@code Function<String,AgentFunction>}）
     * 或任意自定义 resolver，作为 BSP 引擎的 agent 来源。
     */
    public WorkflowContext execute(WorkflowDefinition def,
                                   NodeRegistry registry,
                                   Map<String, Object> inputs,
                                   CheckpointManager checkpoint,
                                   ChannelReducer reducer,
                                   String workflowId) {
        Objects.requireNonNull(registry, "registry");
        return execute(def, registry::apply, inputs, checkpoint, reducer, workflowId);
    }

    /**
     * resolver 形式核心实现（Map/NodeRegistry 重载最终都汇到此处）。
     */
    private WorkflowContext execute(WorkflowDefinition def,
                                    java.util.function.Function<String, AgentFunction> agentResolver,
                                    Map<String, Object> inputs,
                                    CheckpointManager checkpoint,
                                    ChannelReducer reducer,
                                    String workflowId) {
        Objects.requireNonNull(def, "def");
        Objects.requireNonNull(agentResolver, "agentResolver");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(reducer, "reducer");
        CheckpointManager cp = checkpoint;
        DAGraph dag = new DAGraph(def);
        List<SuperStep> steps = buildSuperSteps(layerer.computeSuperSteps(def));
        Map<String, Object> effInputs = inputs == null ? Map.of() : inputs;
        WorkflowContext context = new WorkflowContext(effInputs);

        // U7：为本次执行注册 trace（traceRegistry 为 null 或 workflowId 为空时 trace=null，不影响执行）
        ExecutionTrace trace = traceRegistry == null ? null : traceRegistry.register(workflowId);

        // R10：按 def.agentflow() 声明的 budget_tokens/budget_cost 构造 per-workflow 预算
        // （未声明任一维度 → null，记账方不查预算，向后兼容）
        WorkflowBudget budget = budgetFrom(def);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        NodeExecutor nodeExecutor = new NodeExecutor(agentResolver, executor);
        Instant workflowStart = Instant.now();
        boolean outcomeRecorded = false; // U7 指标：兜底防漏记/防双记
        boolean paused = false; // U4 审批暂停：合法中间态，finally 不兜底 FAILED
        try {
            // v2 条件分支：可达节点集（初始 = 源节点，随路由决策增量激活后继）；空工作流 → 空集
            Set<String> active = steps.isEmpty() ? new HashSet<>() : new HashSet<>(steps.get(0).nodeIds());
            // v2 on_error：经 on_error 激活的目标节点（其自身失败不二次跳转，避免级联兜底）
            Set<String> onErrorActivated = new HashSet<>();
            // v2 条件分支：已走边（from→to，累计），供 checkpoint 持久化路由决策（恢复期重算 SKIPPED）
            List<String> takenEdges = new ArrayList<>();
            // v2 循环：外层迭代轮次循环共享骨架。active = 本轮前向传播；nextActive = 回边目标累积（下一轮起点）。
            // 无回边工作流：首轮结束 nextActive 恒空 → 单轮收敛，行为与 v2 一致（R11）。
            paused = runRounds(steps, active, onErrorActivated, 0, 0, Set.of(), -1, effInputs, context, dag, def, reducer, cp,
                    workflowId, nodeExecutor, executor, workflowStart, trace, budget, takenEdges, maxIterationsOf(def));
            if (paused) {
                // U4 审批暂停：不标 SUCCESS/FAILED、不写 workflow.executed{success|failed}；
                // 审批 pending 指标已由 applyBarrier 记；trace 记 AWAITING_APPROVAL（合法中间态）
                if (trace != null) {
                    trace.markCompleted(ExecutionTrace.Status.AWAITING_APPROVAL);
                }
                return context;
            }
            if (trace != null) {
                if (!onErrorActivated.isEmpty()) {
                    trace.markCompletedViaOnError();
                }
                trace.markCompleted(ExecutionTrace.Status.COMPLETED);
            }
            recordWorkflowOutcome(onErrorActivated.isEmpty()
                    ? AgentFlowMetrics.STATUS_SUCCESS : AgentFlowMetrics.STATUS_FALLBACK);
            outcomeRecorded = true;
            return context;
        } catch (WorkflowExecutionException we) {
            if (trace != null) {
                trace.markCompleted(ExecutionTrace.Status.FAILED);
                trace.recordWorkflowError(describeFailure(we));
            }
            recordWorkflowOutcome(AgentFlowMetrics.STATUS_FAILED);
            outcomeRecorded = true;
            // U5 P0 修复（ADV-2）：abort 前显式标记 FAILED——RecoveryProtocol 据此鉴别崩溃层可能含
            // timeout 后在飞 VT 写出的 stray COMPLETED 记录，整体重跑该层，避免读到未经 barrier
            // 合并的孤立 channel 输出。best-effort：状态写入失败不掩盖原始 abort 异常。
            try {
                cp.updateStatus(workflowId, com.agentflow.engine.checkpoint.WorkflowStatus.FAILED);
            } catch (RuntimeException se) {
                log.warn("abort 时 updateStatus(FAILED) 失败 wf={}: {}", workflowId, se.toString());
            }
            throw we;
        } finally {
            // U7 兜底：catch(WorkflowExecutionException) 不覆盖 reducer.merge/applyOutput 抛的其他
            // RuntimeException（如 CUSTOM reducer 异常），trace 会永留 RUNNING 误导诊断。finally 里
            // 若 trace 仍 RUNNING 则标 FAILED（correctness #1，2 票确认）。best-effort，不掩盖原异常。
            if (trace != null && trace.status() == ExecutionTrace.Status.RUNNING) {
                trace.markCompleted(ExecutionTrace.Status.FAILED);
            }
            // U4：paused 是合法中间态（AWAITING_APPROVAL），不兜底记 FAILED
            if (!outcomeRecorded && !paused) {
                recordWorkflowOutcome(AgentFlowMetrics.STATUS_FAILED);
            }
            executor.shutdownNow();
        }
    }

    /** U7 指标：记一次工作流执行结果（metrics 为 null 则 no-op）。 */
    private void recordWorkflowOutcome(String status) {
        if (metrics != null) {
            metrics.recordWorkflowExecuted(status);
        }
    }

    /** R10：从 def.agentflow() 声明的 budget_tokens/budget_cost 构造 per-workflow 预算；
     *  未声明任一维度 → null（记账方不查预算，向后兼容）。 */
    private static WorkflowBudget budgetFrom(WorkflowDefinition def) {
        AgentflowMeta meta = def.agentflow();
        if (meta == null || (meta.budgetTokens() == null && meta.budgetCost() == null)) {
            return null;
        }
        return new WorkflowBudget(meta.budgetTokens(), meta.budgetCost());
    }

    /** v2 循环：取回边的 max_iterations（单回边场景取第一个 loop 边的上界）；无回边 → -1（不设上界）。 */
    private static int maxIterationsOf(WorkflowDefinition def) {
        if (def.edges() == null) {
            return -1;
        }
        for (EdgeDefinition e : def.edges()) {
            if (e.loop() && e.maxIterations() != null) {
                return e.maxIterations();
            }
        }
        return -1;
    }

    /** v2 循环：判断已走边列表里是否含回边（loop 边）决策——恢复期轮次转换检测用。 */
    private static boolean hasLoopDecision(List<String> takenEdges, WorkflowDefinition def) {
        if (def.edges() == null) {
            return false;
        }
        for (EdgeDefinition e : def.edges()) {
            if (e.loop() && takenEdges.contains(EdgeDefinition.edgeKey(e.from(), e.to()))) {
                return true;
            }
        }
        return false;
    }

    /** v2 循环：取「已走回边」的目标集合——恢复期进入下一轮的起点（复刻 execute 的 nextActive）。
     *  用于修复 backedge-target：新轮起点是回边目标而非 layer-0 源，避免复活已完成轮内上游节点。 */
    private static Set<String> backedgeTargets(WorkflowDefinition def, List<String> takenEdges) {
        Set<String> targets = new HashSet<>();
        if (def.edges() == null) {
            return targets;
        }
        for (EdgeDefinition e : def.edges()) {
            if (e.loop() && takenEdges.contains(EdgeDefinition.edgeKey(e.from(), e.to()))) {
                targets.add(e.to());
            }
        }
        return targets;
    }

    /**
     * v2 循环：外层迭代轮次循环共享骨架（execute 与 recoverAndExecute 共用，消除「复制必然漂移」历史 P0 模式）。
     * 首轮从 startLayer 起跑（execute 从层 0；恢复轮从崩溃层起、剔除已完成节点）；后续轮从层 0 完整遍历。
     * 每轮结束 nextActive 非空 → round++ 继续迭代；空 → 收敛结束；达上限 → checkIterationCap 抛「迭代超限」。
     */
    private boolean runRounds(List<SuperStep> steps, Set<String> active, Set<String> onErrorActivated,
                              int startRound, int startLayer, Set<String> firstExcluded, int firstCrashLayer,
                              Map<String, Object> inputs, WorkflowContext context, DAGraph dag, WorkflowDefinition def,
                              ChannelReducer reducer, CheckpointManager cp, String workflowId, NodeExecutor nodeExecutor,
                              ExecutorService executor, Instant workflowStart, ExecutionTrace trace, WorkflowBudget budget,
                              List<String> takenEdges, int maxIterations) {
        int round = startRound;
        while (true) {
            Set<String> nextActive = new HashSet<>();
            int layer0 = (round == startRound) ? startLayer : 0;
            Set<String> excludeThisRound = (round == startRound) ? firstExcluded : Set.of();
            int crashLayer = (round == startRound) ? firstCrashLayer : -1;
            for (int i = layer0; i < steps.size(); i++) {
                SuperStep step = steps.get(i);
                if (runStep(step, active, nextActive, excludeThisRound, crashLayer, round,
                        inputs, context, dag, def, reducer, cp, workflowId, nodeExecutor, executor,
                        workflowStart, trace, budget, onErrorActivated, takenEdges)) {
                    return true; // U4 审批暂停：本层未完成，立即退出（不写 barrier）
                }
            }
            if (trace != null) {
                trace.recordRound(round);
            }
            if (nextActive.isEmpty()) {
                break; // 本轮无回边命中 → 收敛
            }
            round++;
            checkIterationCap(round, maxIterations);
            active = nextActive;
        }
        return false;
    }

    /** v2 循环：迭代轮次超限检查（per-loop max_iterations + 引擎 maxTotalRounds 双保险，execute/recover 共享）。 */
    private static void checkIterationCap(int round, int maxIterations) {
        if (maxIterations >= 0 && round >= maxIterations) {
            throw new WorkflowExecutionException(-1, List.of(
                    new FatalException("迭代超限: 达到 max_iterations=" + maxIterations)));
        }
        if (round >= MAX_TOTAL_ROUNDS) {
            throw new WorkflowExecutionException(-1, List.of(
                    new FatalException("迭代超限(全局): 达到 maxTotalRounds=" + MAX_TOTAL_ROUNDS)));
        }
    }

    /**
     * 崩溃恢复 + 续跑入口（U5 KTD-3 Recovery，P0 修复 ADV-1/ADV-2 的消费端）。
     *
     * <p>调用 {@link RecoveryProtocol#recover} 取 {@link ExecutionState}，然后：
     * <ol>
     *   <li>用 {@code channelSnapshot} 重建 WorkflowContext（上一 barrier 的 channel 状态）</li>
     *   <li><b>重放 {@code replayOutputs}</b>：崩溃层已完成节点的 channelWrites 未进上一 barrier，
     *       按 Reducer 合并进 context，恢复崩溃前的 channel 状态（P0 修复 ADV-1）。
     *       这些节点在 {@code completedNodeIds} 中，跳过不重跑 LLM</li>
     *   <li>从 {@code nextSuperStep} 起跑剩余 super-step；崩溃层中不在 {@code completedNodeIds}
     *       的节点正常执行（含 FAILED 重跑、未启动节点首跑）</li>
     * </ol>
     *
     * <p>stray 防护（ADV-2）：若工作流状态为 FAILED（引擎 abort 显式标记），RecoveryProtocol 已把
     * completedNodeIds 与 replayOutputs 置空，崩溃层整体重跑——本方法无需额外处理。
     *
     * @param recovery 已构造的 RecoveryProtocol（持有 CheckpointManager）
     * @param def      工作流定义（用于 DAG 分层 + Reducer）
     * @param agentResolver agent 来源
     * @param reducer  channel Reducer
     * @param workflowId 工作流实例 id
     * @return 最终 WorkflowContext
     */
    public WorkflowContext recoverAndExecute(RecoveryProtocol recovery,
                                             WorkflowDefinition def,
                                             java.util.function.Function<String, AgentFunction> agentResolver,
                                             ChannelReducer reducer,
                                             String workflowId) {
        Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(def, "def");
        Objects.requireNonNull(agentResolver, "agentResolver");
        Objects.requireNonNull(reducer, "reducer");
        Objects.requireNonNull(workflowId, "workflowId");

        // U7：恢复路径同样注册 trace（与 execute() 对齐），否则 TraceController 查恢复续跑的工作流
        // 会返回旧 FAILED trace 或空，与 checkpoint 的 SUCCESS 状态分裂（adversarial/correctness/reliability/agent-native 4 票确认）。
        ExecutionTrace trace = traceRegistry == null ? null : traceRegistry.register(workflowId);

        // R10：恢复路径同样按定义构造 per-workflow 预算（与 execute() 对齐）
        WorkflowBudget budget = budgetFrom(def);

        ExecutionState state = recovery.recover(workflowId);
        log.info("recoverAndExecute wf={}: nextSuperStep={}, 跳过节点={}, 重放输出={}",
                workflowId, state.nextSuperStep(), state.completedNodeIds(), state.replayOutputs().size());

        // Step 1: 用 barrier 快照重建 context
        WorkflowContext context = new WorkflowContext(state.channelSnapshot());

        // Step 2: 重放崩溃层已完成节点的输出（ADV-1 修复核心）
        // 这些节点的 channelWrites 未进上一 barrier，必须按 Reducer 合并进 context，
        // 否则下游节点读到陈旧/null channel 值。跳过这些节点不重跑（completedNodeIds）。
        DAGraph dag = new DAGraph(def);
        for (AgentOutput replay : state.replayOutputs()) {
            // replayOutputs 与 completedNodeIds 同序同源，输出对应的 nodeId 见 NodeOutputStore；
            // 此处按 channelWrites 重放，不依赖 nodeId（applyOutput 需 NodeDefinition 仅取默认 channel 名）
            applyReplayOutput(context, replay, def, reducer);
        }

        // Step 3: 重建 round 维度 + 轮次转换检测
        List<SuperStep> allSteps = buildSuperSteps(layerer.computeSuperSteps(def));
        CheckpointManager cp = recovery.checkpointManager();

        // U4 防御（U5 前）：AWAITING_APPROVAL 是审批暂停（合法中间态），不是崩溃——经 recoverAndExecute
        // 误恢复会从崩溃层续跑、跳过等待的审批、破坏暂停语义。U5 approveAndResume 才处理该类工作流。
        if (cp.findStatus(workflowId).orElse(null) == WorkflowStatus.AWAITING_APPROVAL) {
            throw new IllegalStateException("工作流 " + workflowId + " 处于 AWAITING_APPROVAL（审批暂停），"
                    + "不可用 recoverAndExecute 恢复——需 approveAndResume（U5）");
        }

        int round = state.round();
        int crashLayerStep = state.nextSuperStep();
        List<String> crashedRoundDecisions = cp.findRoutingDecisions(workflowId, round);
        // 轮次转换检测：该轮回边已命中（critique->draft 在路由决策）→ 该轮实质完成（剩余仅空层 barrier），
        // 无论崩溃在末层 barrier 前/后都应进入下一轮从层 0 续跑。
        // 修复 mid-round crash：回边命中但末层 barrier 未写时此前不触发转换 → resume 丢 pending 迭代、SUCCESS 缺 finalize。
        boolean thisRoundHasBackedge = hasLoopDecision(crashedRoundDecisions, def);
        if (thisRoundHasBackedge) {
            round++;
            crashLayerStep = 0;
            log.info("恢复 wf={}: 该轮回边命中，进入下一轮 round={} 从层 0 续跑", workflowId, round);
        }
        // 无回边命中且 crashLayerStep >= size：latest barrier 已在最后一层、工作流已收敛——
        // crashLayerStep 保持 size → 下方 for 循环跑零层 → 收尾 SUCCESS。
        // 修复 final-barrier：此前 else 分支 crashLayerStep=0 把整个已收敛 DAG 从层 0 重跑（旧 v2 remaining 为空优雅收尾）。
        Set<String> excluded = (round == state.round()) ? state.completedNodeIds() : Set.of();

        // v2 条件分支/循环：重建可达集。已走边 = 当前 round 持久化路由决策 + 崩溃层已完成节点的路由重算。
        List<String> takenEdges = new ArrayList<>(round == state.round()
                ? crashedRoundDecisions : cp.findRoutingDecisions(workflowId, round));
        for (NodeOutputStore n : cp.findCompletedNodes(workflowId, round, crashLayerStep)) {
            try {
                // 恢复期 inputs 不可得（原入参未持久化），传空 Map；context 用重建后的 channel 快照
                for (EdgeDefinition e : resolveTakenEdges(n.nodeId(), n.output(), context, Map.of(), def)) {
                    takenEdges.add(EdgeDefinition.edgeKey(e.from(), e.to()));
                }
            } catch (FatalException fe) {
                log.warn("恢复 wf={}: 崩溃层已完成节点 {} 路由重算失败: {}", workflowId, n.nodeId(), fe.getMessage());
            }
        }
        Set<String> active;
        if (round != state.round()) {
            // 新轮起点 = 回边目标（复刻 execute 的 nextActive），不从 layer-0 源 BFS——
            // 否则 backedge 目标非源时会复活已完成轮内上游节点（修复 backedge-target，双计费）。
            active = backedgeTargets(def, crashedRoundDecisions);
        } else {
            active = computeReachable(allSteps.get(0).nodeIds(), dag, def, takenEdges);
        }
        Set<String> onErrorActivated = rebuildOnErrorActivated(def, takenEdges);

        int maxIterations = maxIterationsOf(def);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        NodeExecutor nodeExecutor = new NodeExecutor(agentResolver, executor);
        Instant workflowStart = Instant.now();
        boolean outcomeRecorded = false; // U7 指标：兜底防漏记/防双记
        boolean paused = false; // U4 审批暂停：合法中间态，finally 不兜底 FAILED
        try {
            cp.updateStatus(workflowId, WorkflowStatus.RUNNING);
            // v2 循环：恢复轮（round == startRound）从 crashLayerStep 起跑（剔除已完成节点），后续轮从层 0 完整遍历。
            paused = runRounds(allSteps, active, onErrorActivated, round, crashLayerStep, excluded, crashLayerStep, Map.of(),
                    context, dag, def, reducer, cp, workflowId, nodeExecutor, executor, workflowStart, trace, budget,
                    takenEdges, maxIterations);
            if (paused) {
                // U4：恢复续跑过程中再次遇审批 → 保持 AWAITING_APPROVAL（applyBarrier 已置），不标 SUCCESS/FAILED
                if (trace != null) {
                    trace.markCompleted(ExecutionTrace.Status.AWAITING_APPROVAL);
                }
                return context;
            }
            cp.updateStatus(workflowId, WorkflowStatus.SUCCESS);
            if (trace != null) {
                if (!onErrorActivated.isEmpty()) {
                    trace.markCompletedViaOnError();
                }
                trace.markCompleted(ExecutionTrace.Status.COMPLETED);
            }
            recordWorkflowOutcome(onErrorActivated.isEmpty()
                    ? AgentFlowMetrics.STATUS_SUCCESS : AgentFlowMetrics.STATUS_FALLBACK);
            outcomeRecorded = true;
            return context;
        } catch (WorkflowExecutionException we) {
            if (trace != null) {
                trace.markCompleted(ExecutionTrace.Status.FAILED);
                trace.recordWorkflowError(describeFailure(we));
            }
            recordWorkflowOutcome(AgentFlowMetrics.STATUS_FAILED);
            outcomeRecorded = true;
            try {
                cp.updateStatus(workflowId, WorkflowStatus.FAILED);
            } catch (RuntimeException se) {
                log.warn("recoverAndExecute abort 时 updateStatus(FAILED) 失败 wf={}: {}",
                        workflowId, se.toString());
            }
            throw we;
        } finally {
            // U4：paused 是合法中间态（AWAITING_APPROVAL），不兜底记 FAILED
            if (!outcomeRecorded && !paused) {
                recordWorkflowOutcome(AgentFlowMetrics.STATUS_FAILED);
            }
            executor.shutdownNow();
        }
    }

    /** 记录一个 super-step 内各节点所属的 BSP 层号（U10 后续 #10，供 UI 真实拓扑分组）。空 trace 则 no-op。 */
    private void recordSuperStepTrace(ExecutionTrace trace, SuperStep step) {
        if (trace == null) {
            return;
        }
        for (String nodeId : step.nodeIds()) {
            trace.recordStep(nodeId, step.index());
        }
    }

    /** v2 循环：记录本 super-step 节点所属迭代轮次（供 trace 区分跨轮重复执行的同 nodeId，agent-native）。 */
    private void recordNodeRounds(ExecutionTrace trace, SuperStep step, int round) {
        if (trace == null) {
            return;
        }
        for (String nodeId : step.nodeIds()) {
            trace.recordNodeRound(nodeId, round);
        }
    }

    /** 重放崩溃层已完成节点的输出进 context（走 Reducer，与 applyOutput 同语义）。 */
    private void applyReplayOutput(WorkflowContext context, AgentOutput output,
                                   WorkflowDefinition def, ChannelReducer reducer) {        if (output == null || output.channelWrites() == null || output.channelWrites().isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> e : output.channelWrites().entrySet()) {
            Reducer r = channelReducer(def, e.getKey());
            Object current = context.getValue(e.getKey());
            Object merged = reducer.merge(e.getKey(), current, e.getValue(), r);
            context.put(e.getKey(), merged);
        }
    }

    private List<NodeResult> runSuperStep(SuperStep step, DAGraph dag, WorkflowContext snapshot,
                                          NodeExecutor nodeExecutor, CheckpointManager cp, String workflowId,
                                          int round, ExecutorService executor, Map<String, Object> inputs,
                                          Instant workflowStart, ExecutionTrace trace, WorkflowBudget budget) {
        List<CompletableFuture<NodeResult>> futures = new ArrayList<>(step.nodeIds().size());
        for (String id : step.nodeIds()) {
            NodeDefinition node = dag.node(id);
            AgentInput input = new AgentInput(id, node.agent(), node.promptTemplate(), snapshot, inputs,
                    node.tools(), node.outputSchema(), node.mockResponse(), trace, budget, round);
            // 并行执行 + 节点级 checkpoint（完成当下即持久化，R3）
            futures.add(CompletableFuture.supplyAsync(() -> {
                long startNs = System.nanoTime(); // U7 指标：节点从提交到终结的耗时（含重试）
                try {
                    // U4：retryPolicy 包在 NodeExecutor 外层（null = 不重试，backward compat）
                    NodeResult r = retryPolicy != null
                            ? retryPolicy.execute(nodeExecutor, node, input)
                            : nodeExecutor.execute(node, input);
                    if (r instanceof NodeResult.Success s) {
                        // 节点级 checkpoint 失败不应崩溃工作流（U5 决定 fatal/non-fatal 策略）；
                        // 降级 warn，主结果保留——recovery 可能因此重跑该节点（LLM 重复计费风险由 U5 兜底）
                        try {
                            cp.saveNodeOutput(workflowId, round, step.index(), id, s.output());
                        } catch (RuntimeException ce) {
                            log.warn("saveNodeOutput 失败 wf={} step={} node={}: {}",
                                    workflowId, step.index(), id, ce.toString());
                        }
                    }
                    return r;
                } catch (RuntimeException ex) {
                    // 防御性 catch-all：保持 no-throw 不变量，避免 allOf exceptional 丢失兄弟节点结果
                    return new NodeResult.Failure(id, ex);
                } finally {
                    // U7 指标：无论成败都记节点耗时（agent tag）；metrics 为 null 则 no-op
                    if (metrics != null) {
                        metrics.recordNodeDuration(node.agent(), System.nanoTime() - startNs);
                    }
                }
            }, executor));
        }
        // barrier：等最慢的节点完成。lambda 内已 catch-all，故 allOf 不会 exceptional
        CompletableFuture<Void> allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        Duration remaining = timeoutPolicy != null ? timeoutPolicy.remainingWorkflow(workflowStart) : null;
        if (remaining != null) {
            // 工作流总超时：用 .get(remaining) 而非 .join()，超时/零剩余则抛 abort。
            // 注意：CompletableFuture.cancel(true) 不会中断 supplyAsync 已在跑的 VT（CF.cancel 仅标记完成，
            // 不传播 interrupt），真正中止在飞节点靠本方法抛出后 execute 的 finally { executor.shutdownNow(); }
            // 中断所有 VT。U5 注意：PostgresCheckpointManager 下，超时后仍可能在飞节点完成并调
            // cp.saveNodeOutput（写出 stray COMPLETED 记录到已 abort 的 super-step）——U5 Recovery 需
            // 鉴别 abort 后的 stray 记录（按 workflow 状态过滤），非 U4 问题（NoopCheckpointManager 无此风险）。
            try {
                allOf.get(remaining.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                throw new WorkflowExecutionException(step.index(), List.of(te));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new WorkflowExecutionException(step.index(), List.of(ie));
            } catch (ExecutionException ee) {
                // 不应发生（lambda catch-all），兜底解包
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                throw new WorkflowExecutionException(step.index(), List.of(cause));
            }
        } else {
            allOf.join();
        }
        List<NodeResult> results = new ArrayList<>(futures.size());
        for (CompletableFuture<NodeResult> f : futures) {
            results.add(f.join());
        }
        return results;
    }

    /**
     * v2 条件分支：跑一个 super-step 的公共主体（execute 与 recoverAndExecute 共用，review P1 收敛）。
     * 只跑可达节点；崩溃层（{@code crashLayerStep}）再剔除 {@code excludedNodeIds}（已完成节点，输出已重放）。
     * 顺序：过滤可达 → 标 SKIPPED → 并行执行 → 路由决策持久化 → barrier 落盘（路由先于 barrier，崩溃窗口不丢）。
     */
    private boolean runStep(SuperStep step, Set<String> active, Set<String> nextActive, Set<String> excludedNodeIds,
                            int crashLayerStep, int round, Map<String, Object> inputs, WorkflowContext context, DAGraph dag,
                            WorkflowDefinition def, ChannelReducer reducer, CheckpointManager cp, String workflowId,
                            NodeExecutor nodeExecutor, ExecutorService executor, Instant workflowStart, ExecutionTrace trace,
                            WorkflowBudget budget, Set<String> onErrorActivated, List<String> takenEdges) {
        // 工作流总超时：super-step 间检查（超时则 abort，不推进下游）
        if (timeoutPolicy != null && timeoutPolicy.isWorkflowExceeded(workflowStart)) {
            throw new WorkflowExecutionException(step.index(),
                    List.of(new TimeoutException("workflow total timeout exceeded")));
        }
        // 只跑本层可达节点；崩溃层再剔除已完成节点（输出已重放，不重跑防 LLM 重复计费）
        List<String> activeInStep = step.nodeIds().stream().filter(active::contains).toList();
        SuperStep stepToRun = new SuperStep(step.index(), activeInStep.stream()
                .filter(id -> step.index() != crashLayerStep || !excludedNodeIds.contains(id))
                .toList());
        // v2 可观测：被剪枝（不可达）节点标记 SKIPPED
        markSkippedNodes(trace, step, activeInStep, dag);
        WorkflowContext snapshot = context.readOnlySnapshot();
        List<NodeResult> results = runSuperStep(stepToRun, dag, snapshot, nodeExecutor, cp, workflowId, round,
                executor, inputs, workflowStart, trace, budget);
        // U10 后续 #10：记录本 super-step 各节点所属层号（含被剪枝节点，供 UI 真实拓扑分组）
        recordSuperStepTrace(trace, step);
        // v2 循环：记录节点所属迭代轮次（供 trace 区分跨轮重复执行的同 nodeId）
        recordNodeRounds(trace, step, round);
        BarrierResult barrier = applyBarrier(step, results, context, dag, def, reducer, cp, workflowId, round,
                onErrorActivated, trace, takenEdges);
        // U4 审批暂停：不激活下游、不写路由决策/barrier（该层未完成，恢复时从层 0 续跑）
        if (barrier.paused()) {
            return true;
        }
        active.addAll(barrier.onErrorTargets());
        // v2 条件分支：成功节点计算路由决策、激活后继（无分支命中 → Fatal → 工作流 FAILED）
        updateReachability(results, active, nextActive, def, step.index(), trace, takenEdges, context, inputs);
        // 先持久化路由决策、再写 barrier（崩溃窗口内路由已落盘，恢复不丢下游）
        cp.saveRoutingDecisions(workflowId, round, step.index(), List.copyOf(takenEdges));
        cp.saveBarrier(workflowId, round, step.index(), context);
        return false;
    }

    /** Barrier 扫描结果：on_error 激活目标 + 是否审批暂停（U4 HITL）。 */
    private record BarrierResult(List<String> onErrorTargets, boolean paused) {
    }

    /** Barrier 阶段：按声明序合并成功节点输出；on_error 节点失败转兜底（激活目标、不 abort），
     *  其余失败聚合抛出。返回本层 on_error 激活的目标节点（调用方加入可达集）。
     *  U4 HITL：遇 {@link NodeResult.ApprovalRequired} → 审批优先暂停——兄弟 Success 输出已并入
     *  context（快照用）、持久化审批单 + 上下文快照、置 {@code AWAITING_APPROVAL}，不 abort、不写 barrier。 */
    private BarrierResult applyBarrier(SuperStep step, List<NodeResult> results, WorkflowContext context,
                                       DAGraph dag, WorkflowDefinition def, ChannelReducer reducer,
                                       CheckpointManager cp, String workflowId, int round, Set<String> onErrorActivated,
                                       ExecutionTrace trace, List<String> takenEdges) {
        List<Throwable> fatalFailures = new ArrayList<>();
        List<String> onErrorTargets = new ArrayList<>();
        NodeResult.ApprovalRequired approval = null;
        // results 已按声明序（提交序），保证 Reducer 合并确定
        for (NodeResult r : results) {
            if (r instanceof NodeResult.Success s) {
                applyOutput(context, dag.node(s.nodeId()), s.output(), def, reducer);
            } else if (r instanceof NodeResult.ApprovalRequired ar) {
                // 审批优先：首个审批请求为准；同层兄弟 Success 仍并入 context（快照含兄弟输出）
                if (approval == null) {
                    approval = ar;
                }
            } else if (r instanceof NodeResult.Failure f) {
                NodeDefinition node = dag.node(f.nodeId());
                // on_error 兜底：终态失败转跳转；on_error 目标自身失败不二次跳转（走致命失败）
                if (node.onError() != null && !node.onError().isBlank()
                        && !onErrorActivated.contains(f.nodeId())) {
                    onErrorTargets.add(node.onError());
                    onErrorActivated.add(node.onError());
                    recordRouting(f.nodeId(), node.onError(), trace, takenEdges);
                } else {
                    fatalFailures.add(f.error());
                }
            }
        }
        // U4 审批暂停：即使同层有 Failure 也以审批为准（兄弟输出已入 context，恢复时不重跑）
        if (approval != null) {
            ApprovalRequest request = ApprovalRequest.pending(workflowId, approval.nodeId(), round,
                    step.index(), approval.description(), approval.requestPayload(), flattenContext(context));
            try {
                cp.saveApprovalRequest(workflowId, request);
            } catch (RuntimeException e) {
                // 审批单持久化失败 = 暂停无法恢复，视为致命（而非静默降级）
                throw new WorkflowExecutionException(step.index(), List.of(e));
            }
            try {
                cp.updateStatus(workflowId, WorkflowStatus.AWAITING_APPROVAL);
            } catch (RuntimeException se) {
                log.warn("暂停时 updateStatus(AWAITING_APPROVAL) 失败 wf={}: {}", workflowId, se.toString());
            }
            if (metrics != null) {
                metrics.recordApprovalEvent(AgentFlowMetrics.STATUS_APPROVAL_PENDING);
            }
            return new BarrierResult(onErrorTargets, true);
        }
        if (!fatalFailures.isEmpty()) {
            // U4 ErrorHandler：转 FAILED 前 context 补偿（写 errorHandled=true 等；
            // 写全局 context，非 agent 只读快照，保 BSP 互不可见）
            if (errorHandler != null) {
                for (Throwable cause : fatalFailures) {
                    try {
                        errorHandler.handle(context, cause);
                    } catch (RuntimeException he) {
                        log.warn("ErrorHandler 抛异常，忽略（best-effort 补偿）: {}", he.toString());
                    }
                }
            }
            // 失败 super-step 不写 barrier checkpoint——KTD-3：barrier checkpoint 记录"已完成"super-step，
            // 失败层未完成；U5 Recovery 查 nextSuperStep 的节点级 COMPLETED 输出重跑失败节点
            throw new WorkflowExecutionException(step.index(), fatalFailures);
        }
        // v2 注：saveBarrier 移到调用方（execute/recoverAndExecute）在 saveRoutingDecisions 之后执行，
        // 保证路由决策先于 barrier 落盘（review P1：崩溃窗口内路由不丢）
        return new BarrierResult(onErrorTargets, false);
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

    /** v2 条件分支：成功节点计算路由决策、激活后继节点 + 记录路由决策；无分支命中抛 WorkflowExecutionException。 */
    private void updateReachability(List<NodeResult> results, Set<String> active, Set<String> nextActive,
                                    WorkflowDefinition def, int stepIndex, ExecutionTrace trace,
                                    List<String> takenEdges, WorkflowContext context, Map<String, Object> inputs) {
        for (NodeResult r : results) {
            if (r instanceof NodeResult.Success s) {
                try {
                    for (EdgeDefinition taken : resolveTakenEdges(s.nodeId(), s.output(), context, inputs, def)) {
                        if (taken.loop()) {
                            nextActive.add(taken.to()); // 回边命中 → 目标进下一轮（不终结本轮前向传播）
                        } else {
                            active.add(taken.to()); // 前向/退出边 → 后继进本轮
                        }
                        recordRouting(s.nodeId(), taken.to(), trace, takenEdges);
                    }
                } catch (FatalException fe) {
                    throw new WorkflowExecutionException(stepIndex, List.of(fe));
                }
            }
        }
    }

    /** v2 条件分支：记录路由决策到 takenEdges（持久化）与 trace（可观测）。 */
    private void recordRouting(String from, String to, ExecutionTrace trace, List<String> takenEdges) {
        takenEdges.add(EdgeDefinition.edgeKey(from, to));
        if (trace != null) {
            trace.recordRoutingDecision(from, to);
        }
    }

    /** 提取工作流失败原因摘要（第一个 failure 的简短 message，供 DiagnosisService 识别「迭代超限」等）。 */
    private static String describeFailure(WorkflowExecutionException we) {
        if (we.failures() == null || we.failures().isEmpty()) {
            return we.getMessage();
        }
        Throwable first = we.failures().get(0);
        return first.getMessage() != null ? first.getMessage() : first.getClass().getSimpleName();
    }

    /** v2 on_error：从已走边重建「经 on_error 激活的目标集合」，恢复期级联守卫（review P1）。 */
    private Set<String> rebuildOnErrorActivated(WorkflowDefinition def, List<String> takenEdges) {
        Set<String> result = new HashSet<>();
        if (def.nodes() == null) {
            return result;
        }
        for (NodeDefinition n : def.nodes()) {
            if (n.onError() != null && !n.onError().isBlank()
                    && takenEdges.contains(EdgeDefinition.edgeKey(n.id(), n.onError()))) {
                result.add(n.onError());
            }
        }
        return result;
    }

    /**
     * v2 条件分支：从源节点 BFS 计算可达集（恢复期用）。
     * 纯 fan-out 节点（无 when 边）→ 所有出边总是走；路由节点（有 when 边）→ 仅已走边（takenEdges）；
     * on_error 隐式边按已走边判断。
     */
    private Set<String> computeReachable(List<String> sources, DAGraph dag, WorkflowDefinition def,
                                         List<String> takenEdges) {
        Set<String> active = new HashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>(sources);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (!active.add(node)) {
                continue;
            }
            NodeDefinition nd = dag.node(node);
            List<EdgeDefinition> outgoing = def.edges() == null ? List.of()
                    : def.edges().stream().filter(e -> e.from().equals(node)).toList();
            boolean hasWhen = outgoing.stream().anyMatch(e -> e.when() != null && !e.when().isBlank());
            // on_error 已走（该节点失败转兜底）→ 正常出边被剪枝，按路由节点处理（不 fan-out）
            boolean onErrorTaken = nd.onError() != null && !nd.onError().isBlank()
                    && takenEdges.contains(EdgeDefinition.edgeKey(node, nd.onError()));
            boolean fanOut = !hasWhen && !onErrorTaken;
            for (EdgeDefinition e : outgoing) {
                boolean taken = fanOut || takenEdges.contains(EdgeDefinition.edgeKey(node, e.to()));
                if (taken) {
                    queue.add(e.to());
                }
            }
            if (onErrorTaken) {
                queue.add(nd.onError());
            }
        }
        return active;
    }

    /** v2 可观测：把本层被剪枝（不可达）节点标记为 SKIPPED（trace 记录）。 */
    private void markSkippedNodes(ExecutionTrace trace, SuperStep step, List<String> activeIds, DAGraph dag) {
        if (trace == null) {
            return;
        }
        for (String id : step.nodeIds()) {
            if (!activeIds.contains(id)) {
                trace.addSkippedNode(id, dag.node(id).agent());
            }
        }
    }

    /**
     * v2 条件分支/循环：计算节点完成后的路由决策，返回「已走」的边（EdgeDefinition，含 loop 标记）。
     * 纯 fan-out（无 when 边）→ 所有出边都走（v1 语义）；路由节点（有 when 边）→ 按声明序取第一条 true，
     * 否则默认边；无 when 命中且无默认边 → 抛 FatalException（无分支命中）。调用方按 edge.loop() 分派。
     */
    private List<EdgeDefinition> resolveTakenEdges(String nodeId, AgentOutput output, WorkflowContext context,
                                                   Map<String, Object> inputs, WorkflowDefinition def)
            throws FatalException {
        List<EdgeDefinition> outgoing = def.edges() == null ? List.of()
                : def.edges().stream().filter(e -> e.from().equals(nodeId)).toList();
        List<EdgeDefinition> whenEdges = new ArrayList<>();
        List<EdgeDefinition> defaultEdges = new ArrayList<>();
        for (EdgeDefinition e : outgoing) {
            if (e.when() != null && !e.when().isBlank()) {
                whenEdges.add(e);
            } else {
                defaultEdges.add(e);
            }
        }
        if (whenEdges.isEmpty()) {
            return outgoing; // fan-out：所有出边（非 loop，fan-out 节点无 when 边）
        }
        Map<String, Object> outputMap = outputMap(output);
        Map<String, Object> contextMap = flattenContext(context);
        for (EdgeDefinition e : whenEdges) {
            if (predicateEvaluator.evaluate(e.when(), outputMap, contextMap, inputs)) {
                return List.of(e); // 命中的边（可能是 loop 回边）
            }
        }
        if (defaultEdges.size() == 1) {
            return List.of(defaultEdges.get(0));
        }
        throw new FatalException("无分支命中: 节点 " + nodeId + " 的 when 谓词均未命中且无默认边");
    }

    /** 谓词求值的 output 根对象：structuredOutput 优先（键合并）+ content 降级为 content 键。 */
    private static Map<String, Object> outputMap(AgentOutput output) {
        Map<String, Object> map = new HashMap<>();
        if (output.structuredOutput() != null && !output.structuredOutput().isEmpty()) {
            map.putAll(output.structuredOutput());
        }
        if (output.content() != null) {
            map.put("content", output.content());
        }
        return map;
    }

    /** 谓词求值的 context 根对象：channel 名 → 值（对齐 SpelPromptResolver 的 channel 扁平视图）。 */
    private static Map<String, Object> flattenContext(WorkflowContext context) {
        Map<String, Object> flat = new HashMap<>();
        if (context != null) {
            context.values().forEach((k, cv) -> flat.put(k, cv.value()));
        }
        return flat;
    }
}
