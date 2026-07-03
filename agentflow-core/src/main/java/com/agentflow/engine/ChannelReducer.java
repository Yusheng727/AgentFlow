package com.agentflow.engine;

import com.agentflow.dsl.Reducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 按 {@link Reducer} 策略合并 channel 并发写入（R6）。
 *
 * <p>调用方（{@link BspEngine} barrier 阶段）按节点声明序逐个 merge，保证确定性
 * （U2 测试场景 4：同时写同一 channel，Reducer 按确定顺序合并）。
 *
 * <ul>
 *   <li>OVERWRITE — 后写覆盖先写（默认）</li>
 *   <li>CONCAT — 顺序拼接（List 拼 List / 字符串拼接 / 标量提升为 List）</li>
 *   <li>MAX — 取较大值（数值）</li>
 *   <li>CUSTOM — 委托注册的 {@code Function<List<Object>, Object>}（按 channel 名查）</li>
 * </ul>
 */
public final class ChannelReducer {

    private final Map<String, Function<List<Object>, Object>> customReducers;

    public ChannelReducer() {
        this(Map.of());
    }

    public ChannelReducer(Map<String, Function<List<Object>, Object>> customReducers) {
        this.customReducers = customReducers == null ? Map.of() : customReducers;
    }

    /**
     * 合并单次写入到当前值。
     *
     * @param channel channel 名（CUSTOM 用）
     * @param current 当前值（可能 null，表示首次写入）
     * @param write   本次写入值
     * @param reducer 合并策略
     * @return 合并后的值
     */
    public Object merge(String channel, Object current, Object write, Reducer reducer) {
        Objects.requireNonNull(reducer, "reducer");
        return switch (reducer) {
            case OVERWRITE -> write;
            case CONCAT -> concat(current, write);
            case MAX -> max(current, write);
            case CUSTOM -> customMerge(channel, current, write);
        };
    }

    private static Object concat(Object current, Object write) {
        if (current == null) {
            return write;
        }
        // 双 List → 拼接
        if (current instanceof List<?> curList && write instanceof List<?> writeList) {
            List<Object> merged = new ArrayList<>(curList.size() + writeList.size());
            merged.addAll(curList);
            merged.addAll(writeList);
            return merged;
        }
        // 双字符串 → 拼接
        if (current instanceof String curStr && write instanceof String writeStr) {
            return curStr + writeStr;
        }
        // 标量 + List → [current, ...write]
        if (write instanceof List<?> writeList) {
            List<Object> merged = new ArrayList<>(writeList.size() + 1);
            merged.add(current);
            merged.addAll(writeList);
            return merged;
        }
        // 其他 → 退化为 List[current, write]
        List<Object> merged = new ArrayList<>(2);
        merged.add(current);
        merged.add(write);
        return merged;
    }

    private static Object max(Object current, Object write) {
        if (current == null) {
            return write;
        }
        if (current instanceof Number curNum && write instanceof Number writeNum) {
            return curNum.doubleValue() >= writeNum.doubleValue() ? curNum : writeNum;
        }
        // 非数值 → 不定义顺序，保留后写（与 OVERWRITE 一致）
        return write;
    }

    private Object customMerge(String channel, Object current, Object write) {
        Function<List<Object>, Object> reducerFn = customReducers.get(channel);
        if (reducerFn == null) {
            throw new IllegalStateException(
                    "channel " + channel + " 声明 CUSTOM reducer 但未注册自定义函数");
        }
        List<Object> vals = new ArrayList<>(2);
        if (current != null) {
            vals.add(current);
        }
        vals.add(write);
        return reducerFn.apply(vals);
    }
}
