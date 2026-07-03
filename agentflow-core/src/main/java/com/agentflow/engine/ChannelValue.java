package com.agentflow.engine;

/**
 * Channel 值 + 版本号。
 *
 * <p>版本号每次写入自增，用于冲突检测与将来的乐观并发（KTD-3 channel versioning）。
 * U5 的 barrier checkpoint 会把 (channel → value, version) 序列化到 {@code workflow_checkpoints.channel_versions}。
 */
public record ChannelValue(Object value, long version) {

    /** 首次写入：version = 0。 */
    public static ChannelValue initial(Object value) {
        return new ChannelValue(value, 0);
    }

    /** 后续写入：version + 1。 */
    public ChannelValue next(Object value) {
        return new ChannelValue(value, this.version + 1);
    }
}
