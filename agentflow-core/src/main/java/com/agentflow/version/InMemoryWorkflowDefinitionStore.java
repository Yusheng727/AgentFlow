package com.agentflow.version;

import com.agentflow.dsl.WorkflowDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版 {@link WorkflowDefinitionStore}（开发/测试/mock 模式，进程内，重启丢失）。
 *
 * <p>按 {@code name@version} 存定义；另按 name 记保存顺序（{@link #findLatest} 取最近保存的版本）。
 */
public final class InMemoryWorkflowDefinitionStore implements WorkflowDefinitionStore {

    private final Map<String, WorkflowDefinition> byKey = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<String>> orderByName = new ConcurrentHashMap<>();

    @Override
    public Optional<WorkflowDefinition> find(String workflowName, String version) {
        return Optional.ofNullable(byKey.get(key(workflowName, version)));
    }

    @Override
    public Optional<WorkflowDefinition> findLatest(String workflowName) {
        List<String> order = orderByName.get(workflowName);
        if (order == null || order.isEmpty()) {
            return Optional.empty();
        }
        return find(workflowName, order.get(order.size() - 1));
    }

    @Override
    public void save(String workflowName, String version, WorkflowDefinition definition) {
        byKey.put(key(workflowName, version), definition);
        orderByName.computeIfAbsent(workflowName, k -> new CopyOnWriteArrayList<>())
                .addIfAbsent(version);
    }

    private static String key(String name, String version) {
        return name + "@" + version;
    }
}
