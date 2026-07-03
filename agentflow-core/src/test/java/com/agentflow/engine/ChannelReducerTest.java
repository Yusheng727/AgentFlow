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
}
