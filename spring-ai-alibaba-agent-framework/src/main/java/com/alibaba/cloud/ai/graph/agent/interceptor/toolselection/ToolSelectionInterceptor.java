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
package com.alibaba.cloud.ai.graph.agent.interceptor.toolselection;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uses an LLM to select relevant tools before calling the main model.
 *
 * When an agent has many tools available, this interceptor filters them down
 * to only the most relevant ones for the user's query. This reduces token usage
 * and helps the main model focus on the right tools.
 *
 * Example:
 * ToolSelectionInterceptor interceptor = ToolSelectionInterceptor.builder()
 *     .selectionModel(gpt4oMini)
 *     .maxTools(3)
 *     .build();
 */
public class ToolSelectionInterceptor extends ModelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ToolSelectionInterceptor.class);

	private static final String DEFAULT_SYSTEM_PROMPT =
			"Your goal is to select the most relevant tools for answering the user's query.";

	private final ChatModel selectionModel;
	private final String systemPrompt;
	private final Integer maxTools;
	private final Set<String> alwaysInclude;
	private final ObjectMapper objectMapper;

	private ToolSelectionInterceptor(Builder builder) {
		// 初始化选择模型
		this.selectionModel = builder.selectionModel;
		// 设置系统提示语，默认为DEFAULT_SYSTEM_PROMPT
		this.systemPrompt = builder.systemPrompt;
		// 最大工具数限制，可为空
		this.maxTools = builder.maxTools;
		// 始终包含的工具集合，如果builder中未设置则初始化为空HashSet
		this.alwaysInclude = builder.alwaysInclude != null
				? new HashSet<>(builder.alwaysInclude)
				: new HashSet<>();
		// 创建ObjectMapper实例用于解析JSON响应
		this.objectMapper = new ObjectMapper();
	}

	public static Builder builder() {
		// 返回一个新的Builder实例
		return new Builder();
	}

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		// 获取请求中的可用工具列表
		List<String> availableTools = request.getTools();

		// 如果没有工具或者工具数量已经在限制范围内，则跳过筛选直接调用处理器
		if (availableTools == null || availableTools.isEmpty() ||
				(maxTools != null && availableTools.size() <= maxTools)) {
			return handler.call(request);
		}

		// 查找最后一条用户消息
		String lastUserQuery = findLastUserMessage(request.getMessages());
		if (lastUserQuery == null) {
			// 没有找到用户消息时记录日志并跳过工具选择
			log.debug("No user message found, skipping tool selection");
			return handler.call(request);
		}

		// 执行工具选择逻辑
		Set<String> selectedToolNames = selectTools(availableTools, lastUserQuery);

		// 记录选择结果的日志信息
		log.info("Selected {} tools from {} available: {}",
				selectedToolNames.size(), availableTools.size(), selectedToolNames);

		// 根据选择结果过滤工具列表
		List<String> filteredTools = availableTools.stream()
				.filter(selectedToolNames::contains)
				.collect(Collectors.toList());

		// 构建新的请求对象，其中只包含筛选后的工具
		ModelRequest filteredRequest = ModelRequest.builder(request)
				.tools(filteredTools)
				.build();

		// 调用下一个拦截器或最终处理程序
		return handler.call(filteredRequest);
	}

	private String findLastUserMessage(List<Message> messages) {
		// 从后往前遍历消息列表寻找最后一个UserMessage
		for (int i = messages.size() - 1; i >= 0; i--) {
			Message msg = messages.get(i);
			if (msg instanceof UserMessage) {
				// 找到UserMessage时返回其文本内容
				return msg.getText();
			}
		}
		// 没有找到UserMessage时返回null
		return null;
	}

	private Set<String> selectTools(List<String> toolNames, String userQuery) {
		try {
			// 构建工具列表字符串供提示词使用
			StringBuilder toolList = new StringBuilder();
			for (String toolName : toolNames) {
				toolList.append("- ").append(toolName).append("\n");
			}

			// 如果设置了最大工具数限制，则添加相应指令到提示词中
			String maxToolsInstruction = maxTools != null
					? "\nIMPORTANT: List the tool names in order of relevance. " +
					"Select at most " + maxTools + " tools."
					: "";

			// 创建用于工具选择的提示消息列表
			List<Message> selectionMessages = List.of(
					// 添加系统消息，包括基础提示和最大工具数限制说明
					new SystemMessage(systemPrompt + maxToolsInstruction),
					// 添加用户消息，包含可用工具列表、用户查询及响应格式要求
					new UserMessage("Available tools:\n" + toolList +
							"\nUser query: " + userQuery +
							"\n\nRespond with a JSON object containing a 'tools' array with the selected tool names: {\"tools\": [\"tool1\", \"tool2\"]}")
			);

			// 将消息封装成Prompt对象
			Prompt prompt = new Prompt(selectionMessages);
			// 使用选择模型进行推理调用
			var response = selectionModel.call(prompt);
			// 提取模型响应的文本内容
			String responseText = response.getResult().getOutput().getText();

			// 解析模型返回的JSON响应获取选中的工具名称
			Set<String> selected = parseToolSelection(responseText);

			// 添加始终应包含的工具
			selected.addAll(alwaysInclude);

			// 如果设定了最大工具数且当前选中数量超过此限制，则截断至限定数量
			if (maxTools != null && selected.size() > maxTools) {
				List<String> selectedList = new ArrayList<>(selected);
				selected = new HashSet<>(selectedList.subList(0, maxTools));
			}

			// 返回选中的工具集合
			return selected;

		}
		catch (Exception e) {
			// 工具选择过程中出现异常时记录警告日志，并使用全部工具继续执行
			log.warn("Tool selection failed, using all tools: {}", e.getMessage());
			return new HashSet<>(toolNames);
		}
	}

	private Set<String> parseToolSelection(String responseText) {
		try {
			// 尝试将响应文本解析为ToolSelectionResponse对象
			ToolSelectionResponse response = objectMapper.readValue(responseText, ToolSelectionResponse.class);
			// 返回解析出的工具名称列表转换成的Set
			return new HashSet<>(response.tools);
		}
		catch (Exception e) {
			// JSON解析失败时记录调试日志并返回空集合作为回退方案
			log.debug("Failed to parse JSON, using fallback extraction");
			return new HashSet<>();
		}
	}

	@Override
	public String getName() {
		// 返回拦截器名称"ToolSelection"
		return "ToolSelection";
	}

	// 定义用于接收工具选择响应的内部类
	private static class ToolSelectionResponse {
		// 使用JsonProperty注解标记tools字段对应JSON中的"tools"键
		@JsonProperty("tools")
		public List<String> tools;
	}

	// 构造器模式中的Builder类定义
	public static class Builder {
		// 选择模型实例
		private ChatModel selectionModel;
		// 系统提示语，默认值为DEFAULT_SYSTEM_PROMPT
		private String systemPrompt = DEFAULT_SYSTEM_PROMPT;
		// 最大工具数限制
		private Integer maxTools;
		// 始终应包含的工具集合
		private Set<String> alwaysInclude;

		public Builder selectionModel(ChatModel selectionModel) {
			// 设置选择模型
			this.selectionModel = selectionModel;
			return this;
		}

		public Builder systemPrompt(String systemPrompt) {
			// 设置系统提示语
			this.systemPrompt = systemPrompt;
			return this;
		}

		public Builder maxTools(int maxTools) {
			// 验证maxTools参数必须大于0
			if (maxTools <= 0) {
				throw new IllegalArgumentException("maxTools must be > 0");
			}
			// 设置最大工具数限制
			this.maxTools = maxTools;
			return this;
		}

		public Builder alwaysInclude(Set<String> alwaysInclude) {
			// 设置始终包含的工具集合
			this.alwaysInclude = alwaysInclude;
			return this;
		}

		public Builder alwaysInclude(String... toolNames) {
			// 通过变长参数设置始终包含的工具集合
			this.alwaysInclude = new HashSet<>(Arrays.asList(toolNames));
			return this;
		}

		public ToolSelectionInterceptor build() {
			// 验证必要属性selectionModel是否已设置
			if (selectionModel == null) {
				throw new IllegalStateException("selectionModel is required");
			}
			// 构造ToolSelectionInterceptor实例
			return new ToolSelectionInterceptor(this);
		}
	}
}

