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

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Specification for creating a subagent.
 *
 * This class defines the configuration for a custom subagent that can be used
 * with the SubAgentInterceptor. Each subagent has its own name, description,
 * system prompt, and optionally custom tools and interceptors.
 */
public class SubAgentSpec {

	// 子代理名称
	private final String name;
	// 子代理描述
	private final String description;
	// 子代理系统提示
	private final String systemPrompt;
	// 子代理使用的模型
	private final ChatModel model;
	// 子代理可用的工具列表
	private final List<ToolCallback> tools;
	// 子代理使用的拦截器列表
	private final List<ModelInterceptor> interceptors;
	// 是否启用循环日志
	private final boolean enableLoopingLog;

	// 通过构建器初始化SubAgentSpec实例
	private SubAgentSpec(Builder builder) {
		// 设置子代理名称
		this.name = builder.name;
		// 设置子代理描述
		this.description = builder.description;
		// 设置子代理系统提示
		this.systemPrompt = builder.systemPrompt;
		// 设置子代理模型
		this.model = builder.model;
		// 设置子代理工具列表
		this.tools = builder.tools;
		// 设置子代理拦截器列表
		this.interceptors = builder.interceptors;
		// 设置是否启用循环日志
		this.enableLoopingLog = builder.enableLoopingLog;
	}

	// 构建器工厂方法
	public static Builder builder() {
		return new Builder();
	}

	// 获取子代理名称
	public String getName() {
		return name;
	}

	// 获取子代理描述
	public String getDescription() {
		return description;
	}

	// 获取子代理系统提示
	public String getSystemPrompt() {
		return systemPrompt;
	}

	// 获取子代理模型
	public ChatModel getModel() {
		return model;
	}

	// 获取子代理工具列表
	public List<ToolCallback> getTools() {
		return tools;
	}

	// 获取子代理拦截器列表
	public List<ModelInterceptor> getInterceptors() {
		return interceptors;
	}

	// 检查是否启用循环日志
	public boolean isEnableLoopingLog() {
		return enableLoopingLog;
	}

	// 子代理规范构建器类
	public static class Builder {
		// 子代理名称
		private String name;
		// 子代理描述
		private String description;
		// 子代理系统提示
		private String systemPrompt;
		// 子代理模型
		private ChatModel model;
		// 子代理工具列表
		private List<ToolCallback> tools;
		// 子代理拦截器列表
		private List<ModelInterceptor> interceptors;
		// 是否启用循环日志
		private boolean enableLoopingLog;

		/**
		 * Set the name of the subagent (required).
		 */
		// 设置子代理名称（必需）
		public Builder name(String name) {
			this.name = name;
			return this;
		}

		/**
		 * Set the description of the subagent (required).
		 * This is used by the main agent to decide whether to call the subagent.
		 */
		// 设置子代理描述（必需）
		// 主代理使用此描述来决定是否调用子代理
		public Builder description(String description) {
			this.description = description;
			return this;
		}

		/**
		 * Set the system prompt for the subagent (required).
		 * This is used as the instruction when the subagent is invoked.
		 */
		// 设置子代理系统提示（必需）
		// 当子代理被调用时，这将作为指令使用
		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		/**
		 * Set a custom model for this subagent.
		 * If not set, the default model from SubAgentInterceptor will be used.
		 */
		// 为此子代理设置自定义模型
		// 如果未设置，将使用来自SubAgentInterceptor的默认模型
		public Builder model(ChatModel model) {
			this.model = model;
			return this;
		}

		/**
		 * Set custom tools for this subagent.
		 * If not set, the default tools from SubAgentInterceptor will be used.
		 */
		// 为此子代理设置自定义工具
		// 如果未设置，将使用来自SubAgentInterceptor的默认工具
		public Builder tools(List<ToolCallback> tools) {
			this.tools = tools;
			return this;
		}

		/**
		 * Set custom interceptors for this subagent.
		 * These will be applied after the default interceptors from SubAgentInterceptor.
		 */
		// 为此子代理设置自定义拦截器
		// 这些拦截器将在SubAgentInterceptor的默认拦截器之后应用
		public Builder interceptors(List<ModelInterceptor> interceptors) {
			this.interceptors = interceptors;
			return this;
		}

		// 设置是否启用循环日志
		public Builder enableLoopingLog(boolean enableLoopingLog) {
			this.enableLoopingLog = enableLoopingLog;
			return this;
		}

		// 构建SubAgentSpec实例
		public SubAgentSpec build() {
			// 检查子代理名称是否为空
			if (name == null || name.trim().isEmpty()) {
				throw new IllegalArgumentException("SubAgent name is required");
			}
			// 检查子代理描述是否为空
			if (description == null || description.trim().isEmpty()) {
				throw new IllegalArgumentException("SubAgent description is required");
			}
			// 检查子代理系统提示是否为空
			if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
				throw new IllegalArgumentException("SubAgent system prompt is required");
			}
			// 创建并返回SubAgentSpec实例
			return new SubAgentSpec(this);
		}
	}
}

