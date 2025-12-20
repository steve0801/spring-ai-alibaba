/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.graph.agent.extension.interceptor;

import com.alibaba.cloud.ai.graph.agent.extension.tools.model.TaskTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SubAgent interceptor that provides subagent invocation capabilities to agents.
 *
 * This interceptor adds a `task` tool to the agent that can be used to invoke subagents.
 * Subagents are useful for handling complex tasks that require multiple steps, or tasks
 * that require a lot of context to resolve.
 *
 * A chief benefit of subagents is that they can handle multi-step tasks, and then return
 * a clean, concise response to the main agent.
 *
 * This interceptor comes with a default general-purpose subagent that can be used to
 * handle the same tasks as the main agent, but with isolated context.
 *
 * Example:
 * <pre>
 * SubAgentInterceptor interceptor = SubAgentInterceptor.builder()
 *     .defaultModel(chatModel)
 *     .addSubAgent(SubAgentSpec.builder()
 *         .name("research-analyst")
 *         .description("Use this agent to conduct thorough research on complex topics")
 *         .systemPrompt("You are a research analyst...")
 *         .build())
 *     .build();
 * </pre>
 */
public class SubAgentInterceptor extends ModelInterceptor {

	private static final String DEFAULT_SUBAGENT_PROMPT = "In order to complete the objective that the user asks of you, you have access to a number of standard tools.";

	private static final String DEFAULT_SYSTEM_PROMPT = """
		## `task` (subagent spawner)
		
		You have access to a `task` tool to launch short-lived subagents that handle isolated tasks. These agents are ephemeral — they live only for the duration of the task and return a single result.
		
		When to use the task tool:
		- When a task is complex and multi-step, and can be fully delegated in isolation
		- When a task is independent of other tasks and can run in parallel
		- When a task requires focused reasoning or heavy token/context usage that would bloat the orchestrator thread
		- When sandboxing improves reliability (e.g. code execution, structured searches, data formatting)
		- When you only care about the output of the subagent, and not the intermediate steps (ex. performing a lot of research and then returned a synthesized report, performing a series of computations or lookups to achieve a concise, relevant answer.)
		
		Subagent lifecycle:
		1. **Spawn** → Provide clear role, instructions, and expected output
		2. **Run** → The subagent completes the task autonomously
		3. **Return** → The subagent provides a single structured result
		4. **Reconcile** → Incorporate or synthesize the result into the main thread
		
		When NOT to use the task tool:
		- If you need to see the intermediate reasoning or steps after the subagent has completed (the task tool hides them)
		- If the task is trivial (a few tool calls or simple lookup)
		- If delegating does not reduce token usage, complexity, or context switching
		- If splitting would add latency without benefit
		
		## Important Task Tool Usage Notes to Remember
		- Whenever possible, parallelize the work that you do. This is true for both tool_calls, and for tasks. Whenever you have independent steps to complete - make tool_calls, or kick off tasks (subagents) in parallel to accomplish them faster. This saves time for the user, which is incredibly important.
		- Remember to use the `task` tool to silo independent tasks within a multi-part objective.
		- You should use the `task` tool whenever you have a complex task that will take multiple steps, and is independent from other tasks that the agent needs to complete. These agents are highly competent and efficient.
		""";

	private static final String DEFAULT_GENERAL_PURPOSE_DESCRIPTION =
			"General-purpose agent for researching complex questions, searching for files and content, " +
			"and executing multi-step tasks. This agent has access to all tools as the main agent.";

