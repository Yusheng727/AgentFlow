package com.agentflow.dsl;

/**
 * YAML 顶层 agentflow 段。承载 SemVer 版本字段（R14）。
 *
 * <pre>
 * agentflow:
 *   version: "1.0"
 * </pre>
 */
public record AgentflowMeta(String version) {
}
