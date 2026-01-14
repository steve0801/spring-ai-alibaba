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
package com.alibaba.cloud.ai.graph.agent.hook.hip;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.InterruptableAction;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback.FeedbackResult;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.state.RemoveByHash;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@HookPositions(HookPosition.AFTER_MODEL)
public class HumanInTheLoopHook extends ModelHook implements AsyncNodeActionWithConfig, InterruptableAction {
	private static final Logger log = LoggerFactory.getLogger(HumanInTheLoopHook.class);

	private Map<String, ToolConfig> approvalOn;

	private HumanInTheLoopHook(Builder builder) {
		// 从Builder构造函数中复制approvalOn映射
		this.approvalOn = new HashMap<>(builder.approvalOn);
	}

	public static Builder builder() {
		// 返回一个Builder实例用于创建HumanInTheLoopHook对象
		return new Builder();
	}

	@Override
	public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
		// 调用afterModel方法处理逻辑
		return afterModel(state, config);
	}

	@Override
	public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
		// 从配置元数据中获取人类反馈信息
		Optional<Object> feedback = config.metadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY);
		// 将反馈转换为InterruptionMetadata类型
		InterruptionMetadata interruptionMetadata = (InterruptionMetadata) feedback.orElse(null);

		if (interruptionMetadata == null) {
			// 如果没有找到人类反馈，则记录日志并返回空结果
			log.info("No human feedback found in the runnable config metadata, no tool to execute or none needs feedback.");
			return CompletableFuture.completedFuture(Map.of());
		}

		// 从状态中获取消息列表
		List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());
		// 获取最后一条消息
		Message lastMessage = messages.get(messages.size() - 1);

		if (lastMessage instanceof AssistantMessage assistantMessage) {

			if (!assistantMessage.hasToolCalls()) {
				// 如果最后一条消息不是工具调用，则记录日志并返回空结果
				log.info("Found human feedback but last AssistantMessage has no tool calls, nothing to process for human feedback.");
				return CompletableFuture.completedFuture(Map.of());
			}

			// 创建新的工具调用列表
			List<AssistantMessage.ToolCall> newToolCalls = new ArrayList<>();

			// 创建响应列表用于存储拒绝的工具调用
			List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
			// 创建拒绝消息对象
			ToolResponseMessage rejectedMessage = new ToolResponseMessage(responses);

			for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
				// 查找当前工具调用对应的反馈
				Optional<ToolFeedback> toolFeedbackOpt = interruptionMetadata.toolFeedbacks().stream()
						.filter(tf -> tf.getName().equals(toolCall.name()))
						.findFirst();

				if (toolFeedbackOpt.isPresent()) {
					// 获取工具反馈对象
					ToolFeedback toolFeedback = toolFeedbackOpt.get();
					// 获取反馈结果（批准、编辑、拒绝）
					FeedbackResult result = toolFeedback.getResult();

					if (result == FeedbackResult.APPROVED) {
						// 如果工具调用被批准，则添加到新工具调用列表中
						newToolCalls.add(toolCall);
					}
					else if (result == FeedbackResult.EDITED) {
						// 如果工具调用被编辑，则创建新的工具调用对象并添加到列表中
						AssistantMessage.ToolCall editedToolCall = new AssistantMessage.ToolCall(toolCall.id(), toolCall.type(), toolCall.name(), toolFeedback.getArguments());
						newToolCalls.add(editedToolCall);
					}
					else if (result == FeedbackResult.REJECTED) {
						// 如果工具调用被拒绝，则创建拒绝响应并添加到响应列表中
						ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), String.format("Tool call request for %s has been rejected by human. The reason for why this tool is rejected and the suggestion for next possible tool choose is listed as below:\n %s.", toolFeedback.getName(), toolFeedback.getDescription()));
						responses.add(response);
					}
				}
				else {
					// 如果没有为需要批准的工具提供反馈，则视为批准以继续执行
					newToolCalls.add(toolCall);
				}
			}

			// 创建更新映射
			Map<String, Object> updates = new HashMap<>();
			// 创建新消息列表
			List<Object> newMessages = new ArrayList<>();

			if (!rejectedMessage.getResponses().isEmpty()) {
				// 如果有拒绝的响应，则将拒绝消息添加到新消息列表中
				newMessages.add(rejectedMessage);
			}

			if (!newToolCalls.isEmpty()) {
				// 创建包含更新工具调用的新助手消息
				newMessages.add(new AssistantMessage(assistantMessage.getText(), assistantMessage.getMetadata(), newToolCalls, assistantMessage.getMedia()));
				// 添加RemoveByHash操作以移除原助手消息
				newMessages.add(new RemoveByHash<>(assistantMessage));
			}

			// 将新消息列表放入更新映射中
			updates.put("messages", newMessages);
			// 返回完成的更新映射
			return CompletableFuture.completedFuture(updates);
		}
		else {
			// 如果最后一条消息不是助手消息，则记录警告
			log.warn("Last message is not an AssistantMessage, cannot process human feedback.");
		}

		// 返回空的完成Future
		return CompletableFuture.completedFuture(Map.of());
	}

	@Override
	public Optional<InterruptionMetadata> interrupt(String nodeId, OverAllState state, RunnableConfig config) {
		// 从配置元数据中获取人类反馈信息
		Optional<Object> feedback = config.metadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY);
		if (feedback.isPresent()) {
			// 检查反馈类型是否为InterruptionMetadata
			if (!(feedback.get() instanceof InterruptionMetadata)) {
				throw new IllegalArgumentException("Human feedback metadata must be of type InterruptionMetadata.");
			}

			// 验证反馈是否有效
			if (!validateFeedback((InterruptionMetadata) feedback.get())) {
				return Optional.of((InterruptionMetadata)feedback.get());
			}
			return Optional.empty();
		}

		// 从状态中获取消息列表
		List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());
		// 获取最后一条消息
		Message lastMessage = messages.get(messages.size() - 1);

		if (lastMessage instanceof AssistantMessage assistantMessage) {
			// 2. 如果最后一条消息是助手消息
			if (assistantMessage.hasToolCalls()) {
				// 标记是否需要中断
				boolean needsInterruption = false;
				// 创建中断元数据构建器
				InterruptionMetadata.Builder builder = InterruptionMetadata.builder(getName(), state);
				for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
					// 检查当前工具调用是否需要批准
					if (approvalOn.containsKey(toolCall.name())) {
						// 获取工具配置
						ToolConfig toolConfig = approvalOn.get(toolCall.name());
						// 获取工具描述
						String description = toolConfig.getDescription();
						// 创建人类审批的提示内容
						String content = "The AI is requesting to use the tool: " + toolCall.name() + ".\n"
								+ (description != null ? ("Description: " + description + "\n") : "")
								+ "With the following arguments: " + toolCall.arguments() + "\n"
								+ "Do you approve?";
						// TODO, create a designated tool metadata field in InterruptionMetadata?
						// 向构建器添加工具反馈信息
						builder.addToolFeedback(InterruptionMetadata.ToolFeedback.builder().id(toolCall.id())
										.name(toolCall.name()).description(content).arguments(toolCall.arguments()).build())
								.build();
						// 设置需要中断标记
						needsInterruption = true;
					}
				}
				// 如果需要中断，则返回构建的中断元数据，否则返回空
				return needsInterruption ? Optional.of(builder.build()) : Optional.empty();
			}
		}
		// 返回空表示不需要中断
		return Optional.empty();
	}

	private boolean validateFeedback(InterruptionMetadata feedback) {
		// 检查反馈是否为空或工具反馈列表为空
		if (feedback == null || feedback.toolFeedbacks() == null || feedback.toolFeedbacks().isEmpty()) {
			return false;
		}

		// 获取工具反馈列表
		List<InterruptionMetadata.ToolFeedback> toolFeedbacks = feedback.toolFeedbacks();

		// 1. 确保每个ToolFeedback的结果不为空
		for (InterruptionMetadata.ToolFeedback toolFeedback : toolFeedbacks) {
			if (toolFeedback.getResult() == null) {
				return false;
			}
		}

		// 2. 确保ToolFeedback数量与approvalOn数量匹配且所有名称都在approvalOn中
		if (toolFeedbacks.size() != approvalOn.size()) {
			return false;
		}
		for (InterruptionMetadata.ToolFeedback toolFeedback : toolFeedbacks) {
			if (!approvalOn.containsKey(toolFeedback.getName())) {
				return false;
			}
		}

		// 验证通过返回true
		return true;
	}

	@Override
	public String getName() {
		// 返回钩子的名称
		return "HIP";
	}

	@Override
	public List<JumpTo> canJumpTo() {
		// 返回可以跳转到的位置列表（当前为空）
		return List.of();
	}

	public static class Builder {
		// 存储需要批准的工具配置映射
		private Map<String, ToolConfig> approvalOn = new HashMap<>();

		public Builder approvalOn(String toolName, ToolConfig toolConfig) {
			// 将工具名和配置添加到approvalOn映射中
			this.approvalOn.put(toolName, toolConfig);
			return this;
		}

		public Builder approvalOn(String toolName, String description) {
			// 创建工具配置对象并设置描述
			ToolConfig config = new ToolConfig();
			config.setDescription(description);
			// 将工具名和配置添加到映射中
			this.approvalOn.put(toolName, config);
			return this;
		}

		public Builder approvalOn(Map<String, ToolConfig> approvalOn) {
			// 将提供的映射中的所有条目添加到当前映射中
			this.approvalOn.putAll(approvalOn);
			return this;
		}

		public HumanInTheLoopHook build() {
			// 使用当前构建器创建HumanInTheLoopHook实例
			return new HumanInTheLoopHook(this);
		}
	}

}
