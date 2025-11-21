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
package com.alibaba.cloud.ai.graph.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.alibaba.cloud.ai.graph.state.RemoveByHash;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.InterceptorChain;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY;
import static com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants.AGENT_STATE_CONTEXT_KEY;
import static com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants.AGENT_STATE_FOR_UPDATE_CONTEXT_KEY;
import static com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver.THREAD_ID_DEFAULT;

public class AgentToolNode implements NodeActionWithConfig {
	// 工具节点名称常量
	public static final String TOOL_NODE_NAME = "tool";
	// 日志记录器
	private static final Logger logger = LoggerFactory.getLogger(AgentToolNode.class);

	// Agent名称
	private final String agentName;

	// 是否启用执行日志
	private boolean enableActingLog;

	// 工具回调列表
	private List<ToolCallback> toolCallbacks;

	// 工具拦截器列表
	private List<ToolInterceptor> toolInterceptors = new ArrayList<>();

	// 工具回调解析器
	private ToolCallbackResolver toolCallbackResolver;

	// 构造函数，使用Builder模式初始化AgentToolNode
	public AgentToolNode(Builder builder) {
		// 初始化agentName
		this.agentName = builder.agentName;
		// 初始化enableActingLog
		this.enableActingLog = builder.enableActingLog;
		// 初始化toolCallbackResolver
		this.toolCallbackResolver = builder.toolCallbackResolver;
		// 初始化toolCallbacks
		this.toolCallbacks = builder.toolCallbacks;
	}

