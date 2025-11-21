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
package com.alibaba.cloud.ai.graph.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.serializer.AgentInstructionMessage;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

import org.springframework.util.StringUtils;

import static com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants.AGENT_STATE_CONTEXT_KEY;

public class AgentTool implements BiFunction<String, ToolContext, AssistantMessage> {

	private final ReactAgent agent;

	public AgentTool(ReactAgent agent) {
		this.agent = agent;
	}

	@Override
	public AssistantMessage apply(String input, ToolContext toolContext) {
		// 从工具上下文中获取当前代理状态
		OverAllState state = (OverAllState) toolContext.getContext().get(AGENT_STATE_CONTEXT_KEY);
		try {
			// 复制状态以避免影响原始状态
			// 调用此工具的代理应该只感知到ToolCallChoice和ToolResponse
			OverAllState newState = agent.getAndCompileGraph().cloneState(state.data());

			// 构建要添加的消息列表
			// 如果存在指令则先添加指令，然后添加用户输入
			// 注意：我们必须一次性添加所有消息，因为cloneState不会复制keyStrategies，
			// 所以多次updateState调用会覆盖而不是追加
			java.util.List<Message> messagesToAdd = new java.util.ArrayList<>();
			// 检查代理指令是否非空，如果非空则添加到消息列表
			if (StringUtils.hasLength(agent.instruction())) {
				messagesToAdd.add(new AgentInstructionMessage(agent.instruction()));
			}
			// 添加用户输入消息
			messagesToAdd.add(new UserMessage(input));

			// 更新状态，将消息列表作为输入
			Map<String, Object> inputs = newState.updateState(Map.of("messages", messagesToAdd));

			// 调用代理图执行输入，获取结果状态
			Optional<OverAllState> resultState = agent.getAndCompileGraph().invoke(inputs);

			// 从结果状态中提取消息列表
			Optional<List> messages = resultState.flatMap(overAllState -> overAllState.value("messages", List.class));
			// 检查消息列表是否存在
			if (messages.isPresent()) {
				// 类型转换警告抑制
				@SuppressWarnings("unchecked")
				// 将消息列表转换为Message类型列表
				List<Message> messageList = (List<Message>) messages.get();
				// 使用消息列表，获取最后一条助手消息
				AssistantMessage assistantMessage = (AssistantMessage)messageList.get(messageList.size() - 1);
				// 返回助手消息
				return assistantMessage;
			}
		}
		// 捕获异常并转换为运行时异常抛出
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		// 如果执行失败或获取结果失败，抛出运行时异常
		throw new RuntimeException("Failed to execute agent tool or failed to get agent tool result");
	}

	// 定义静态的工具调用结果转换器
	private static final ToolCallResultConverter CONVERTER = new MessageToolCallResultConverter();

	// 创建AgentTool实例的静态方法
	public static AgentTool create(ReactAgent agent) {
		return new AgentTool(agent);
	}

	// 获取函数工具回调的静态方法
	public static ToolCallback getFunctionToolCallback(ReactAgent agent) {
		// 将代理输入类型转换为JSON模式
		String inputSchema = StringUtils.hasLength(agent.getInputSchema())
				// 如果代理输入模式非空，则使用该模式
				? agent.getInputSchema()
				// 否则检查代理输入类型是否非空
				: (agent.getInputType() != null )
					// 如果输入类型非空，则生成该类型的JSON模式
					? JsonSchemaGenerator.generateForType(agent.getInputType())
					// 否则设为null
					: null;

		// 构建函数工具回调
		return FunctionToolCallback.builder(agent.name(), AgentTool.create(agent))
			// 设置代理描述
			.description(agent.description())
			// 设置输入类型为String（ToolCallback的输入类型始终是String）
			.inputType(String.class) // the inputType for ToolCallback is always String
			// 设置输入模式
			.inputSchema(inputSchema)
			// 设置工具调用结果转换器
			.toolCallResultConverter(CONVERTER)
			// 构建并返回
			.build();
	}

}
