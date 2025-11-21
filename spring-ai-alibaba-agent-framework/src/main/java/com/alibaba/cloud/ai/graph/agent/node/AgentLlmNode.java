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
import com.alibaba.cloud.ai.graph.serializer.AgentInstructionMessage;
import com.alibaba.cloud.ai.graph.utils.TypeRef;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.InterceptorChain;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import org.springframework.util.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver.THREAD_ID_DEFAULT;

public class AgentLlmNode implements NodeActionWithConfig {
	// 模型节点名称常量
	public static final String MODEL_NODE_NAME = "model";
	// 日志记录器
	private static final Logger logger = LoggerFactory.getLogger(AgentLlmNode.class);
	// 模型迭代次数键名
	public static final String MODEL_ITERATION_KEY = "_MODEL_ITERATION_";

	// Agent名称
	private String agentName;

	// Advisor列表
	private List<Advisor> advisors = new ArrayList<>();

	// 工具回调列表
	private List<ToolCallback> toolCallbacks = new ArrayList<>();

	// 模型拦截器列表
	private List<ModelInterceptor> modelInterceptors = new ArrayList<>();

	// 输出键名
	private String outputKey;

	// 输出模式定义
	private String outputSchema;

	// 聊天客户端
	private ChatClient chatClient;

	// 系统提示词
	private String systemPrompt;

	// 指令
	private String instruction;

	// 工具调用选项
	private ToolCallingChatOptions toolCallingChatOptions;

	// 是否启用推理日志
	private boolean enableReasoningLog;

	// 构造函数，使用Builder模式初始化AgentLlmNode
	public AgentLlmNode(Builder builder) {
		// 初始化agentName
		this.agentName = builder.agentName;
		// 初始化outputKey
		this.outputKey = builder.outputKey;
		// 初始化outputSchema
		this.outputSchema = builder.outputSchema;
		// 初始化systemPrompt
		this.systemPrompt = builder.systemPrompt;
		// 初始化instruction
		this.instruction = builder.instruction;
		// 如果advisors不为空则初始化
		if (builder.advisors != null) {
			this.advisors = builder.advisors;
		}
		// 如果toolCallbacks不为空则初始化
		if (builder.toolCallbacks != null) {
			this.toolCallbacks = builder.toolCallbacks;
		}
		// 如果modelInterceptors不为空则初始化
		if (builder.modelInterceptors != null) {
			this.modelInterceptors = builder.modelInterceptors;
		}
		// 初始化chatClient
		this.chatClient = builder.chatClient;
		// 构建toolCallingChatOptions，禁用内部工具执行
		this.toolCallingChatOptions = ToolCallingChatOptions.builder()
				.toolCallbacks(toolCallbacks)
				.internalToolExecutionEnabled(false)
				.build();
		// 初始化enableReasoningLog
		this.enableReasoningLog = builder.enableReasoningLog;;
	}

	// 静态工厂方法，返回Builder实例
	public static Builder builder() {
		return new Builder();
	}

