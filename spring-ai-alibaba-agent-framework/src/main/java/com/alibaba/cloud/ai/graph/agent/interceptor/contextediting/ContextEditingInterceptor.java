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
package com.alibaba.cloud.ai.graph.agent.interceptor.contextediting;

import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Context editing interceptor that clears older tool results once the conversation
 * grows beyond a configurable token threshold.
 *
 * This mirrors Anthropic's context editing capabilities by managing tool result
 * history to stay within token limits.
 *
 * Example:
 * ContextEditingInterceptor interceptor = ContextEditingInterceptor.builder()
 *     .trigger(100000)
 *     .keep(3)
 *     .clearAtLeast(1000)
 *     .build();
 */
public class ContextEditingInterceptor extends ModelInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ContextEditingInterceptor.class);
	// 定义默认的占位符字符串，用于替换被清除的工具结果
	private static final String DEFAULT_PLACEHOLDER = "[cleared]";

	// 触发上下文编辑的token阈值
	private final int trigger;
	// 每次至少需要清除的token数量
	private final int clearAtLeast;
	// 保留最近的工具消息数量，不进行清除
	private final int keep;
	// 是否清除工具输入参数
	private final boolean clearToolInputs;
	// 需要排除不被清除的工具集合
	private final Set<String> excludeTools;
	// 用于替换被清除内容的占位符
	private final String placeholder;
	// token计数器，用于计算消息的token数量
	private final TokenCounter tokenCounter;

	// 私有构造函数，通过Builder模式创建ContextEditingInterceptor实例
	private ContextEditingInterceptor(Builder builder) {
		// 设置触发阈值
		this.trigger = builder.trigger;
		// 设置至少清除的token数量
		this.clearAtLeast = builder.clearAtLeast;
		// 设置保留的最近消息数量
		this.keep = builder.keep;
		// 设置是否清除工具输入
		this.clearToolInputs = builder.clearToolInputs;
		// 设置排除工具列表，如果为空则创建空的HashSet
		this.excludeTools = builder.excludeTools != null
				? new HashSet<>(builder.excludeTools)
				: new HashSet<>();
		// 设置占位符
		this.placeholder = builder.placeholder;
		// 设置token计数器，默认使用近似计数器
		this.tokenCounter = builder.tokenCounter;
	}

	// 静态方法，返回Builder实例用于创建ContextEditingInterceptor
	public static Builder builder() {
		return new Builder();
	}

	// 拦截模型调用的核心方法
	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		// 创建消息列表的副本
		List<Message> messages = new ArrayList<>(request.getMessages());

		// 计算当前消息的token总数
		int tokens = tokenCounter.countTokens(messages);

		// 如果token数量未超过触发阈值，则直接调用处理器
		if (tokens <= trigger) {
			// Token count is below trigger, no editing needed
			return handler.call(request);
		}

		// 记录日志，token数量超过触发阈值
		log.info("Token count {} exceeds trigger {}, clearing tool results", tokens, trigger);

		// 查找可以被清除的工具消息候选列表
		List<ClearableToolMessage> candidates = findClearableCandidates(messages);

		// 如果没有可清除的消息，则直接调用处理器
		if (candidates.isEmpty()) {
			log.debug("No tool messages to clear");
			return handler.call(request);
		}

		// 已清除的token数量计数器
		int clearedTokens = 0;
		// 需要清除的消息索引集合
		Set<Integer> indicesToClear = new HashSet<>();

		// 遍历候选消息，直到达到至少清除的token数量
		for (ClearableToolMessage candidate : candidates) {
			// 如果已清除的token数量达到阈值，则停止清除
			if (clearedTokens >= clearAtLeast) {
				break;
			}

			// 将当前候选消息索引添加到清除列表
			indicesToClear.add(candidate.index);
			// 累加已清除的token数量
			clearedTokens += candidate.estimatedTokens;
		}

		// 构建更新后的消息列表
		List<Message> updatedMessages = new ArrayList<>();
		for (int i = 0; i < messages.size(); i++) {
			// 获取当前消息
			Message msg = messages.get(i);

			// 如果当前消息索引在清除列表中
			if (indicesToClear.contains(i)) {
				// 如果是工具响应消息
				if (msg instanceof ToolResponseMessage) {
					ToolResponseMessage toolMsg = (ToolResponseMessage) msg;
					// 创建清除后的响应列表
					List<ToolResponseMessage.ToolResponse> clearedResponses = new ArrayList<>();

					// 遍历所有响应，用占位符替换响应数据
					for (ToolResponseMessage.ToolResponse resp : toolMsg.getResponses()) {
						clearedResponses.add(new ToolResponseMessage.ToolResponse(
								resp.id(), resp.name(), placeholder));
					}

					// 添加更新后的工具响应消息
					updatedMessages.add(new ToolResponseMessage(clearedResponses, toolMsg.getMetadata()));
				}
				// 如果是助手消息
				else if (msg instanceof AssistantMessage) {
					AssistantMessage assistantMsg = (AssistantMessage) msg;
					// 创建清除后的工具调用列表
					List<AssistantMessage.ToolCall> clearedToolCalls = new ArrayList<>();

					// 清除工具调用参数，用占位符替换
					if (assistantMsg.getToolCalls() != null) {
						for (AssistantMessage.ToolCall toolCall : assistantMsg.getToolCalls()) {
							clearedToolCalls.add(new AssistantMessage.ToolCall(
									toolCall.id(), toolCall.type(), toolCall.name(), placeholder));
						}
					}

					// 创建新的助手消息，包含清除后的工具调用
					AssistantMessage clearedAssistantMsg = new AssistantMessage(
							assistantMsg.getText(),
							assistantMsg.getMetadata(),
							clearedToolCalls
					);
					updatedMessages.add(clearedAssistantMsg);
				}
			}
			// 如果不需要清除，则直接添加原消息
			else {
				updatedMessages.add(msg);
			}
		}

		// 如果有清除token
		if (clearedTokens > 0) {
			// 记录清除日志
			log.info("Cleared approximately {} tokens from {} tool messages",
					clearedTokens, indicesToClear.size());

			// 创建包含更新消息的新请求
			ModelRequest updatedRequest = ModelRequest.builder(request)
					.messages(updatedMessages)
					.build();

			// 调用处理器处理更新后的请求
			return handler.call(updatedRequest);
		}

		// 如果没有清除任何token，则调用原始请求
		return handler.call(request);
	}

	// 查找可清除的工具消息候选列表
	private List<ClearableToolMessage> findClearableCandidates(List<Message> messages) {
		// 创建候选列表
		List<ClearableToolMessage> candidates = new ArrayList<>();

		// 遍历所有消息，查找工具消息
		for (int i = 0; i < messages.size(); i++) {
			// 获取当前消息
			Message msg = messages.get(i);

			// 如果是工具响应消息
			if (msg instanceof ToolResponseMessage toolMsg) {

				// 检查是否已经被清除
				boolean alreadyCleared = false;
				for (ToolResponseMessage.ToolResponse resp : toolMsg.getResponses()) {
					if (placeholder.equals(resp.responseData())) {
						alreadyCleared = true;
						break;
					}
				}

				// 如果已经清除，则跳过
				if (alreadyCleared) {
					continue;
				}

				// 检查工具是否在排除列表中
				boolean excluded = false;
				for (ToolResponseMessage.ToolResponse resp : toolMsg.getResponses()) {
					if (excludeTools.contains(resp.name())) {
						excluded = true;
						break;
					}
				}

				// 如果被排除，则跳过
				if (excluded) {
					continue;
				}

				// 计算消息的token数量并添加到候选列表
				int tokens = TokenCounter.approximateMsgCounter().countTokens(List.of(toolMsg));
				candidates.add(new ClearableToolMessage(i, tokens));
			}
			// 如果是助手消息
			else if (msg instanceof AssistantMessage assistantMsg) {

				// 检查消息是否包含工具调用
				if (assistantMsg.getToolCalls().isEmpty()) {
					continue;
				}

				// 检查是否已经被清除（工具调用参数为占位符）
				boolean alreadyCleared = false;
				for (AssistantMessage.ToolCall toolCall : assistantMsg.getToolCalls()) {
					if (placeholder.equals(toolCall.arguments())) {
						alreadyCleared = true;
						break;
					}
				}

				// 如果已经清除，则跳过
				if (alreadyCleared) {
					continue;
				}

				// 检查工具是否在排除列表中
				boolean excluded = false;
				for (AssistantMessage.ToolCall toolCall : assistantMsg.getToolCalls()) {
					if (excludeTools.contains(toolCall.name())) {
						excluded = true;
						break;
					}
				}

				// 如果被排除，则跳过
				if (excluded) {
					continue;
				}

				// 计算消息的token数量并添加到候选列表
				int tokens = TokenCounter.approximateMsgCounter().countTokens(List.of(assistantMsg));
				candidates.add(new ClearableToolMessage(i, tokens));
			}
		}

		// 按照从旧到新的顺序排序，排除最近保留的消息
		if (candidates.size() > keep) {
			candidates = candidates.subList(0, candidates.size() - keep);
		}
		else {
			candidates.clear();
		}

		// 返回候选列表
		return candidates;
	}


	@Override
	// 获取拦截器名称的方法
	public String getName() {
		return "ContextEditing";
	}

	// 定义可清除工具消息的内部类，用于存储消息索引和预估token数
	private static class ClearableToolMessage {
		// 消息在列表中的索引位置
		final int index;
		// 消息预估的token数量
		final int estimatedTokens;

		// 构造函数，初始化索引和token数
		ClearableToolMessage(int index, int estimatedTokens) {
			this.index = index;
			this.estimatedTokens = estimatedTokens;
		}
	}

	// 构建器类，用于创建ContextEditingInterceptor实例
	public static class Builder {
		// 触发上下文编辑的token阈值，默认100000
		private int trigger = 100000;
		// 每次至少需要清除的token数量，默认0
		private int clearAtLeast = 0;
		// 保留最近的工具消息数量，默认3条
		private int keep = 3;
		// 是否清除工具输入参数，默认false
		private boolean clearToolInputs = false;
		// 需要排除不被清除的工具集合
		private Set<String> excludeTools;
		// 用于替换被清除内容的占位符，默认"[cleared]"
		private String placeholder = DEFAULT_PLACEHOLDER;
		// token计数器，默认使用近似计数器
		private TokenCounter tokenCounter = TokenCounter.approximateMsgCounter();

		// 设置触发阈值
		public Builder trigger(int trigger) {
			this.trigger = trigger;
			return this;
		}

		// 设置至少清除的token数量
		public Builder clearAtLeast(int clearAtLeast) {
			this.clearAtLeast = clearAtLeast;
			return this;
		}

		// 设置保留的最近消息数量
		public Builder keep(int keep) {
			this.keep = keep;
			return this;
		}

		// 设置是否清除工具输入参数
		public Builder clearToolInputs(boolean clearToolInputs) {
			this.clearToolInputs = clearToolInputs;
			return this;
		}

		// 设置排除工具列表
		public Builder excludeTools(Set<String> excludeTools) {
			this.excludeTools = excludeTools;
			return this;
		}

		// 设置排除工具列表（可变参数版本）
		public Builder excludeTools(String... toolNames) {
			this.excludeTools = new HashSet<>(Arrays.asList(toolNames));
			return this;
		}

		// 设置占位符
		public Builder placeholder(String placeholder) {
			this.placeholder = placeholder;
			return this;
		}

		// 设置token计数器
		public Builder tokenCounter(TokenCounter tokenCounter) {
			this.tokenCounter = tokenCounter;
			return this;
		}

		// 构建ContextEditingInterceptor实例
		public ContextEditingInterceptor build() {
			return new ContextEditingInterceptor(this);
		}
	}
}

