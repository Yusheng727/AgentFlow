package com.agentflow.starter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 开启 AgentFlow 自动配置（U9 起引入，U13 扩展完整 Bean 注册）。
 *
 * <p>放在任意 {@code @Configuration} 类上，触发 {@link AgentFlowAutoConfiguration}：
 * <ul>
 *   <li>注册 {@link com.agentflow.engine.BspEngine}、{@link com.agentflow.dsl.WorkflowDSLParser} 等核心 Bean</li>
 *   <li>{@code agentflow.mock.enabled=true} 时，AgentFunction 解析为 {@link com.agentflow.adapters.mock.MockAgentFunction}，零 LLM 成本</li>
 * </ul>
 *
 * <p>v1（U9）只落地 mock 切换；完整 Bean 注册（NodeRegistry / CheckpointManager / Controller）在 U13。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME
)
public @interface EnableAgentFlow {
}