	// 设置工具回调列表
	public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
		this.toolCallbacks = toolCallbacks;
	}

	// 设置模型拦截器列表
	public void setModelInterceptors(List<ModelInterceptor> modelInterceptors) {
		this.modelInterceptors = modelInterceptors;
	}

	// 设置指令
	public void setInstruction(String instruction) {
		this.instruction = instruction;
	}

	// 应用节点逻辑的核心方法
	@Override
	public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
		// 如果启用了推理日志且处于调试级别，则记录开始推理的信息
		if (enableReasoningLog && logger.isDebugEnabled()) {
			logger.debug("[ThreadId {}] Agent {} start reasoning.", config.threadId()
					.orElse(THREAD_ID_DEFAULT), agentName);
		}

		// 检查并管理迭代计数器
		final AtomicInteger iterations;
		if (!config.context().containsKey(MODEL_ITERATION_KEY)) {
			// 如果上下文中没有迭代计数器，则创建新的计数器并放入上下文
			iterations = new AtomicInteger(0);
			config.context().put(MODEL_ITERATION_KEY, iterations);
		} else {
			// 如果存在迭代计数器，则获取并递增
			iterations = (AtomicInteger) config.context().get(MODEL_ITERATION_KEY);
			iterations.incrementAndGet();
		}

		// 检查和管理消息
		if (state.value("messages").isEmpty()) {
			// 如果消息为空则抛出异常
			throw new IllegalArgumentException("Either 'instruction' or 'includeContents' must be set for Agent.");
		}
		// 获取消息列表
		@SuppressWarnings("unchecked")
		List<Message> messages = (List<Message>) state.value("messages").get();
		// 增强用户消息，添加输出模式定义
		augmentUserMessage(messages, outputSchema);
		// 渲染模板化的用户消息
		renderTemplatedUserMessage(messages, state.data());

		// 创建ModelRequest构建器
		ModelRequest.Builder requestBuilder = ModelRequest.builder()
				.messages(messages)
				.options(toolCallingChatOptions)
				.context(config.metadata().orElse(new HashMap<>()));
		// 如果系统提示词不为空，则添加到请求中
		if (StringUtils.hasLength(this.systemPrompt)) {
			requestBuilder.systemMessage(new SystemMessage(this.systemPrompt));
		}
		// 构建ModelRequest
		ModelRequest modelRequest = requestBuilder.build();

		// 添加流式支持
		boolean stream = config.metadata("_stream_", new TypeRef<Boolean>(){}).orElse(true);
		if (stream) {
			// 创建基础处理器，实际调用模型并支持流式传输
			ModelCallHandler baseHandler = request -> {
				try {
					// 如果启用了推理日志，则记录系统提示词
					if (enableReasoningLog) {
						String systemPrompt = request.getSystemMessage() != null ? request.getSystemMessage().getText() : "";
						if (logger.isDebugEnabled()) {
							logger.debug("[ThreadId {}] Agent {} reasoning with system prompt: {}", config.threadId()
									.orElse(THREAD_ID_DEFAULT), agentName, systemPrompt);
						}
					}
					// 发起流式请求获取响应
					Flux<ChatResponse> chatResponseFlux = buildChatClientRequestSpec(request).stream().chatResponse();
					// 如果启用了推理日志，则添加响应处理逻辑
					if (enableReasoningLog) {
						chatResponseFlux = chatResponseFlux.doOnNext(chatResponse -> {
							if (chatResponse != null && chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null) {
								// 如果响应包含工具调用，则记录工具调用信息
								if (chatResponse.getResult().getOutput().hasToolCalls()) {
									logger.info("[ThreadId {}] Agent {} reasoning round {} streaming output: {}",
											config.threadId().orElse(THREAD_ID_DEFAULT), agentName, iterations.get(), chatResponse.getResult().getOutput().getToolCalls());
								} else {
									// 否则记录文本输出信息
									logger.info("[ThreadId {}] Agent {} reasoning round {} streaming output: {}",
											config.threadId()
													.orElse(THREAD_ID_DEFAULT), agentName, iterations.get(), chatResponse.getResult()
													.getOutput().getText());
								}
							}
						});
					}
					// 返回流式响应的ModelResponse
					return ModelResponse.of(chatResponseFlux);
				} catch (Exception e) {
					// 异常处理，记录错误并返回异常信息
					logger.error("Exception during streaming model call: ", e);
					return ModelResponse.of(new AssistantMessage("Exception: " + e.getMessage()));
				}
			};

			// 链接拦截器（如果有的话）
			ModelCallHandler chainedHandler = InterceptorChain.chainModelInterceptors(
					modelInterceptors, baseHandler);

			// 执行链接后的处理器
			ModelResponse modelResponse = chainedHandler.call(modelRequest);
			// 返回处理结果
			return Map.of(StringUtils.hasLength(this.outputKey) ? this.outputKey : "messages", modelResponse.getMessage());
		} else {
			// 非流式模式下的基础处理器
			ModelCallHandler baseHandler = request -> {
				try {
					// 如果启用了推理日志，则记录系统提示词
					if (enableReasoningLog) {
						String systemPrompt = request.getSystemMessage() != null ? request.getSystemMessage().getText() : "";
						logger.info("[ThreadId {}] Agent {} reasoning round {} with system prompt: {}.", config.threadId().orElse(THREAD_ID_DEFAULT), agentName, iterations.get(), systemPrompt);
					}

					// 发起非流式请求获取响应
					ChatResponse response = buildChatClientRequestSpec(request).call().chatResponse();

					// 处理响应消息
					AssistantMessage responseMessage = new AssistantMessage("Empty response from model for unknown reason");
					if (response != null && response.getResult() != null) {
						responseMessage = response.getResult().getOutput();
					}

					// 如果启用了推理日志，则记录响应信息
					if (enableReasoningLog) {
						logger.info("[ThreadId {}] Agent {} reasoning round {} returned: {}.", config.threadId().orElse(THREAD_ID_DEFAULT), agentName, iterations.get(), responseMessage);
					}

					// 返回响应的ModelResponse
					return ModelResponse.of(responseMessage, response);
				} catch (Exception e) {
					// 异常处理，记录错误并返回异常信息
					logger.error("Exception during streaming model call: ", e);
					return ModelResponse.of(new AssistantMessage("Exception: " + e.getMessage()));
				}
			};

			// 链接拦截器（如果有的话）
			ModelCallHandler chainedHandler = InterceptorChain.chainModelInterceptors(
					modelInterceptors, baseHandler);

			// 如果启用了推理日志，则记录模型链启动信息
			if (enableReasoningLog) {
				logger.info("[ThreadId {}] Agent {} reasoning round {} model chain has started.", config.threadId().orElse(THREAD_ID_DEFAULT), agentName, iterations.get());
			}

			// 执行链接后的处理器
			ModelResponse modelResponse = chainedHandler.call(modelRequest);
			// 获取token使用情况
			Usage tokenUsage = modelResponse.getChatResponse() != null ? modelResponse.getChatResponse().getMetadata()
					.getUsage() : new EmptyUsage();

			// 构建更新后的状态
			Map<String, Object> updatedState = new HashMap<>();
			updatedState.put("_TOKEN_USAGE_", tokenUsage);
			updatedState.put("messages", modelResponse.getMessage());
			// 如果指定了输出键，则也放入该键对应的数据
			if (StringUtils.hasLength(this.outputKey)) {
				updatedState.put(this.outputKey, modelResponse.getMessage());
			}

			// 返回更新后的状态
			return updatedState;
		}
	}

	// 设置advisor列表
	public void setAdvisors(List<Advisor> advisors) {
		this.advisors = advisors;
	}

	// 如果需要，在消息列表开头添加系统提示词
	private List<Message> appendSystemPromptIfNeeded(ModelRequest modelRequest) {
		// 创建新列表并复制modelRequest中的消息
		List<Message> messages = new ArrayList<>(modelRequest.getMessages());

		// FIXME, there should have only one SystemMessage.
		//  Users may have added SystemMessages in hooks or somewhere else, simply remove will cause unexpected agent behaviour.
//		messages.removeIf(message -> message instanceof SystemMessage);

		// 如果modelRequest中有系统消息，则将其添加到列表开头
		if (modelRequest.getSystemMessage() != null) {
			messages.add(0, modelRequest.getSystemMessage());
		}

		// 统计系统消息数量
		long systemMessageCount = messages.stream()
				.filter(message -> message instanceof SystemMessage)
				.count();

		// 如果系统消息超过2个，则发出警告
		if (systemMessageCount > 2) {
			logger.warn("Detected {} SystemMessages in the message list. There should typically be only one SystemMessage. " +
					"Multiple SystemMessages may cause unexpected behavior or model confusion.", systemMessageCount);
		}

		// 返回处理后的消息列表
		return messages;
	}

	// 渲染提示词模板
	private String renderPromptTemplate(String prompt, Map<String, Object> params) {
		// 创建提示词模板
		PromptTemplate promptTemplate = new PromptTemplate(prompt);
		// 渲染并返回结果
		return promptTemplate.render(params);
	}

	// 在用户消息中增强输出模式定义
	public void augmentUserMessage(List<Message> messages, String outputSchema) {
		// 如果输出模式定义为空，则直接返回
		if (!StringUtils.hasText(outputSchema)) {
			return;
		}

		// 从后往前遍历消息列表
		for (int i = messages.size() - 1; i >= 0; i--) {
			Message message = messages.get(i);
			// 如果是用户消息
			if (message instanceof UserMessage userMessage) {
				// 检查是否已经包含输出模式定义以避免重复
				if (!userMessage.getText().contains(outputSchema)) {
					messages.set(i, userMessage.mutate().text(userMessage.getText() + System.lineSeparator() + outputSchema).build());
				}
				break;
			}
			// 如果是代理指令消息
			if (message instanceof AgentInstructionMessage templatedUserMessage) {
                // 对输出模式定义中的花括号进行转义
                String newOutputSchema = outputSchema.replace("{", "\\{").replace("}", "\\}");
                // 检查是否已经包含输出模式定义以避免重复
                if (!templatedUserMessage.getText().contains(newOutputSchema)) {
                	messages.set(i, templatedUserMessage.mutate().text(templatedUserMessage.getText() + System.lineSeparator() + newOutputSchema).build());
                }
				break;
			}

			// 如果遍历完仍未找到用户消息，则在末尾添加一条新的用户消息
			if (i == 0) {
				messages.add(new UserMessage(outputSchema));
			}
		}
	}

	// 渲染模板化用户消息
	public void renderTemplatedUserMessage(List<Message> messages, Map<String, Object> params) {
		// 从后往前遍历消息列表
		for (int i = messages.size() - 1; i >= 0; i--) {
			Message message = messages.get(i);
			// 如果是代理指令消息
			if (message instanceof AgentInstructionMessage instructionMessage) {
				// 渲染模板并构建新的消息
				AgentInstructionMessage newMessage = instructionMessage.mutate().text(renderPromptTemplate(instructionMessage.getText(), params)).build();
				messages.set(i, newMessage);
				break;
			}
		}
	}

	/**
	 * 根据ModelRequest中指定的工具过滤工具回调。
	 * @param modelRequest 包含要过滤的工具名称列表的模型请求
	 * @return 匹配请求工具的过滤后的工具回调列表
	 */
	// 根据模型请求过滤工具回调
	private List<ToolCallback> filterToolCallbacks(ModelRequest modelRequest) {
		// 如果modelRequest为空或工具列表为空，则返回所有工具回调
		if (modelRequest == null || modelRequest.getTools() == null || modelRequest.getTools().isEmpty()) {
			return toolCallbacks;
		}

		// 获取请求的工具列表
		List<String> requestedTools = modelRequest.getTools();
		// 过滤出匹配的工具回调
		return toolCallbacks.stream()
				.filter(callback -> requestedTools.contains(callback.getToolDefinition().name()))
				.toList();
	}

	// 构建聊天客户端请求规范
	private ChatClient.ChatClientRequestSpec buildChatClientRequestSpec(ModelRequest modelRequest) {
		// 获取处理后的消息列表
		List<Message> messages = appendSystemPromptIfNeeded(modelRequest);

		// 过滤工具回调
		List<ToolCallback> filteredToolCallbacks = filterToolCallbacks(modelRequest);
		// 重新构建工具调用选项
		this.toolCallingChatOptions = ToolCallingChatOptions.builder()
				.toolCallbacks(filteredToolCallbacks)
				.internalToolExecutionEnabled(false)
				.build();

		// 构建聊天客户端请求规范
		ChatClient.ChatClientRequestSpec chatClientRequestSpec = chatClient.prompt()
				.options(toolCallingChatOptions)
				.messages(messages)
				.advisors(advisors);

		// 返回请求规范
		return chatClientRequestSpec;
	}

	// 获取节点名称
	public String getName() {
		return MODEL_NODE_NAME;
	}

	// Builder内部类，用于构建AgentLlmNode实例
	public static class Builder {
		// Agent名称
		private String agentName;

		// 输出键名
		private String outputKey;

		// 输出模式定义
		private String outputSchema;

		// 系统提示词
		private String systemPrompt;

		// 聊天客户端
		private ChatClient chatClient;

		// Advisor列表
		private List<Advisor> advisors;

		// 工具回调列表
		private List<ToolCallback> toolCallbacks;

		// 模型拦截器列表
		private List<ModelInterceptor> modelInterceptors;

		// 指令
		private String instruction;

		// 是否启用推理日志
		private boolean enableReasoningLog;

		// 设置agentName
		public Builder agentName(String agentName) {
			this.agentName = agentName;
			return this;
		}

		// 设置outputKey
		public Builder outputKey(String outputKey) {
			this.outputKey = outputKey;
			return this;
		}

		// 设置outputSchema
		public Builder outputSchema(String outputSchema) {
			this.outputSchema = outputSchema;
			return this;
		}

		// 设置systemPrompt
		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		// 设置advisors
		public Builder advisors(List<Advisor> advisors) {
			this.advisors = advisors;
			return this;
		}

		// 设置toolCallbacks
		public Builder toolCallbacks(List<ToolCallback> toolCallbacks) {
			this.toolCallbacks = toolCallbacks;
			return this;
		}

		// 设置modelInterceptors
		public Builder modelInterceptors(List<ModelInterceptor> modelInterceptors) {
			this.modelInterceptors = modelInterceptors;
			return this;
		}

		// 设置chatClient
		public Builder chatClient(ChatClient chatClient) {
			this.chatClient = chatClient;
			return this;
		}

		// 设置instruction
		public Builder instruction(String instruction) {
			this.instruction = instruction;
			return this;
		}

		// 设置enableReasoningLog
		public Builder enableReasoningLog(boolean enableReasoningLog) {
			this.enableReasoningLog = enableReasoningLog;
			return this;
		}

		// 构建AgentLlmNode实例
		public AgentLlmNode build() {
			return new AgentLlmNode(this);
		}

	}

}

