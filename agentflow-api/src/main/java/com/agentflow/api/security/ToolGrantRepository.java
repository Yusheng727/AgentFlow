package com.agentflow.api.security;

import java.util.Set;

/**
 * 工具授权仓储（v1.1 R21）：{@code caller_tool_grants} 表的读写抽象。
 *
 * <p>{@link CallerToolAllowlist} 持有的授权是「config 静态 + 本仓储动态」相加；本接口是管理 API
 * （{@link ToolGrantController}）的写入面。空表 = 不启用 DB 侧校验（v1 向后兼容，见 Allowlist 语义）。
 */
public interface ToolGrantRepository {

    /** caller 已授权的工具名集合（无授权返回空 Set）。 */
    Set<String> findGrantedTools(String callerId);

    /** caller 是否被授予指定工具（获取 {@code *} 通配视为任意工具）。 */
    boolean isGranted(String callerId, String toolName);

    /** 授予 caller 一个工具（幂等：已存在则忽略）。 */
    void grant(String callerId, String toolName, String grantedBy);

    /** 撤销 caller 的一个工具授权（不存在则忽略）。 */
    void revoke(String callerId, String toolName);

    /** 全局授权记录数（0 表示 DB 侧未启用校验——Allowlist 据此判断是否 allow-all 兼容）。 */
    int totalGrantCount();
}
