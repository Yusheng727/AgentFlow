package com.agentflow.api.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * admin API key 解析的单一真相源（review #6：三 controller 原逐字重复 hashKeys）。
 *
 * <p>env {@code agentflow.admin.api-keys}（逗号分隔多值）→ 各 key 的 SHA-256 哈希集合，
 * 供 {@link com.agentflow.api.ApprovalController} / {@link ToolGrantController} /
 * {@link com.agentflow.api.ApprovalCenterController} 判定调用者是否 admin。
 * 与普通 X-API-Key 同经 {@link ApiKeyAuthFilter#sha256} 哈希比对——统一口径防一端 env
 * 解析改动静默破坏另一端可见域。
 */
public final class AdminApiKeys {

    private final Set<String> hashes;

    private AdminApiKeys(Set<String> hashes) {
        this.hashes = hashes;
    }

    public static AdminApiKeys from(String adminKeysCsv) {
        if (adminKeysCsv == null || adminKeysCsv.isBlank()) {
            return new AdminApiKeys(Collections.emptySet());
        }
        Set<String> hashes = new LinkedHashSet<>();
        Arrays.stream(adminKeysCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .forEach(k -> hashes.add(ApiKeyAuthFilter.sha256(k)));
        return new AdminApiKeys(Collections.unmodifiableSet(hashes));
    }

    /** 该 caller 是否为 admin（按 X-API-Key 的 SHA-256 哈希比对）。 */
    public boolean isAdmin(String callerId) {
        return callerId != null && hashes.contains(callerId);
    }

    /** 未配置任何 admin key（grant/revoke 类端点可据此 403 安全默认）。 */
    public boolean isEmpty() {
        return hashes.isEmpty();
    }
}