	private static final String TASK_TOOL_DESCRIPTION = """
			Launch an ephemeral subagent to handle complex, multi-step independent tasks with isolated context.
			
			Available agent types and the tools they have access to:
			{available_agents}
			
			When using the Task tool, you must specify a subagent_type parameter to select which agent type to use.
			
			## Usage notes:
			1. Launch multiple agents concurrently whenever possible to maximize performance
			2. When the agent is done, it will return a single message back to you
			3. Each agent invocation is stateless - provide a highly detailed task description
			4. The agent's outputs should generally be trusted
			5. Clearly tell the agent whether you expect it to create content, perform analysis, or just do research
			6. If the agent description mentions that it should be used proactively, then you should try your best to use it without the user having to ask for it first. Use your judgement.
			7. When only the general-purpose agent is provided, you should use it for all tasks. It is great for isolating context and token usage, and completing specific, complex tasks, as it has all the same capabilities as the main agent.
			
			### Example usage of the general-purpose agent:
			
			<example_agent_descriptions>
			"general-purpose": use this agent for general purpose tasks, it has access to all tools as the main agent.
			</example_agent_descriptions>
			
			<example>
			User: "I want to conduct research on the accomplishments of Lebron James, Michael Jordan, and Kobe Bryant, and then compare them."
			Assistant: *Uses the task tool in parallel to conduct isolated research on each of the three players*
			Assistant: *Synthesizes the results of the three isolated research tasks and responds to the User*
			<commentary>
			Research is a complex, multi-step task in it of itself.
			The research of each individual player is not dependent on the research of the other players.
			The assistant uses the task tool to break down the complex objective into three isolated tasks.
			Each research task only needs to worry about context and tokens about one player, then returns synthesized information about each player as the Tool Result.
			This means each research task can dive deep and spend tokens and context deeply researching each player, but the final result is synthesized information, and saves us tokens in the long run when comparing the players to each other.
			</commentary>
			</example>
			
			<example>
			User: "Analyze a single large code repository for security vulnerabilities and generate a report."
			Assistant: *Launches a single `task` subagent for the repository analysis*
			Assistant: *Receives report and integrates results into final summary*
			<commentary>
			Subagent is used to isolate a large, context-heavy task, even though there is only one. This prevents the main thread from being overloaded with details.
			If the user then asks followup questions, we have a concise report to reference instead of the entire history of analysis and tool calls, which is good and saves us time and money.
			</commentary>
			</example>
			
			<example>
			User: "Schedule two meetings for me and prepare agendas for each."
			Assistant: *Calls the task tool in parallel to launch two `task` subagents (one per meeting) to prepare agendas*
			Assistant: *Returns final schedules and agendas*
			<commentary>
			Tasks are simple individually, but subagents help silo agenda preparation.
			Each subagent only needs to worry about the agenda for one meeting.
			</commentary>
			</example>
			
			<example>
			User: "I want to order a pizza from Dominos, order a burger from McDonald's, and order a salad from Subway."
			Assistant: *Calls tools directly in parallel to order a pizza from Dominos, a burger from McDonald's, and a salad from Subway*
			<commentary>
			The assistant did not use the task tool because the objective is super simple and clear and only requires a few trivial tool calls.
			It is better to just complete the task directly and NOT use the `task`tool.
			</commentary>
			</example>
			
			### Example usage with custom agents:
			
			<example_agent_descriptions>
			"content-reviewer": use this agent after you are done creating significant content or documents
			"greeting-responder": use this agent when to respond to user greetings with a friendly joke
			"research-analyst": use this agent to conduct thorough research on complex topics
			</example_agent_description>
			
			<example>
			user: "Please write a function that checks if a number is prime"
			assistant: Sure let me write a function that checks if a number is prime
			assistant: First let me use the Write tool to write a function that checks if a number is prime
			assistant: I'm going to use the Write tool to write the following code:
			<code>
			function isPrime(n) {{
			  if (n <= 1) return false
			  for (let i = 2; i * i <= n; i++) {{
			    if (n % i === 0) return false
			  }}
			  return true
			}}
			</code>
			<commentary>
			Since significant content was created and the task was completed, now use the content-reviewer agent to review the work
			</commentary>
			assistant: Now let me use the content-reviewer agent to review the code
			assistant: Uses the Task tool to launch with the content-reviewer agent
			</example>
			
			<example>
			user: "Can you help me research the environmental impact of different renewable energy sources and create a comprehensive report?"
			<commentary>
			This is a complex research task that would benefit from using the research-analyst agent to conduct thorough analysis
			</commentary>
			assistant: I'll help you research the environmental impact of renewable energy sources. Let me use the research-analyst agent to conduct comprehensive research on this topic.
			assistant: Uses the Task tool to launch with the research-analyst agent, providing detailed instructions about what research to conduct and what format the report should take
			</example>
			
			<example>
			user: "Hello"
			<commentary>
			Since the user is greeting, use the greeting-responder agent to respond with a friendly joke
			</commentary>
			assistant: "I'm going to use the Task tool to launch with the greeting-responder agent"
			</example>
			""";

