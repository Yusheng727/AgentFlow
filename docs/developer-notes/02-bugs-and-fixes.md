# 实现过程中的 Bug 与修复

> 按 Unit 记录开发中真实踩到的坑：根因 → 发现过程 → 修复 → 量化结果。
> **面试用途**：「遇到最难的问题」「线上 bug 怎么排查」「怎么保证代码质量」类问题的弹药。
> 每条遵循 Situation→Task→Action→Result，深挖细节备查。

---

## U5 — 两级 Checkpoint 持久化 + Recovery

### Bug-1: `jackson-datatype-jsr310` 依赖缺失导致编译失败

**Situation**: U5 代码写完后首次跑 `mvn verify`（之前环境无 Maven，CLAUDE.md 标注「待 verify」）。
**Task**: 让 U5 分支能编译通过。
**Action**:
- 错误：`PostgresCheckpointManager.java:11` import `com.fasterxml.jackson.datatype.jsr310.JavaTimeModule`，但 `agentflow-core/pom.xml` 只声明了 `jackson-databind` 和 `jackson-dataformat-yaml`，缺 `jackson-datatype-jsr310`。
- 根因：`BarrierCheckpoint` 和 `NodeOutputStore` 两个 record 都有 `Instant completedAt` 字段，`defaultJsonMapper()` 注册 `JavaTimeModule` 才能序列化 JSR-310 时间类型，但 pom 没引这个包。
- 修复：core pom 加 `jackson-datatype-jsr310` 依赖（Spring Boot 4.1 BOM 统一版本，无需写 version）。

**Result**: 编译通过。**教训**：写代码时 IDE 可能隐式解析了依赖（或从其他模块传递依赖），但 maven reactor 编译会暴露——`mvn verify` 必须早跑。

---

### Bug-2: `BspEngineTest.RecordingCheckpoint` mock 未实现接口扩展方法

**Situation**: Bug-1 修完后，测试编译阶段失败。
**Task**: 让测试编译通过。
**Action**:
- 错误：`BspEngineTest.java:476` 的内部 mock `RecordingCheckpoint implements CheckpointManager`，只实现了 U2 时代的 2 个方法（`saveNodeOutput`/`saveBarrier`），U5 给接口加了 4 个新方法（`findLatestBarrier`/`findCompletedNodes`/`initWorkflow`/`updateStatus`），mock 没跟上 → 编译报「未实现抽象方法」。
- 根因：U5 扩展了 U2 引入的 SPI 接口，但 U2 的测试 mock 没同步更新。这是「接口扩展」的经典副作用——所有 implementors 都要改。
- 修复：给 `RecordingCheckpoint` 补 4 个 noop 实现（对齐 `NoopCheckpointManager` 模板）。

**Result**: 测试编译通过。**教训**：扩展接口时全局 grep 所有 `implements`，别只改生产实现。

---

### Bug-3: `CheckpointManagerTest.nestedMapAndNumberTypes` 断言类型不匹配

**Situation**: Bug-2 修完后，124 个测试跑起来，1 个失败。
**Task**: 定位测试失败根因。
**Action**:
- 失败信息：`{"count"=100L} expected: ["count"=100]`——`Long.equals(Integer)` 恒为 false。
- 根因（systematic-debugging）：测试造数据 `Map.of("count", 100L)` 是 `Long`；`InMemoryCheckpointManager.saveNodeOutput` **不做序列化**，直接存对象引用，存取后类型不变仍是 `100L`。但断言 `containsEntry("count", 100)` 用 `Integer` 字面量，跨类型 `equals` 必然失败。
- 这是**测试本身的 bug**，不是生产代码缺陷。修法：断言改成 `100L` 匹配造数据。
- 深挖：如果将来改用 `PostgresCheckpointManager`（走 JSONB 往返），Jackson 默认把 JSON 整数反序列化成 `Integer`（不是 `Long`），那时断言又得变——这是 InMemory vs Postgres 行为差异的伏笔（见 Review Finding ADV-6）。

**Result**: 测试全绿。**教训**：断言的类型要和造数据的类型严格一致；Long/Integer 跨类型比较是 AssertJ `containsEntry` 的常见坑。

---

### Bug-4（P0）: 崩溃层 COMPLETED 节点的 channel 输出丢失 —— ADV-1

**Situation**: ce-code-review adversarial reviewer 构造的攻击场景。
**Task**: 修复 Recovery 语义缺陷，否则合并阻塞。
**Action**:
- **根因链**（最严重的发现）：
  1. `BspEngine` 第 199 行：每个节点成功后**立即** `saveNodeOutput`（barrier 之前）
  2. `BspEngine` 第 273 行：`saveBarrier` 在**所有节点完成且无失败后**才调用
  3. **崩溃窗口**：step N 的部分节点已 saveNodeOutput（COMPLETED 落盘）→ crash → barrier 没写
  4. Recovery：`latestBarrier=N-1`，`nextSuperStep=N`，`findCompletedNodes(N)` 返回这些 phantom COMPLETED 节点
  5. 引擎**跳过**这些节点（不重跑，符合 R3 防重复计费）
  6. **但**这些节点的 `channelWrites` 从未合并进 barrier（`applyOutput` 在内存 context 里，crash 丢失）→ `channelSnapshot`（来自 N-1 barrier）**缺这些输出**
  7. step N+1 节点 SpEL 引用这些 channel → null/陈旧值 → 错误 LLM 输入

