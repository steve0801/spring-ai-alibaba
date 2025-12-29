/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.examples.documentation.framework.advanced.a2a;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.a2a.A2aRemoteAgent;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardProvider;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;

import org.springframework.ai.chat.model.ChatModel;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * A2A (Agent-to-Agent) 一体化示例：注册 -> 发现 -> 调用
 *
 * - 启动本应用后，data_analysis_agent 将作为本地 ReactAgent 自动注册到 A2A（并根据配置注册到 Nacos）
 * - 通过 AgentCardProvider 从注册中心发现该 Agent
 * - 构造 A2aRemoteAgent 远程代理并完成调用
 */
@Component
public class A2AExample {

	private final ChatModel chatModel;
	private final AgentCardProvider agentCardProvider;
	private final ReactAgent localDataAnalysisAgent;

	@Autowired
	public A2AExample(@Qualifier("dashscopeChatModel") ChatModel chatModel,
			AgentCardProvider agentCardProvider,
			@Qualifier("dataAnalysisAgent") ReactAgent localDataAnalysisAgent) {
		this.chatModel = chatModel;
		this.agentCardProvider = agentCardProvider;
		this.localDataAnalysisAgent = localDataAnalysisAgent;
	}

	/**
	 * 运行一体化演示
	 * 1) 本地 Agent 已由 Spring 容器创建并通过 A2A Server 自动暴露
	 * 2) 使用 AgentCardProvider 从注册中心发现该 Agent
	 * 3) 构建 A2aRemoteAgent 并完成一次远程调用
	 */
	public void runDemo() throws GraphRunnerException {
		// 打印标题信息，表明这是一个A2A一体化演示程序
		System.out.println("=== A2A 一体化演示：注册 -> 发现 -> 调用 ===\n");

		// 阶段说明
		// 输出架构说明标题
		System.out.println("【架构说明】");
		// 说明第一阶段：服务注册，将本地Agent注册到Nacos供其他服务发现
		System.out.println("1. Registry（注册）：本地 Agent 注册到 Nacos，供其他服务发现");
		// 说明第二阶段：服务发现，通过AgentCardProvider从Nacos查询Agent
		System.out.println("2. Discovery（发现）：通过 AgentCardProvider 从 Nacos 查询 Agent");
		// 说明第三阶段：服务调用，构造A2aRemoteAgent完成远程调用
		System.out.println("3. Invocation（调用）：构造 A2aRemoteAgent 完成远程调用");
		// 添加空行提升可读性
		System.out.println();

		// 1) 本地直连：验证本地注册的 ReactAgent 可用
		// 输出阶段1标题，验证ReactAgent Bean功能
		System.out.println("【阶段1：本地直调】验证 ReactAgent Bean 功能");
		// 显示Agent名称
		System.out.println("- Agent 名称: data_analysis_agent");
		// 显示调用方式为直接调用Bean
		System.out.println("- 调用方式: 直接调用 Bean");
		// 显示注册状态，已通过A2A Server AutoConfiguration注册到Nacos
		System.out.println("- 注册状态: 已通过 A2A Server AutoConfiguration 注册到 Nacos");
		// 添加空行提升可读性
		System.out.println();

		// 提示开始执行本地调用
		System.out.println("执行本地调用...");
		// 调用本地dataAnalysisAgent，传入分析请求
		Optional<OverAllState> localResult = localDataAnalysisAgent.invoke("请对上月销售数据进行趋势分析，并给出关键结论。");
		// 处理调用结果，如果存在则提取messages字段并打印结果摘要
		localResult.flatMap(s -> s.value("messages")).ifPresent(r ->
				System.out.println("✓ 本地调用成功，结果: " + (r.toString().length() > 100 ? r.toString()
						.substring(0, 100) + "..." : r)));
		// 添加空行提升可读性
		System.out.println();

		// 2) 发现：通过 AgentCardProvider 从注册中心获取该 Agent 的 AgentCard
		// 输出阶段2标题，使用AgentCardProvider从Nacos发现Agent
		System.out.println("【阶段2：服务发现】使用 AgentCardProvider 从 Nacos 发现 Agent");
		// 显示发现机制为Nacos Discovery
		System.out.println("- 发现机制: Nacos Discovery (spring.ai.alibaba.a2a.nacos.discovery.enabled=true)");
		// 显示AgentCardProvider的实现类类型
		System.out.println("- AgentCardProvider 类型: " + agentCardProvider.getClass().getSimpleName());
		// 显示要查询的Agent名称
		System.out.println("- 查询 Agent: data_analysis_agent");
		// 添加空行提升可读性
		System.out.println();

		// 提示开始构建A2aRemoteAgent
		System.out.println("构建 A2aRemoteAgent...");
		// 使用Builder模式创建A2aRemoteAgent实例
		A2aRemoteAgent remote = A2aRemoteAgent.builder()
				// 设置Agent名称
				.name("data_analysis_agent")
				// 设置AgentCardProvider，用于从Nacos自动获取AgentCard
				.agentCardProvider(agentCardProvider)  // 从 Nacos 自动获取 AgentCard
				// 设置描述信息
				.description("数据分析远程代理")
				// 构建A2aRemoteAgent对象
				.build();
		// 输出构建成功信息
		System.out.println("✓ A2aRemoteAgent 构建成功，AgentCard 已从 Nacos 获取");
		// 添加空行提升可读性
		System.out.println();

		// 3) 远程调用：通过 A2aRemoteAgent 调用（即便是同进程，也模拟远程化调用路径）
		// 输出阶段3标题，通过A2aRemoteAgent执行远程调用
		System.out.println("【阶段3：远程调用】通过 A2aRemoteAgent 执行远程调用");
		// 显示调用路径：A2aRemoteAgent -> REST API -> 本地ReactAgent
		System.out.println("- 调用路径: A2aRemoteAgent -> REST API (/a2a/message) -> 本地 ReactAgent");
		// 显示传输协议为JSON-RPC over HTTP
		System.out.println("- 传输协议: JSON-RPC over HTTP");
		// 添加空行提升可读性
		System.out.println();

		// 提示开始执行远程调用
		System.out.println("执行远程调用...");
		// 调用远程Agent，传入分析请求
		Optional<OverAllState> remoteResult = remote.invoke("请根据季度数据给出同比与环比分析概要。");
		// 处理调用结果，如果存在则提取output字段并打印结果摘要
		remoteResult.flatMap(s -> s.value("output")).ifPresent(r ->
				System.out.println("✓ 远程调用成功，结果: " + (r.toString().length() > 100 ? r.toString()
						.substring(0, 100) + "..." : r)));
		// 添加空行提升可读性
		System.out.println();

		// 验证要点
		// 输出验证要点标题
		System.out.println("【验证要点】");
		// 提供查看本地AgentCard的方法
		System.out.println("1. 本地 AgentCard:");
		System.out.println("   → curl http://localhost:8080/.well-known/agent.json");
		// 添加空行提升可读性
		System.out.println();
		// 提供Nacos控制台验证注册的方法
		System.out.println("2. Nacos 控制台（验证注册）:");
		System.out.println("   → http://localhost:8848/nacos");
		System.out.println("   → 登录 (nacos/nacos)");
		System.out.println("   → 查看 A2A 服务注册维度");
		// 添加空行提升可读性
		System.out.println();
		// 提供配置说明
		System.out.println("3. 配置说明:");
		System.out.println("   → registry.enabled=true  : 将本地 Agent 注册到 Nacos（服务提供者）");
		System.out.println("   → discovery.enabled=true : 从 Nacos 发现其他 Agent（服务消费者）");
		// 添加空行提升可读性
		System.out.println();
		// 提供其他服务调用示例
		System.out.println("4. 其他服务调用:");
		System.out.println("   其他服务可使用相同的方式发现并调用 data_analysis_agent:");
		System.out.println("   ```");
		System.out.println("   A2aRemoteAgent remote = A2aRemoteAgent.builder()");
		System.out.println("       .name(\"data_analysis_agent\")");
		System.out.println("       .agentCardProvider(agentCardProvider)");
		System.out.println("       .build();");
		System.out.println("   remote.invoke(\"分析请求...\");");
		System.out.println("   ```");
	}
}
