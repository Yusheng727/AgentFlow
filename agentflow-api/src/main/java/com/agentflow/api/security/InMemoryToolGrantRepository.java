package com.agentflow.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 {@link ToolGrantRepository}（开发/测试/mock 模式，进程内，重启丢失）。
 *
 * <p>demo-api 默认装配此实现（无 PG 也可跑管理 API）；生产用 {@link JdbcToolGrantRepository}。
 * {@code grant} 支持 {@code *} 通配 toolName（caller 授权任意工具）。
 */
public final class InMemoryToolGrantRepository implements ToolGrantRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryToolGrantRepository.class);

    private final Map<String, Set<String>> grants = new ConcurrentHashMap<>();

    @Override
    public Set<String> findGrantedTools(String callerId) {
        if (callerId == null) return Set.of();
        Set<String> tools = grants.get(callerId);
        return tools != null ? Set.copyOf(tools) : Set.of();
    }

    @Override
    public boolean isGranted(String callerId, String toolName) {
        if (callerId == null || toolName == null) return false;
        Set<String> tools = grants.get(callerId);
        return tools != null && (tools.contains(toolName) || tools.contains("*"));
    }

    @Override
    public void grant(String callerId, String toolName, String grantedBy) {
        if (callerId == null || toolName == null || toolName.isBlank()) return;
        grants.computeIfAbsent(callerId, k -> ConcurrentHashMap.newKeySet()).add(toolName);
        log.info("工具授权：caller={} tool={} by={}",
                maskCaller(callerId), toolName, grantedBy == null ? "null" : maskCaller(grantedBy));
    }

    @Override
    public void revoke(String callerId, String toolName) {
        Set<String> tools = grants.get(callerId);
        if (tools != null) {
            tools.remove(toolName);
            if (tools.isEmpty()) {
                grants.remove(callerId, tools);
            }
        }
    }

    @Override
    public int totalGrantCount() {
        return grants.values().stream().mapToInt(Set::size).sum();
    }

    private static String maskCaller(String id) {
        return id == null ? "null" : id.substring(0, Math.min(8, id.length())) + "...";
    }
}