- **关键洞察**：plan 的 off-by-one 修复（查崩溃层本身而非已 barrier 层）解决了「节点被重复执行」的问题，但**没解决「跳过节点的输出丢失」问题**。R3 不只是防重复计费，还要保证恢复后 channel 状态正确。

- **修复**：
  - `ExecutionState` 加 `replayOutputs` 字段（崩溃层 COMPLETED 节点的 `AgentOutput` 列表）
  - `RecoveryProtocol.recover()` 收集这些节点的 output（不只是 nodeId）
  - `BspEngine` 新增 `recoverAndExecute` 入口（plan U5 点名但 v1 原未实现）：恢复时把 `replayOutputs` 按 Reducer 重放进 context，再跳过这些节点不重跑

**Result**: 4 个 reviewer 独立确认（adversarial + correctness + reliability + testing）。新增 `BspEngineRecoveryTest.recoverReplaysCrashLayerOutput` 端到端验证：崩溃层 B 已 COMPLETED，恢复后 B 不重跑但下游 C 能读到 B 的 channel 输出。

**面试讲法**：「我发现 checkpoint 恢复有个语义漏洞——崩溃层已完成节点的输出虽然持久化了，但因为没进 barrier，恢复时跳过这些节点会导致下游读到陈旧 channel。这不是普通的 bug，是恢复协议设计层面的缺陷：off-by-one 修复只解决了重复执行，没解决输出丢失。我加了 replayOutputs 机制，恢复时把崩溃层输出重放进 context。」

---

### Bug-5（P0）: timeout abort 的 stray COMPLETED 防护未实现 —— ADV-2

**Situation**: 同 Bug-4，adversarial reviewer 第二个攻击场景。
**Task**: 兑现 Javadoc 承诺的 stray 防护。
**Action**:
- **根因链**：
  1. `BspEngine` 第 220-221 行注释自己警告了：timeout 后在飞 VT 仍可能完成并 `saveNodeOutput` 写出 stray COMPLETED 到已 abort 的 super-step
  2. `RecoveryProtocol` Javadoc 第 32-34 行承诺「先查工作流状态，FAILED 时忽略 stray COMPLETED」
  3. **但** `recover()` 实现根本没查状态（直接 `findCompletedNodes` 返回所有 COMPLETED）
  4. **更糟**：`BspEngine` abort 路径（第 271 行抛异常）也没调 `updateStatus(FAILED)`——所以即使 Recovery 想查状态，状态还是 RUNNING/PENDING
  5. 结果：stray COMPLETED 被当成有效，跳过这些节点 → channel 输出缺失 → 同 Bug-4 的错误

- **修复**（两层）：
  - `CheckpointManager` 加 `findStatus` 方法（InMemory/Postgres/Noop 实现）
  - `BspEngine` abort 路径 `catch WorkflowExecutionException` → `updateStatus(FAILED)`（best-effort，不掩盖原始异常）
  - `RecoveryProtocol.recover()` 开头查 `findStatus`：若 FAILED，判定崩溃层可能含 stray，`completedNodeIds` 与 `replayOutputs` 均置空，崩溃层整体重跑（宁可 LLM 重复计费——R3 软约束——不换错误结果——正确性硬约束）

- **设计权衡**：FAILED 时整体重跑 vs 精确鉴别 stray。选前者因为：stray 记录和合法 COMPLETED 在数据层无法区分（都是 status=COMPLETED + output 非空），唯一区分信号是「工作流是否被 abort」。整体重跑牺牲少量 LLM 成本换正确性，符合 R3 的本质（防重复计费是经济约束，不是正确性约束）。

**Result**: 4 reviewer 独立确认。新增 `RecoveryProtocolTest.abortedWorkflowIgnoresStrayCompleted` + `BspEngineRecoveryTest.abortedWorkflowRerunsCrashLayer` 验证。

**面试讲法**：「代码 Javadoc 承诺了一个防护机制但实际没实现——这是文档与代码不一致的典型。我两层修复：引擎 abort 时显式标记 FAILED，Recovery 查到 FAILED 就整体重跑崩溃层。这里有个设计决策：stray 记录和正常 COMPLETED 在数据层无法区分，我选择牺牲 LLM 成本（R3 经济约束）换正确性。」

---

### 设计缺陷修复过程中的 Bug: `skipCompleted` 执行后过滤 vs 执行前剔除

**Situation**: 写 `recoverAndExecute` 时第一版用「执行后过滤」跳过已完成节点。
**Task**: 让崩溃层已完成节点不被重跑。
**Action**:
- 第一版：`runSuperStep` 正常执行所有节点，之后 `skipCompleted(results, completedNodeIds)` 过滤结果。
- 测试失败：`bRuns.get()` 期望 0 实际 1——B 被重跑了。
- 根因：`skipCompleted` 在 results 之后过滤，但那时 agent 已经执行了（`bRuns.incrementAndGet()` 已触发）。过滤结果不能阻止执行。
- 修复：改成「执行前剔除」——崩溃层构造一个不含 `completedNodeIds` 的 `SuperStep` 副本传给 `runSuperStep`，这些节点根本不进 agent。
- 顺带删掉 dead code `skipCompleted` 方法 + 未用的 `Set` import。

**Result**: 测试通过。**教训**：「跳过」要在执行前，不是执行后过滤——后者只能改结果不能阻止副作用（LLM 调用、计数器、IO 都已发生）。
