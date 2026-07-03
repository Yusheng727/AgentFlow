package com.agentflow.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工作流执行上下文：channel → {@link ChannelValue} 的存储 + 只读快照。
 *
 * <p>BSP 语义（KTD-1）：super-step 开始时调用 {@link #readOnlySnapshot()} 给每个节点一个只读视图，
 * 节点间互不可见、不可变——barrier 天然避免并行写入竞态。节点尝试 {@code put} 抛
 * {@link UnsupportedOperationException}（U2 测试场景 7）。barrier 阶段由引擎按 Reducer 合并写入全局 context。
 *
 * <p>本类线程安全性的边界：全局 context 的写入只发生在 barrier 阶段（单线程串行合并），
 * 不需要加锁；只读快照本身不可变。U5 的并发节点级 checkpoint 写库由 CheckpointManager 自己保证。
 */
public final class WorkflowContext {

    private final Map<String, ChannelValue> values;
    private final boolean readOnly;

    public WorkflowContext() {
        this(new HashMap<>(), false);
    }

    /** 从启动入参构造（channel 名 → 值）。 */
    public WorkflowContext(Map<String, Object> initial) {
        this(new HashMap<>(), false);
        if (initial != null) {
            initial.forEach((k, v) -> this.values.put(k, ChannelValue.initial(v)));
        }
    }

    private WorkflowContext(Map<String, ChannelValue> values, boolean readOnly) {
        this.values = values;
        this.readOnly = readOnly;
    }

    /** 取 channel 的 ChannelValue（可能 null）。 */
    public ChannelValue get(String channel) {
        return values.get(Objects.requireNonNull(channel, "channel"));
    }

    /** 取 channel 的原始值（可能 null）。 */
    public Object getValue(String channel) {
        ChannelValue cv = values.get(channel);
        return cv == null ? null : cv.value();
    }

    /** 是否包含某 channel。 */
    public boolean contains(String channel) {
        return values.containsKey(channel);
    }

    /**
     * 写入 channel（版本 +1）。仅可写上下文可用；只读快照调用抛
     * {@link UnsupportedOperationException}。
     */
    public void put(String channel, Object value) {
        if (readOnly) {
            throw new UnsupportedOperationException(
                    "context 为只读快照，不可写入（BSP super-step 内节点互不可见）: " + channel);
        }
        ChannelValue current = values.get(channel);
        values.put(channel, current == null ? ChannelValue.initial(value) : current.next(value));
    }

    /** 全部 channel 快照（不可变视图）。 */
    public Map<String, ChannelValue> values() {
        return Collections.unmodifiableMap(values);
    }

    /**
     * 生成只读快照（KTD-1）。基于 {@code Map.copyOf}，任何写入尝试抛
     * {@link UnsupportedOperationException}。供同 super-step 内并行节点读取。
     *
     * <p><b>隔离范围（已知限制）</b>：{@code Map.copyOf} 只保证<b>结构级</b>不可变（不能 put/remove），
     * channel 值对象本身是共享引用。Agent 不得向 channel 写入可变对象（如 ArrayList/HashMap），
     * 否则节点可通过快照 mutate 全局 context、破坏 BSP 互不可见语义。v1 channel 值应为不可变类型
     * （String / record / 不可变集合）；ChannelReducer 的 CONCAT 已新建 List 缓解常见路径。
     * 深度拷贝留给 v1.1（性能权衡，见审查残留风险）。
     */
    public WorkflowContext readOnlySnapshot() {
        return new WorkflowContext(Map.copyOf(values), true);
    }
}
