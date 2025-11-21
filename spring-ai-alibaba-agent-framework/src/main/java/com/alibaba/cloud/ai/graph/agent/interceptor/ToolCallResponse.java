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

import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Response object for tool calls.
 */
public class ToolCallResponse {

	// 工具执行结果内容
	private final String result;
	// 工具名称
	private final String toolName;
	// 工具调用ID
	private final String toolCallId;
	// 工具执行状态
	private final String status;
	// 元数据信息
	private final Map<String, Object> metadata;

	// 构造函数，初始化基本字段，status和metadata设为null
	public ToolCallResponse(String result, String toolName, String toolCallId) {
		this(result, toolName, toolCallId, null, null);
	}

	// 完整构造函数，初始化所有字段
	public ToolCallResponse(String result, String toolName, String toolCallId, String status, Map<String, Object> metadata) {
		// 初始化执行结果
		this.result = result;
		// 初始化工具名称
		this.toolName = toolName;
		// 初始化工具调用ID
		this.toolCallId = toolCallId;
		// 初始化执行状态
		this.status = status;
		// 初始化元数据，如果传入null则使用空Map
		this.metadata = metadata != null ? new HashMap<>(metadata) : Collections.emptyMap();
	}

	// 静态工厂方法，创建ToolCallResponse实例
	public static ToolCallResponse of(String toolCallId, String toolName, String result) {
		return new ToolCallResponse(result, toolName, toolCallId);
	}

	// 静态工厂方法，创建Builder实例
	public static Builder builder() {
		return new Builder();
	}

	// 获取执行结果
	public String getResult() {
		return result;
	}

	// 获取工具名称
	public String getToolName() {
		return toolName;
	}

	// 获取工具调用ID
	public String getToolCallId() {
		return toolCallId;
	}

	// 获取执行状态
	public String getStatus() {
		return status;
	}

	// 获取元数据的不可变视图
	public Map<String, Object> getMetadata() {
		return Collections.unmodifiableMap(metadata);
	}

	// 将ToolCallResponse转换为ToolResponseMessage.ToolResponse
	public ToolResponseMessage.ToolResponse toToolResponse() {
		return new ToolResponseMessage.ToolResponse(toolCallId, toolName, result);
	}

	// Builder内部类，用于构建ToolCallResponse对象
	public static class Builder {
		// 内容字段
		private String content;
		// 工具名称字段
		private String toolName;
		// 工具调用ID字段
		private String toolCallId;
		// 状态字段
		private String status;
		// 元数据字段
		private Map<String, Object> metadata;

		// 设置内容
		public Builder content(String content) {
			this.content = content;
			return this;
		}

		// 设置工具名称
		public Builder toolName(String toolName) {
			this.toolName = toolName;
			return this;
		}

		// 设置工具调用ID
		public Builder toolCallId(String toolCallId) {
			this.toolCallId = toolCallId;
			return this;
		}

		// 设置状态
		public Builder status(String status) {
			this.status = status;
			return this;
		}

		// 设置元数据
		public Builder metadata(Map<String, Object> metadata) {
			this.metadata = metadata;
			return this;
		}

		// 构建ToolCallResponse实例
		public ToolCallResponse build() {
			return new ToolCallResponse(content, toolName, toolCallId, status, metadata);
		}
	}
}

