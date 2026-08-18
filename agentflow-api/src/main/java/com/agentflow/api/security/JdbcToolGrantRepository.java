package com.agentflow.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PostgreSQL 版 {@link ToolGrantRepository}（v1.1 R21，生产）。
 *
 * <p>读写 {@code caller_tool_grants} 表（V6 迁移）。SQL 常量 package-private（单一真相源），
 * {@link com.agentflow.api.security.ToolGrantRepositoryTest} 用 H2 建兼容表跑同一 SQL 验证语义。
 *
 * <p>PostgresCheckpointManager 的 Flyway 迁移由同一 DataSource 管理，本仓储复用接入。
 */
public final class JdbcToolGrantRepository implements ToolGrantRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcToolGrantRepository.class);

    private final JdbcTemplate jdbc;

    public JdbcToolGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> findGrantedTools(String callerId) {
        if (callerId == null) return Set.of();
        List<String> tools = jdbc.queryForList(FIND_TOOLS_SQL, String.class, callerId);
        if (tools.isEmpty()) return Set.of();
        // 保留 SQL ORDER BY 结果（Set.copyOf 会打乱，管理 API 展示/测试需要确定性顺序）
        return Collections.unmodifiableSet(new LinkedHashSet<>(tools));
    }

    @Override
    public boolean isGranted(String callerId, String toolName) {
        if (callerId == null || toolName == null) return false;
        List<Integer> hits = jdbc.queryForList(IS_GRANTED_SQL, Integer.class, callerId, toolName);
        return !hits.isEmpty();
    }

    @Override
    public void grant(String callerId, String toolName, String grantedBy) {
        if (callerId == null || toolName == null || toolName.isBlank()) return;
        jdbc.update(GRANT_SQL, callerId, toolName, grantedBy, callerId, toolName);
        log.info("工具授权（DB）：caller={} tool={} by={}",
                maskCaller(callerId), toolName, grantedBy == null ? "null" : maskCaller(grantedBy));
    }

    @Override
    public void revoke(String callerId, String toolName) {
        jdbc.update(REVOKE_SQL, callerId, toolName);
    }

    @Override
    public int totalGrantCount() {
        // COUNT(*) 是 bigint，用 Long 避免驱动返回类型漂移
        Long count = jdbc.queryForObject(TOTAL_COUNT_SQL, Long.class);
        return count != null ? count.intValue() : 0;
    }

    static final String FIND_TOOLS_SQL =
            "SELECT tool_name FROM caller_tool_grants WHERE caller_id = ? ORDER BY tool_name";
    /** 命中本 tool 或 * 通配即视为授权（通配作为普通行存在，{@code tool_name='*'}）。 */
    static final String IS_GRANTED_SQL =
            "SELECT 1 FROM caller_tool_grants WHERE caller_id = ? AND (tool_name = ? OR tool_name = '*') LIMIT 1";
    /** 幂等 INSERT：PG/H2 均兼容（避免 PG 专有 ON CONFLICT），存在即零行插入。 */
    static final String GRANT_SQL =
            "INSERT INTO caller_tool_grants (caller_id, tool_name, granted_by) "
                    + "SELECT ?, ?, ? WHERE NOT EXISTS "
                    + "(SELECT 1 FROM caller_tool_grants WHERE caller_id = ? AND tool_name = ?)";
    static final String REVOKE_SQL =
            "DELETE FROM caller_tool_grants WHERE caller_id = ? AND tool_name = ?";
    static final String TOTAL_COUNT_SQL = "SELECT COUNT(*) FROM caller_tool_grants";

    private static String maskCaller(String id) {
        return id == null ? "null" : id.substring(0, Math.min(8, id.length())) + "...";
    }
}