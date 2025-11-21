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
package com.alibaba.cloud.ai.graph.agent.interceptor.toolemulator;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool interceptor that emulates specified tools using an LLM instead of executing them.
 *
 * This interceptor allows selective emulation of tools for testing purposes.
 * By default (when tools=null), all tools are emulated. You can specify which
 * tools to emulate by passing a list of tool names.
 *
 * Example:
 * // Emulate all tools (default behavior)
 * ToolEmulatorInterceptor emulator = ToolEmulatorInterceptor.builder()
 *     .model(chatModel)
 *     .build();
 *
 * // Emulate specific tools by name
 * ToolEmulatorInterceptor emulator = ToolEmulatorInterceptor.builder()
 *     .model(chatModel)
 *     .addTool("get_weather")
 *     .addTool("get_user_location")
 *     .build();
 *
 * // Emulate all except specified tools
 * ToolEmulatorInterceptor emulator = ToolEmulatorInterceptor.builder()
 *     .model(chatModel)
 *     .emulateAllTools(false)  // Only emulate specified tools
 *     .addTool("expensive_api")
 *     .build();
 */
public class ToolEmulatorInterceptor extends ToolInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ToolEmulatorInterceptor.class);

	// 用于模拟工具调用的聊天模型
	private final ChatModel emulatorModel;
	// 是否模拟所有工具的标志位
	private final boolean emulateAll;
	// 需要模拟的工具名称集合
	private final Set<String> toolsToEmulate;
	// 用于生成模拟响应的提示模板
	private final String promptTemplate;

	// 私有构造函数，通过Builder模式创建ToolEmulatorInterceptor实例
	private ToolEmulatorInterceptor(Builder builder) {
		// 设置模拟器模型
		this.emulatorModel = builder.emulatorModel;
		// 设置是否模拟所有工具
		this.emulateAll = builder.emulateAll;
		// 复制需要模拟的工具集合
		this.toolsToEmulate = new HashSet<>(builder.toolsToEmulate);
		// 设置提示模板
		this.promptTemplate = builder.promptTemplate;
	}

	// 静态方法，返回Builder实例用于创建ToolEmulatorInterceptor
	public static Builder builder() {
		return new Builder();
	}

	// 获取拦截器名称的方法
	@Override
	public String getName() {
		return "ToolEmulator";
	}

	// 拦截工具调用的核心方法
	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		// 获取工具名称
		String toolName = request.getToolName();

		// 检查此工具是否应该被模拟
		boolean shouldEmulate = emulateAll || toolsToEmulate.contains(toolName);

		// 如果不应该模拟，则正常执行工具调用
		if (!shouldEmulate) {
			// 通过调用处理器让工具正常执行
			return handler.call(request);
		}

		// 记录日志，开始模拟工具调用
		log.info("Emulating tool call: {}", toolName);

		try {
			// 为模拟器LLM构建提示
			String prompt = String.format(promptTemplate,
					toolName,
					"No description available", // TODO: ToolCallRequest中没有工具描述信息
					request.getArguments());

			// 从LLM获取模拟响应
			ChatResponse response = emulatorModel.call(new Prompt(new UserMessage(prompt)));
			// 获取模拟结果文本
			String emulatedResult = response.getResult().getOutput().getText();

			// 记录调试日志，显示模拟工具的返回结果
			log.debug("Emulated tool '{}' returned: {}", toolName,
					emulatedResult.length() > 100 ? emulatedResult.substring(0, 100) + "..." : emulatedResult);

			// 短路处理：返回模拟结果而不执行真实工具
			return ToolCallResponse.of(request.getToolCallId(), toolName, emulatedResult);

		}
		// 捕获模拟过程中出现的异常
		catch (Exception e) {
			// 记录错误日志，模拟工具调用失败
			log.error("Failed to emulate tool call for: {}", toolName, e);
			// 在模拟失败时回退到实际执行
			return handler.call(request);
		}
	}

	// 构建器类，用于创建ToolEmulatorInterceptor实例
	public static class Builder {
		// 存储需要模拟的工具名称集合
		private final Set<String> toolsToEmulate = new HashSet<>();
		// 模拟器使用的聊天模型
		private ChatModel emulatorModel;
		// 是否模拟所有工具，默认为true（模拟所有工具）
		private boolean emulateAll = true; // Default: emulate all tools
		// 默认的提示模板，用于生成模拟响应
		private String promptTemplate = """
				You are emulating a tool call for testing purposes.
				
				Tool: %s
				Description: %s
				Arguments: %s
				
				Generate a realistic response that this tool would return given these arguments.
				Return ONLY the tool's output, no explanation or preamble.
				Introduce variation into your responses.
				""";

		/**
		 * Set the chat model used for emulation.
		 * Required.
		 */
		// 设置用于模拟的聊天模型，这是必需的
		public Builder model(ChatModel model) {
			this.emulatorModel = model;
			return this;
		}

		/**
		 * Add a tool name to emulate.
		 * If emulateAllTools is true, this is ignored.
		 */
		// 添加要模拟的工具名称，如果emulateAllTools为true，则忽略此设置
		public Builder addTool(String toolName) {
			this.toolsToEmulate.add(toolName);
			return this;
		}

		/**
		 * Add multiple tool names to emulate.
		 */
		// 添加多个要模拟的工具名称
		public Builder addTools(Collection<String> toolNames) {
			this.toolsToEmulate.addAll(toolNames);
			return this;
		}

		/**
		 * Set whether to emulate all tools or only specified ones.
		 * Default is true (emulate all tools).
		 *
		 * Set to false to only emulate tools added via addTool().
		 */
		// 设置是否模拟所有工具还是仅模拟指定的工具，默认为true（模拟所有工具）
		public Builder emulateAllTools(boolean emulateAll) {
			this.emulateAll = emulateAll;
			return this;
		}

		/**
		 * Set a custom prompt template for emulation.
		 * The template should accept 3 string format arguments:
		 * 1. Tool name
		 * 2. Tool description
		 * 3. Tool arguments (JSON)
		 */
		// 设置自定义的提示模板用于模拟
		public Builder promptTemplate(String template) {
			this.promptTemplate = template;
			return this;
		}

		// 构建ToolEmulatorInterceptor实例
		public ToolEmulatorInterceptor build() {
			// 检查模拟器模型是否已设置
			if (emulatorModel == null) {
				throw new IllegalStateException("Emulator model is required");
			}
			// 创建并返回ToolEmulatorInterceptor实例
			return new ToolEmulatorInterceptor(this);
		}
	}
}

