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

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Request object for model calls.
 * Contains all information needed to make a model invocation.
 */
public class ModelRequest {
	// 系统消息，用于设定AI助手的行为和角色
	private final SystemMessage systemMessage;
	// 上下文信息，存储与请求相关的额外数据
	private final Map<String, Object> context;
	// 消息列表，包含用户和AI之间的对话历史
	private final List<Message> messages;
	// 聊天选项，配置模型调用的各种参数
	private final ChatOptions options;
	// 可用工具列表，指定当前请求可以使用的工具名称
	private final List<String> tools;

	// 构造函数，初始化ModelRequest的所有字段
	public ModelRequest(SystemMessage systemMessage, List<Message> messages, ChatOptions options, List<String> tools, Map<String, Object> context) {
		// 初始化系统消息
		this.systemMessage = systemMessage;
		// 初始化消息列表
		this.messages = messages;
		// 初始化聊天选项
		this.options = options;
		// 初始化工具列表
		this.tools = tools;
		// 初始化上下文信息
		this.context = context;
	}

	// 静态工厂方法，创建一个新的Builder实例
	public static Builder builder() {
		return new Builder();
	}

	// 静态工厂方法，基于现有ModelRequest创建Builder实例并复制其属性
	public static Builder builder(ModelRequest request) {
		return new Builder()
				// 复制消息列表
				.messages(request.messages)
				// 复制聊天选项
				.options(request.options)
				// 复制工具列表
				.tools(request.tools)
				// 复制上下文信息
				.context(request.context);
	}

	// 获取消息列表的getter方法
	public List<Message> getMessages() {
		return messages;
	}

	// 获取系统消息的getter方法
	public SystemMessage getSystemMessage() {
		return systemMessage;
	}

	// 获取聊天选项的getter方法
	public ChatOptions getOptions() {
		return options;
	}

	// 获取工具列表的getter方法
	public List<String> getTools() {
		return tools;
	}

	// 获取上下文信息的getter方法
	public Map<String, Object> getContext() {
		return context;
	}

	// Builder内部类，用于构建ModelRequest对象
	public static class Builder {
		// Builder中的系统消息字段
		private SystemMessage systemMessage;
		// Builder中的消息列表字段
		private List<Message> messages;
		// Builder中的聊天选项字段
		private ChatOptions options;
		// Builder中的工具列表字段
		private List<String> tools;
		// Builder中的上下文信息字段
		private Map<String, Object> context;

		// 设置系统消息的Builder方法
		public Builder systemMessage(SystemMessage systemMessage) {
			this.systemMessage = systemMessage;
			return this;
		}

		// 设置消息列表的Builder方法
		public Builder messages(List<Message> messages) {
			this.messages = messages;
			return this;
		}

		// 设置聊天选项的Builder方法
		public Builder options(ChatOptions options) {
			this.options = options;
			return this;
		}

		// 设置工具列表的Builder方法
		public Builder tools(List<String> tools) {
			this.tools = tools;
			return this;
		}

		// 设置上下文信息的Builder方法，创建新的HashMap副本以避免外部修改
		public Builder context(Map<String, Object> context) {
			this.context = new HashMap<>(context);
			return this;
		}

		// 构建ModelRequest实例的方法
		public ModelRequest build() {
			return new ModelRequest(systemMessage, messages, options, tools, context);
		}
	}
}

