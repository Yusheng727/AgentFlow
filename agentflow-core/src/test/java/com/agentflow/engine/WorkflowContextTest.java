package com.agentflow.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WorkflowContext 单测：读写 / 版本自增 / 只读快照不可变。 */
class WorkflowContextTest {

    @Test
    @DisplayName("put/get/getValue/contains 基本读写")
    void basicReadWrite() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.put("X", "a");
        assertThat(ctx.contains("X")).isTrue();
        assertThat(ctx.getValue("X")).isEqualTo("a");
        assertThat(ctx.get("X").version()).isZero();
        assertThat(ctx.contains("Y")).isFalse();
        assertThat(ctx.getValue("Y")).isNull();
    }

    @Test
    @DisplayName("重复 put 同 channel → 版本自增")
    void versionIncrements() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.put("X", "a");
        ctx.put("X", "b");
        ctx.put("X", "c");
        assertThat(ctx.getValue("X")).isEqualTo("c");
        assertThat(ctx.get("X").version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("从启动入参构造")
    void fromInputs() {
        WorkflowContext ctx = new WorkflowContext(Map.of("supplier", "Acme", "score", 88));
        assertThat(ctx.getValue("supplier")).isEqualTo("Acme");
        assertThat(ctx.get("score").version()).isZero();
    }

    @Test
    @DisplayName("只读快照：put 抛 UnsupportedOperationException")
    void snapshotIsImmutable() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.put("X", "a");
        WorkflowContext snap = ctx.readOnlySnapshot();
        assertThat(snap.getValue("X")).isEqualTo("a");
        assertThatThrownBy(() -> snap.put("X", "b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("快照后全局写入不影响已生成的快照（互不可见）")
    void snapshotIsIsolatedFromLaterWrites() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.put("X", "a");
        WorkflowContext snap = ctx.readOnlySnapshot();
        ctx.put("X", "b");
        // 快照仍为生成时刻的值
        assertThat(snap.getValue("X")).isEqualTo("a");
        // 全局已是新值
        assertThat(ctx.getValue("X")).isEqualTo("b");
    }

    @Test
    @DisplayName("values() 返回不可变视图")
    void valuesViewIsUnmodifiable() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.put("X", "a");
        assertThatThrownBy(() -> ctx.values().put("Y", ChannelValue.initial("b")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
