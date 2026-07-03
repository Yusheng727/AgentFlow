package com.agentflow.engine;

import com.agentflow.dsl.Reducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ChannelReducer 单测：四种策略 + 边界。 */
class ChannelReducerTest {

    private final ChannelReducer reducer = new ChannelReducer();

    // OVERWRITE
    @Test
    @DisplayName("OVERWRITE：后写覆盖先写（含 null current）")
    void overwrite() {
        assertThat(reducer.merge("X", null, "b", Reducer.OVERWRITE)).isEqualTo("b");
        assertThat(reducer.merge("X", "a", "b", Reducer.OVERWRITE)).isEqualTo("b");
    }

    // CONCAT
    @Test
    @DisplayName("CONCAT：字符串拼接 / List 拼接 / 标量+List / null current 原样返回")
    void concat() {
        assertThat(reducer.merge("Y", null, "solo", Reducer.CONCAT)).isEqualTo("solo");
        assertThat(reducer.merge("Y", "a", "b", Reducer.CONCAT)).isEqualTo("ab");
        assertThat(reducer.merge("Y", List.of("a"), List.of("b"), Reducer.CONCAT))
                .isEqualTo(List.of("a", "b"));
        // 标量 + List → [current, ...write]
        assertThat(reducer.merge("Y", "a", List.of("b", "c"), Reducer.CONCAT))
                .isEqualTo(List.of("a", "b", "c"));
        // 双标量 → List[current, write]
        assertThat(reducer.merge("Y", 1, 2, Reducer.CONCAT)).isEqualTo(List.of(1, 2));
    }

    // MAX
    @Test
    @DisplayName("MAX：数值取大 / null current 原样 / 非数值退化为后写")
    void max() {
        assertThat(reducer.merge("Z", null, 5, Reducer.MAX)).isEqualTo(5);
        assertThat(reducer.merge("Z", 1, 2, Reducer.MAX)).isEqualTo(2);
        assertThat(reducer.merge("Z", 9, 3, Reducer.MAX)).isEqualTo(9);
        // 双精度
        assertThat(reducer.merge("Z", 1.5, 2.5, Reducer.MAX)).isEqualTo(2.5);
        // 非数值 → 后写
        assertThat(reducer.merge("Z", "a", "b", Reducer.MAX)).isEqualTo("b");
    }

    // CUSTOM
    @Test
    @DisplayName("CUSTOM：注册的 reducer 函数被调用（含 null current）")
    void customRegistered() {
        ChannelReducer r = new ChannelReducer(Map.of("W",
                vals -> String.join("+", vals.stream().map(String::valueOf).toList())));
        assertThat(r.merge("W", "a", "b", Reducer.CUSTOM)).isEqualTo("a+b");
        // null current 被过滤
        assertThat(r.merge("W", null, "solo", Reducer.CUSTOM)).isEqualTo("solo");
    }

    @Test
    @DisplayName("CUSTOM：未注册 reducer → IllegalStateException")
    void customNotRegisteredThrows() {
        assertThatThrownBy(() -> reducer.merge("missing", "a", "b", Reducer.CUSTOM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("CONCAT：List current + 标量 write → 扁平追加（不嵌套）")
    void concatListPlusScalarFlattens() {
        // 先 List+List 得到一个 List，再 + 标量
        Object first = reducer.merge("Y", List.of("a", "b"), List.of("c"), Reducer.CONCAT);
        assertThat(first).isEqualTo(List.of("a", "b", "c"));
        // List current + 标量 write
        assertThat(reducer.merge("Y", List.of("a", "b"), "c", Reducer.CONCAT))
                .isEqualTo(List.of("a", "b", "c"));
        // 跨 super-step：首层 List + 下层标量
        Object acc = reducer.merge("Y", null, List.of(1, 2), Reducer.CONCAT);
        acc = reducer.merge("Y", acc, 3, Reducer.CONCAT);
        assertThat(acc).isEqualTo(List.of(1, 2, 3));
    }

    @Test
    @DisplayName("MAX：Long > 2^53 精确比较（不丢精度）")
    void maxLargeLongExact() {
        long a = 9_007_199_254_740_993L; // 2^53 + 1，double 无法精确表示
        long b = 9_007_199_254_740_992L; // 2^53
        assertThat(reducer.merge("Z", a, b, Reducer.MAX)).isEqualTo(a);
        assertThat(reducer.merge("Z", b, a, Reducer.MAX)).isEqualTo(a);
        // Integer 同类型精确
        assertThat(reducer.merge("Z", 1, 2, Reducer.MAX)).isEqualTo(2);
        // 混合数值仍走 doubleValue
        assertThat(reducer.merge("Z", 1.5, 2, Reducer.MAX)).isEqualTo(2);
    }
}
