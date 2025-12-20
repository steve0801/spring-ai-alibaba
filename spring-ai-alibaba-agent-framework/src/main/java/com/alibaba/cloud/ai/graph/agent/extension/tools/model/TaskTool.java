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
package com.alibaba.cloud.ai.graph.agent.extension.tools.model;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.Map;
import java.util.function.BiFunction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Tool that enables invoking subagents to handle complex, isolated tasks.
 *
 * This tool allows the main agent to delegate work to specialized subagents,
 * each with their own context and capabilities.
 */
public class TaskTool implements BiFunction<TaskTool.TaskRequest, ToolContext, String> {

	// 存储子代理的映射表
	private final Map<String, ReactAgent> subAgents;

	// 构造函数，初始化子代理映射表
	public TaskTool(Map<String, ReactAgent> subAgents) {
		this.subAgents = subAgents;
	}

	// 实现BiFunction接口的apply方法，用于执行任务工具
	@Override
	public String apply(TaskRequest request, ToolContext toolContext) {
		// 验证子代理类型是否存在
		if (!subAgents.containsKey(request.subagentType)) {
			return "Error: invoked agent of type " + request.subagentType +
					", the only allowed types are " + subAgents.keySet();
		}

		// 获取指定类型的子代理
		ReactAgent subAgent = subAgents.get(request.subagentType);

		try {
			// 使用任务描述调用子代理
			AssistantMessage result = subAgent.call(request.description);

			// 返回子代理的响应文本
			return result.getText();
		}
		catch (Exception e) {
			// 处理异常，返回错误信息
			return "Error executing subagent task: " + e.getMessage();
		}
	}

	/**
	 * Create a ToolCallback for the task tool.
	 */
	// 创建任务工具的ToolCallback的工厂方法
	public static ToolCallback createTaskToolCallback(Map<String, ReactAgent> subAgents, String description) {
		return FunctionToolCallback.builder("task", new TaskTool(subAgents))
				.description(description)
				.inputType(TaskRequest.class)
				.build();
	}

	/**
	 * Request structure for the task tool.
	 */
	// 任务工具请求的数据结构
	public static class TaskRequest {

		// 任务描述属性，必需
		@JsonProperty(required = true)
		@JsonPropertyDescription("Detailed description of the task to be performed by the subagent")
		public String description;

		// 子代理类型属性，必需
		@JsonProperty(required = true, value = "subagent_type")
		@JsonPropertyDescription("The type of subagent to use for this task")
		public String subagentType;

		// 默认构造函数
		public TaskRequest() {
		}

		// 带参数的构造函数
		public TaskRequest(String description, String subagentType) {
			this.description = description;
			this.subagentType = subagentType;
		}
	}
}