	// 工具回调列表
	private final List<ToolCallback> tools;
	// 系统提示信息
	private final String systemPrompt;
	// 子代理映射表
	private final Map<String, ReactAgent> subAgents;
	// 是否包含通用目的子代理
	private final boolean includeGeneralPurpose;

	private SubAgentInterceptor(Builder builder) {
		// 初始化系统提示信息，如果未设置则使用默认值
		this.systemPrompt = builder.systemPrompt != null ? builder.systemPrompt : DEFAULT_SYSTEM_PROMPT;
		// 复制构建器中的子代理映射表
		this.subAgents = new HashMap<>(builder.subAgents);
		// 初始化是否包含通用目的子代理标志
		this.includeGeneralPurpose = builder.includeGeneralPurpose;

		// 如果启用了通用目的子代理且设置了默认模型，则创建通用目的代理
		if (includeGeneralPurpose && builder.defaultModel != null) {
			ReactAgent generalPurposeAgent = createGeneralPurposeAgent(
				builder.defaultModel,
				builder.defaultTools,
				builder.defaultInterceptors
			);
			// 将通用目的代理添加到子代理映射表中
			this.subAgents.put("general-purpose", generalPurposeAgent);
		}

		// 使用工厂方法创建任务工具
		ToolCallback taskTool = TaskTool.createTaskToolCallback(
			this.subAgents,
			buildTaskToolDescription()
		);

		// 初始化工具列表
		this.tools = Collections.singletonList(taskTool);
	}

	// 创建通用目的代理的方法
	private ReactAgent createGeneralPurposeAgent(
			ChatModel model,
			List<ToolCallback> tools,
			List<? extends Interceptor> interceptors) {

		// 创建ReactAgent构建器
		com.alibaba.cloud.ai.graph.agent.Builder builder = ReactAgent.builder()
				.name("general-purpose")
				.model(model)
				.systemPrompt(DEFAULT_SUBAGENT_PROMPT)
				.saver(new MemorySaver());

		// 如果工具列表不为空，则设置工具
		if (tools != null && !tools.isEmpty()) {
			builder.tools(tools);
		}

		// 如果拦截器列表不为空，则设置拦截器
		if (interceptors != null && !interceptors.isEmpty()) {
			builder.interceptors(interceptors);
		}

		// 构建并返回ReactAgent实例
		return builder.build();
	}

	// 构建任务工具描述的方法
	private String buildTaskToolDescription() {
		// 创建代理描述构建器
		StringBuilder agentDescriptions = new StringBuilder();

		// 如果包含通用目的代理，则添加其描述
		if (includeGeneralPurpose) {
			agentDescriptions.append("- general-purpose: ")
					.append(DEFAULT_GENERAL_PURPOSE_DESCRIPTION)
					.append("\n");
		}

		// 遍历子代理映射表，添加除通用目的代理外的所有代理描述
		for (Map.Entry<String, ReactAgent> entry : subAgents.entrySet()) {
			if (!"general-purpose".equals(entry.getKey())) {
				agentDescriptions.append("- ")
						.append(entry.getKey())
						.append(": ")
						.append(entry.getValue().description() != null ?
								entry.getValue().description() : "Custom subagent")
						.append("\n");
			}
		}

		// 替换任务工具描述中的占位符并返回
		return TASK_TOOL_DESCRIPTION.replace("{available_agents}", agentDescriptions.toString());
	}

	// 构建器工厂方法
	public static Builder builder() {
		return new Builder();
	}

	// 获取工具列表
	@Override
	public List<ToolCallback> getTools() {
		return tools;
	}

	// 获取拦截器名称
	@Override
	public String getName() {
		return "SubAgent";
	}

	// 拦截模型调用的方法
	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		// 增强系统提示信息，添加子代理指导
		SystemMessage enhancedSystemMessage;

		// 如果原始请求没有系统消息，则使用子代理系统提示
		if (request.getSystemMessage() == null) {
			enhancedSystemMessage = new SystemMessage(this.systemPrompt);
		} else {
			// 否则将子代理系统提示追加到现有系统消息后
			enhancedSystemMessage = new SystemMessage(request.getSystemMessage().getText() + "\n\n" + systemPrompt);
		}

