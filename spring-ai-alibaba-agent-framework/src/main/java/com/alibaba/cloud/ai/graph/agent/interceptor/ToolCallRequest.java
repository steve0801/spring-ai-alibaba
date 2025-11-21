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
package com.alibaba.cloud.ai.graph.agent.interceptor;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.Map;

/**
 * Request object for tool calls.
 */
public class ToolCallRequest {

	// 工具名称
	private final String toolName;
	// 工具调用参数
	private final String arguments;
	// 工具调用ID
	private final String toolCallId;
	// 上下文信息
	private final Map<String, Object> context;

	// 构造函数，初始化所有字段
	public ToolCallRequest(String toolName, String arguments, String toolCallId, Map<String, Object> context) {
		// 初始化工具名称
		this.toolName = toolName;
		// 初始化参数
		this.arguments = arguments;
		// 初始化工具调用ID
		this.toolCallId = toolCallId;
		// 初始化上下文
		this.context = context;
	}

	// 静态工厂方法，创建新的Builder实例
	public static Builder builder() {
		return new Builder();
	}

	// 静态工厂方法，基于现有ToolCallRequest创建Builder实例并复制属性
	public static Builder builder(ToolCallRequest request) {
		return new Builder()
				// 复制工具名称
				.toolName(request.toolName)
				// 复制参数
				.arguments(request.arguments)
				// 复制工具调用ID
				.toolCallId(request.toolCallId)
				// 复制上下文
				.context(request.context);
	}

	// 获取工具名称
	public String getToolName() {
		return toolName;
	}

	// 获取参数
	public String getArguments() {
		return arguments;
	}

	// 获取工具调用ID
	public String getToolCallId() {
		return toolCallId;
	}

	// 获取上下文信息
	public Map<String, Object> getContext() {
		return context;
	}

	// Builder内部类，用于构建ToolCallRequest对象
	public static class Builder {
		// 工具名称
		private String toolName;
		// 参数
		private String arguments;
		// 工具调用ID
		private String toolCallId;
		// 上下文信息
		private Map<String, Object> context;

		// 从AssistantMessage.ToolCall对象设置Builder属性
		public Builder toolCall(AssistantMessage.ToolCall toolCall) {
			// 设置工具名称为toolCall的name
			this.toolName = toolCall.name();
			// 设置参数为toolCall的arguments
			this.arguments = toolCall.arguments();
			// 设置工具调用ID为toolCall的id
			this.toolCallId = toolCall.id();
			return this;
		}

		// 设置工具名称
		public Builder toolName(String toolName) {
			this.toolName = toolName;
			return this;
		}

		// 设置参数
		public Builder arguments(String arguments) {
			this.arguments = arguments;
			return this;
		}

		// 设置工具调用ID
		public Builder toolCallId(String toolCallId) {
			this.toolCallId = toolCallId;
			return this;
		}

		// 设置上下文信息
		public Builder context(Map<String, Object> context) {
			this.context = context;
			return this;
		}

		// 构建ToolCallRequest实例
		public ToolCallRequest build() {
			return new ToolCallRequest(toolName, arguments, toolCallId, context);
		}
	}
}