	// 设置工具回调列表
	public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
		this.toolCallbacks = toolCallbacks;
	}

	// 设置工具拦截器列表
	public void setToolInterceptors(List<ToolInterceptor> toolInterceptors) {
		this.toolInterceptors = toolInterceptors;
	}

	// 设置工具回调解析器
	void setToolCallbackResolver(ToolCallbackResolver toolCallbackResolver) {
		this.toolCallbackResolver = toolCallbackResolver;
	}

	// 获取工具回调列表
	public List<ToolCallback> getToolCallbacks() {
		return toolCallbacks;
	}

	// 应用节点逻辑的核心方法
	@Override
	public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
		// 获取消息列表
		List<Message> messages = (List<Message>) state.value("messages").orElseThrow();
		// 获取最后一条消息
		Message lastMessage = messages.get(messages.size() - 1);

		// 更新状态映射
		Map<String, Object> updatedState = new HashMap<>();
		// 工具调用产生的额外状态
		Map<String, Object> extraStateFromToolCall = new HashMap<>();
		// 如果最后一条消息是AssistantMessage
		if (lastMessage instanceof AssistantMessage assistantMessage) {
			// 执行工具函数
			List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

			// 如果启用了执行日志，则记录工具调用信息
			if (enableActingLog) {
				logger.info("[ThreadId {}] Agent {} acting with {} tools.", config.threadId().orElse(THREAD_ID_DEFAULT), agentName, assistantMessage.getToolCalls().size());
			}

			// 遍历所有工具调用
			for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
				// 使用拦截器链执行工具调用
				ToolCallResponse response = executeToolCallWithInterceptors(toolCall, state, config, extraStateFromToolCall);
				// 将响应添加到工具响应列表
				toolResponses.add(response.toToolResponse());
			}

			// 创建工具响应消息
			ToolResponseMessage toolResponseMessage = new ToolResponseMessage(toolResponses, Map.of());

			// 如果启用了执行日志，则记录返回信息
			if (enableActingLog) {
				logger.info("[ThreadId {}] Agent {} acting returned: {}", config.threadId().orElse(THREAD_ID_DEFAULT), agentName, toolResponseMessage);
			}

			// 将工具响应消息放入更新状态
			updatedState.put("messages", toolResponseMessage);
		// 如果最后一条消息是ToolResponseMessage
		} else if (lastMessage instanceof ToolResponseMessage toolResponseMessage) {
			// 如果消息数量小于2，则抛出异常
			if (messages.size() < 2) {
				throw new IllegalStateException("Cannot find AssistantMessage before ToolResponseMessage");
			}
			// 获取倒数第二条消息
			Message secondLastMessage = messages.get(messages.size() - 2);
			// 如果倒数第二条消息不是AssistantMessage，则抛出异常
			if (!(secondLastMessage instanceof AssistantMessage assistantMessage)) {
				throw new IllegalStateException("Message before ToolResponseMessage is not an AssistantMessage");
			}

			// 获取已存在的响应列表
			List<ToolResponseMessage.ToolResponse> existingResponses = toolResponseMessage.getResponses();
			// 创建所有响应的列表
			List<ToolResponseMessage.ToolResponse> allResponses = new ArrayList<>(existingResponses);

			// 收集已执行的工具名称
			Set<String> executedToolNames = existingResponses.stream()
					.map(ToolResponseMessage.ToolResponse::name)
					.collect(Collectors.toSet());

			// 如果启用了执行日志，则记录工具调用信息
			if (enableActingLog) {
				logger.info("[ThreadId {}] Agent {} acting with {} tools ({} tools provided results).", config.threadId().orElse(THREAD_ID_DEFAULT), agentName, assistantMessage.getToolCalls().size(), existingResponses.size());
			}

			// 遍历所有工具调用
			for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
				// 如果工具已经执行过，则跳过
				if (executedToolNames.contains(toolCall.name())) {
					continue;
				}

				// 使用拦截器链执行工具调用
				ToolCallResponse response = executeToolCallWithInterceptors(toolCall, state, config, extraStateFromToolCall);
				// 将响应添加到所有响应列表
				allResponses.add(response.toToolResponse());
			}

			// 创建新的消息列表
			List<Object> newMessages = new ArrayList<>();
			// 创建新的工具响应消息
			ToolResponseMessage newToolResponseMessage = new ToolResponseMessage(allResponses, Map.of());
			// 添加新的工具响应消息
			newMessages.add(newToolResponseMessage);
			// 添加移除标记
			newMessages.add(new RemoveByHash<>(assistantMessage));
			// 将新消息列表放入更新状态
			updatedState.put("messages", newMessages);

			// 如果启用了执行日志，则记录成功返回信息
			if (enableActingLog) {
				logger.info("[ThreadId {}] Agent {} acting successfully returned.", config.threadId()
						.orElse(THREAD_ID_DEFAULT), agentName);
				// 如果处于调试级别，则记录详细返回信息
				if (logger.isDebugEnabled()) {
					logger.debug("[ThreadId {}] Agent {} acting returned: {}", config.threadId()
							.orElse(THREAD_ID_DEFAULT), agentName, toolResponseMessage);
				}
			}

		// 如果最后一条消息既不是AssistantMessage也不是ToolResponseMessage，则抛出异常
		} else {
			throw new IllegalStateException("Last message is not an AssistantMessage or ToolResponseMessage");
		}

		// 合并工具调用产生的额外状态
		updatedState.putAll(extraStateFromToolCall);
		// 返回更新后的状态
		return updatedState;
	}

	/**
	 * 使用拦截器链支持执行工具调用。
	 */
	// 使用拦截器链执行工具调用的方法
	private ToolCallResponse executeToolCallWithInterceptors(
			AssistantMessage.ToolCall toolCall,
			OverAllState state,
			RunnableConfig config,
			Map<String, Object> extraStateFromToolCall) {

		// 创建ToolCallRequest
		ToolCallRequest request = ToolCallRequest.builder()
				.toolCall(toolCall)
				.context(config.metadata().orElse(new HashMap<>()))
				.build();

		// 创建实际执行工具的基础处理器
		ToolCallHandler baseHandler = req -> {
			// 解析工具回调
			ToolCallback toolCallback = resolve(req.getToolName());

			// 如果启用了执行日志，则记录正在执行的工具信息
			if (enableActingLog) {
				logger.info("[ThreadId {}] Agent {} acting, executing tool {}.", config.threadId().orElse(THREAD_ID_DEFAULT), agentName, req.getToolName());
			}

			// 执行结果
			String result;
			try {
				// FIXME, currently only FunctionToolCallback supports ToolContext.
				// 如果工具回调是FunctionToolCallback实例
				if (toolCallback instanceof FunctionToolCallback<?, ?>) {
					// 调用工具并传入工具上下文
					result = toolCallback.call(
							req.getArguments(),
							new ToolContext(Map.of(AGENT_STATE_CONTEXT_KEY, state, AGENT_CONFIG_CONTEXT_KEY, config, AGENT_STATE_FOR_UPDATE_CONTEXT_KEY, extraStateFromToolCall))
					);
				}
				// 如果工具回调不是FunctionToolCallback实例，则认为是MCP工具
				else { // toolCallbacks not instance of FunctionToolCallback are considered MCP tools.
					// 直接调用工具
					result = toolCallback.call(req.getArguments());
				}

				// 如果启用了执行日志，则记录工具完成信息
				if (enableActingLog) {
					logger.info("[ThreadId {}] Agent {} acting, tool {} finished", config.threadId()
									.orElse(THREAD_ID_DEFAULT), agentName, req.getToolName());
					// 如果处于调试级别，则记录工具返回结果
					if (logger.isDebugEnabled()) {
						logger.debug("Tool {} returned: {}", req.getToolName(), result);
					}
				}
			// 捕获执行异常
			} catch (Exception e) {
				// 记录错误日志
				logger.error("[ThreadId {}] Agent {} acting, tool {} execution failed. "
						+ "The agent loop has ended, please use ToolRetryInterceptor to customize the retry and policy on tool failure. \n"
						, config.threadId().orElse(THREAD_ID_DEFAULT), agentName, req.getToolName(), e);
				// 抛出异常
				throw e;
			}

			// 返回工具调用响应
			return ToolCallResponse.of(req.getToolCallId(), req.getToolName(), result);
		};

		// 如果有拦截器则链接它们
		ToolCallHandler chainedHandler = InterceptorChain.chainToolInterceptors(
			toolInterceptors, baseHandler);

		// 执行链接后的处理器
		return chainedHandler.call(request);
	}

	// 解析工具回调的方法
	private ToolCallback resolve(String toolName) {
		// 在工具回调列表中查找匹配的工具
		return toolCallbacks.stream()
			.filter(callback -> callback.getToolDefinition().name().equals(toolName))
			.findFirst()
			// 如果未找到则使用工具回调解析器解析
			.orElseGet(() -> toolCallbackResolver.resolve(toolName));
	}

	// 获取节点名称
	public String getName() {
		return TOOL_NODE_NAME;
	}

	// 静态工厂方法，返回Builder实例
	public static Builder builder() {
		return new Builder();
	}

	// Builder内部类，用于构建AgentToolNode实例
	public static class Builder {

		// Agent名称
		private String agentName;

		// 是否启用执行日志
		private boolean enableActingLog;

		// 工具回调列表
		private List<ToolCallback> toolCallbacks = new ArrayList<>();

		// 工具名称列表
		private List<String> toolNames = new ArrayList<>();

		// 工具回调解析器
		private ToolCallbackResolver toolCallbackResolver;

		// 私有构造函数
		private Builder() {
		}

		// 设置agentName
		public Builder agentName(String agentName) {
			this.agentName = agentName;
			return this;
		}

		// 设置enableActingLog
		public Builder enableActingLog(boolean enableActingLog) {
			this.enableActingLog = enableActingLog;
			return this;
		}

		// 设置toolCallbacks
		public Builder toolCallbacks(List<ToolCallback> toolCallbacks) {
			this.toolCallbacks = toolCallbacks;
			return this;
		}

		// 设置toolNames
		public Builder toolNames(List<String> toolNames) {
			this.toolNames = toolNames;
			return this;
		}

		// 设置toolCallbackResolver
		public Builder toolCallbackResolver(ToolCallbackResolver toolCallbackResolver) {
			this.toolCallbackResolver = toolCallbackResolver;
			return this;
		}

		// 构建AgentToolNode实例
		public AgentToolNode build() {
			return new AgentToolNode(this);
		}

	}

}