		// 创建增强的请求
		ModelRequest enhancedRequest = ModelRequest.builder(request)
				.systemMessage(enhancedSystemMessage)
				.build();

		// 使用增强的请求调用处理器
		return handler.call(enhancedRequest);
	}

	// 构建器类
	public static class Builder {
		// 系统提示信息
		private String systemPrompt;
		// 默认模型
		private ChatModel defaultModel;
		// 默认工具列表
		private List<ToolCallback> defaultTools;
		// 默认拦截器列表
		private List<Interceptor> defaultInterceptors;
		// 默认钩子列表
		private List<Hook> defaultHooks;
		// 子代理映射表
		private Map<String, ReactAgent> subAgents = new HashMap<>();
		// 是否包含通用目的子代理，默认为true
		private boolean includeGeneralPurpose = true;

		/**
		 * Set custom system prompt to guide subagent usage.
		 */
		// 设置自定义系统提示信息
		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		/**
		 * Set the default model to use for subagents.
		 */
		// 设置默认模型
		public Builder defaultModel(ChatModel model) {
			this.defaultModel = model;
			return this;
		}

		/**
		 * Set the default tools available to subagents.
		 */
		// 设置默认工具列表
		public Builder defaultTools(List<ToolCallback> tools) {
			this.defaultTools = tools;
			return this;
		}


		// 设置默认拦截器
		public Builder defaultInterceptors(Interceptor... interceptors) {
			this.defaultInterceptors = Arrays.asList(interceptors);
			return this;
		}

		/**
		 * Set the default hooks to apply to subagents.
		 */
		// 设置默认钩子
		public Builder defaultHooks(Hook... hooks) {
			this.defaultHooks = Arrays.asList(hooks);
			return this;
		}

		/**
		 * Add a custom subagent.
		 */
		// 添加自定义子代理
		public Builder addSubAgent(String name, ReactAgent agent) {
			this.subAgents.put(name, agent);
			return this;
		}

		/**
		 * Add a subagent from specification.
		 */
		// 根据规范添加子代理
		public Builder addSubAgent(SubAgentSpec spec) {
			ReactAgent agent = createSubAgentFromSpec(spec);
			this.subAgents.put(spec.getName(), agent);
			return this;
		}

		/**
		 * Whether to include the default general-purpose subagent.
		 */
		// 设置是否包含通用目的子代理
		public Builder includeGeneralPurpose(boolean include) {
			this.includeGeneralPurpose = include;
			return this;
		}

		// 根据规范创建子代理的方法
		private ReactAgent createSubAgentFromSpec(SubAgentSpec spec) {
			// 创建ReactAgent构建器
			com.alibaba.cloud.ai.graph.agent.Builder builder = ReactAgent.builder()
					.name(spec.getName())
					.description(spec.getDescription())
					.instruction(spec.getSystemPrompt())
					.saver(new MemorySaver());

			// 获取模型，如果规范中未设置则使用默认模型
			ChatModel model = spec.getModel() != null ? spec.getModel() : defaultModel;
			if (model != null) {
				builder.model(model);
			}

			// 获取工具列表，如果规范中未设置则使用默认工具
			List<ToolCallback> tools = spec.getTools() != null ? spec.getTools() : defaultTools;
			if (tools != null && !tools.isEmpty()) {
				builder.tools(tools);
			}

			// 应用默认拦截器，然后应用自定义拦截器
			List<Interceptor> allInterceptors = new ArrayList<>();
			if (defaultInterceptors != null) {
				allInterceptors.addAll(defaultInterceptors);
			}
			if (spec.getInterceptors() != null) {
				allInterceptors.addAll(spec.getInterceptors());
			}

			// 如果拦截器列表不为空，则设置拦截器
			if (!allInterceptors.isEmpty()) {
				builder.interceptors(allInterceptors);
			}

			// 如果默认钩子列表不为空，则设置钩子
			if (defaultHooks != null) {
				builder.hooks(defaultHooks);
			}

			// 设置是否启用循环日志
			builder.enableLogging(spec.isEnableLoopingLog());

			// 构建并返回ReactAgent实例
			return builder.build();
		}

		// 构建SubAgentInterceptor实例
		public SubAgentInterceptor build() {
			return new SubAgentInterceptor(this);
		}
	}
}

