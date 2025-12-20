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

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Middleware to patch dangling tool calls in the messages history.
 *
 * <p>This interceptor handles situations where an AI message contains tool calls
 * that don't have corresponding ToolResponseMessages in the conversation history.
 * This can happen when tool execution is interrupted or when new messages arrive
 * before tool responses can be added.</p>
 *
 * <p>The interceptor automatically adds cancellation messages for any dangling
 * tool calls, preventing errors when the conversation is sent to the model.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * PatchToolCallsInterceptor interceptor = PatchToolCallsInterceptor.builder().build();
 * agent.addInterceptor(interceptor);
 * </pre>
 */
public class PatchToolCallsInterceptor extends ModelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(PatchToolCallsInterceptor.class);

	// 取消消息模板，用于生成工具调用取消的提示信息
	private static final String CANCELLATION_MESSAGE_TEMPLATE =
			"Tool call %s with id %s was cancelled - another message came in before it could be completed.";

	private PatchToolCallsInterceptor(Builder builder) {
		// 目前没有配置选项，但构建器模式允许未来扩展
	}

	// 构建器工厂方法
	public static Builder builder() {
		return new Builder();
	}

	// 获取拦截器名称
	@Override
	public String getName() {
		return "PatchToolCalls";
	}

	// 模型调用拦截处理方法
	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		// 获取请求中的消息列表
		List<Message> messages = request.getMessages();

		// 如果消息列表为空，直接调用处理器
		if (messages == null || messages.isEmpty()) {
			return handler.call(request);
		}

		// 修补悬空的工具调用
		List<Message> patchedMessages = patchDanglingToolCalls(messages);

		// 如果消息被修补过，创建新的请求并调用处理器
		if (patchedMessages != messages) {
			ModelRequest patchedRequest = ModelRequest.builder(request)
					.messages(patchedMessages)
					.build();
			return handler.call(patchedRequest);
		}

		// 否则直接调用处理器
		return handler.call(request);
	}

	/**
	 * Patch dangling tool calls by adding ToolResponseMessages for any tool calls
	 * that don't have corresponding responses.
	 *
	 * @param messages The original message list
	 * @return A new list with patched messages, or the original list if no patching needed
	 */
	private List<Message> patchDanglingToolCalls(List<Message> messages) {
		// 创建修补后的消息列表
		List<Message> patchedMessages = new ArrayList<>();
		// 标记是否有修补操作
		boolean hasPatches = false;

		// 构建所有工具响应ID的映射，用于快速查找
		Set<String> existingToolResponseIds = new HashSet<>();
		for (Message msg : messages) {
			if (msg instanceof ToolResponseMessage toolResponseMsg) {
				for (ToolResponseMessage.ToolResponse response : toolResponseMsg.getResponses()) {
					existingToolResponseIds.add(response.id());
				}
			}
		}

		// 遍历消息并修补悬空的工具调用
		for (int i = 0; i < messages.size(); i++) {
			Message msg = messages.get(i);
			// 将消息添加到修补列表中
			patchedMessages.add(msg);

			// 检查是否为包含工具调用的助手消息
			if (msg instanceof AssistantMessage assistantMsg) {
				List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();

				// 如果存在工具调用
				if (!toolCalls.isEmpty()) {
					// 检查每个工具调用是否有对应的响应
					List<ToolResponseMessage.ToolResponse> missingResponses = new ArrayList<>();

					for (AssistantMessage.ToolCall toolCall : toolCalls) {
						String toolCallId = toolCall.id();

						// 检查响应是否存在于剩余的消息中
						boolean hasResponse = existingToolResponseIds.contains(toolCallId);

						// 如果没有响应
						if (!hasResponse) {
							// 发现悬空的工具调用 - 创建取消响应
							String cancellationMsg = String.format(
									CANCELLATION_MESSAGE_TEMPLATE,
									toolCall.name(),
									toolCallId
							);

							missingResponses.add(new ToolResponseMessage.ToolResponse(
									toolCallId,
									toolCall.name(),
									cancellationMsg
							));

							// 记录日志
							log.info("Patching dangling tool call: {} (id: {})", toolCall.name(), toolCallId);
						}
					}

					// 添加包含所有缺失响应的ToolResponseMessage
					if (!missingResponses.isEmpty()) {
						Map<String, Object> metadata = new HashMap<>();
						metadata.put("patched", true);
						patchedMessages.add(ToolResponseMessage.builder().responses(missingResponses).metadata(metadata).build());
						hasPatches = true;
					}
				}
			}
		}

		// 如果有修补操作，返回修补后的消息列表，否则返回原始列表
		return hasPatches ? patchedMessages : messages;
	}

	/**
	 * Builder for creating PatchToolCallsInterceptor instances.
	 */
	public static class Builder {

		public Builder() {
		}

		/**
		 * Build the PatchToolCallsInterceptor instance.
		 * @return A new PatchToolCallsInterceptor
		 */
		public PatchToolCallsInterceptor build() {
			return new PatchToolCallsInterceptor(this);
		}
	}
}

