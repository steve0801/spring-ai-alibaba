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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.scheduling.ScheduleConfig;
import com.alibaba.cloud.ai.graph.scheduling.ScheduledAgentTask;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import reactor.core.publisher.Flux;

import org.springframework.scheduling.Trigger;

import static com.alibaba.cloud.ai.graph.utils.Messageutils.convertToMessages;

/**
 * Abstract base class for all agents in the graph system. Contains common properties and
 * methods shared by different agent implementations.
 */
public abstract class Agent {

	/** The agent's name. Must be a unique identifier within the graph. */
	protected String name;

	/**
	 * One line description about the agent's capability. The system can use this for
	 * decision-making when delegating control to different agents.
	 */
	protected String description;

	protected CompileConfig compileConfig;

	protected volatile CompiledGraph compiledGraph;

	protected volatile StateGraph graph;

	/**
	 * Protected constructor for initializing all base agent properties.
	 * @param name the unique name of the agent
	 * @param description the description of the agent's capability
	 */
	protected Agent(String name, String description) {
		this.name = name;
		this.description = description;
	}

	/**
	 * Default protected constructor for subclasses that need to initialize properties
	 * differently.
	 */
	protected Agent() {
		// Allow subclasses to initialize properties through other means
	}

	/**
	 * Gets the agent's unique name.
	 * @return the unique name of the agent.
	 */
	public String name() {
		return name;
	}

	/**
	 * Gets the one-line description of the agent's capability.
	 * @return the description of the agent.
	 */
	public String description() {
		return description;
	}

	public StateGraph getGraph() {
		// 检查graph是否已初始化，如果未初始化则调用initGraph方法进行初始化
		if (this.graph == null) {
			try {
				// 调用抽象方法initGraph来初始化图结构
				this.graph = initGraph();
			}
			// 捕获图状态异常并转换为运行时异常抛出
			catch (GraphStateException e) {
				throw new RuntimeException(e);
			}
		}
		// 返回已初始化的图结构
		return this.graph;
	}

	// 同步方法，获取并编译图结构，确保线程安全
	public synchronized CompiledGraph getAndCompileGraph() {
		// 如果已编译的图结构存在，直接返回
		if (compiledGraph != null) {
			return compiledGraph;
		}

		// 获取图结构
		StateGraph graph = getGraph();
		try {
			// 检查编译配置是否存在
			if (this.compileConfig == null) {
				// 如果编译配置为空，则使用默认配置编译图结构
				this.compiledGraph = graph.compile();
			}
			// 如果编译配置存在，则使用指定配置编译图结构
			else {
				this.compiledGraph = graph.compile(this.compileConfig);
			}
		} catch (GraphStateException e) {
			// 捕获图状态异常并转换为运行时异常抛出
			throw new RuntimeException(e);
		}
		// 返回编译后的图结构
		return this.compiledGraph;
	}

	/**
	 * Schedule the agent task with trigger.
	 * @param trigger the schedule configuration
	 * @param input the agent input
	 * @return a ScheduledAgentTask instance for managing the scheduled task
	 */
	// 使用触发器调度代理任务的方法
	public ScheduledAgentTask schedule(Trigger trigger, Map<String, Object> input)
			throws GraphStateException, GraphRunnerException {
		// 构建调度配置对象
		ScheduleConfig scheduleConfig = ScheduleConfig.builder().trigger(trigger).inputs(input).build();
		// 调用重载的schedule方法
		return schedule(scheduleConfig);
	}

	/**
	 * Schedule the agent task with trigger.
	 * @param scheduleConfig the schedule configuration
	 * @return a ScheduledAgentTask instance for managing the scheduled task
	 */
	// 使用调度配置调度代理任务的方法
	public ScheduledAgentTask schedule(ScheduleConfig scheduleConfig) throws GraphStateException {
		// 获取并编译图结构
		CompiledGraph compiledGraph = getAndCompileGraph();
		// 调用编译图的schedule方法进行任务调度
		return compiledGraph.schedule(scheduleConfig);
	}

	// 获取当前状态的方法
	public StateSnapshot getCurrentState(RunnableConfig config) throws GraphRunnerException {
		// 调用编译图的getState方法获取当前状态
		return compiledGraph.getState(config);
	}

	// ------------------- Invoke with OverAllState as return value -------------------

	// 使用字符串消息调用代理的方法，返回整体状态
	public Optional<OverAllState> invoke(String message) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(message);
		// 调用doInvoke方法执行调用
		return doInvoke(inputs, null);
	}

	// 使用字符串消息和配置调用代理的方法，返回整体状态
	public Optional<OverAllState> invoke(String message, RunnableConfig config) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(message);
		// 调用doInvoke方法执行调用
		return doInvoke(inputs, config);
	}

	// 使用用户消息调用代理的方法，返回整体状态
	public Optional<OverAllState> invoke(UserMessage message) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(message);
		// 调用doInvoke方法执行调用
		return doInvoke(inputs, null);
	}

	// 使用用户消息和配置调用代理的方法，返回整体状态
	public Optional<OverAllState> invoke(UserMessage message, RunnableConfig config) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(message);
		// 调用doInvoke方法执行调用
		return doInvoke(inputs, config);
	}

	// 使用消息列表调用代理的方法，返回整体状态
	public Optional<OverAllState> invoke(List<Message> messages) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(messages);
		// 调用doInvoke方法执行调用
		return doInvoke(inputs, null);
	}

	// 使用消息列表和配置调用代理的方法，返回整体状态
	public Optional<OverAllState> invoke(List<Message> messages, RunnableConfig config) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(messages);
		// 调用doInvoke方法执行调用
		return doInvoke(inputs, config);
	}

	// ------------------- Invoke  methods with Output as return value -------------------

	// 使用字符串消息调用代理的方法，返回节点输出
	public Optional<NodeOutput> invokeAndGetOutput(String message) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(message);
		// 调用doInvokeAndGetOutput方法执行调用
		return doInvokeAndGetOutput(inputs, null);
	}

	// 使用字符串消息和配置调用代理的方法，返回节点输出
	public Optional<NodeOutput> invokeAndGetOutput(String message, RunnableConfig config) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(message);
		// 调用doInvokeAndGetOutput方法执行调用
		return doInvokeAndGetOutput(inputs, config);
	}

	// 使用用户消息调用代理的方法，返回节点输出
	public Optional<NodeOutput> invokeAndGetOutput(UserMessage message) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(message);
		// 调用doInvokeAndGetOutput方法执行调用
		return doInvokeAndGetOutput(inputs, null);
	}

	// 使用用户消息和配置调用代理的方法，返回节点输出
	public Optional<NodeOutput> invokeAndGetOutput(UserMessage message, RunnableConfig config) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(message);
		// 调用doInvokeAndGetOutput方法执行调用
		return doInvokeAndGetOutput(inputs, config);
	}

	// 使用消息列表调用代理的方法，返回节点输出
	public Optional<NodeOutput> invokeAndGetOutput(List<Message> messages) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(messages);
		// 调用doInvokeAndGetOutput方法执行调用
		return doInvokeAndGetOutput(inputs, null);
	}

	// 使用消息列表和配置调用代理的方法，返回节点输出
	public Optional<NodeOutput> invokeAndGetOutput(List<Message> messages, RunnableConfig config) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(messages);
		// 调用doInvokeAndGetOutput方法执行调用
		return doInvokeAndGetOutput(inputs, config);
	}

	// ------------------- Stream methods -------------------

	// 使用字符串消息流式调用代理的方法，返回节点输出流
	public Flux<NodeOutput> stream(String message) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(message);
		// 调用doStream方法执行流式调用
		return doStream(inputs, buildStreamConfig(null));
	}

	// 使用字符串消息和配置流式调用代理的方法，返回节点输出流
	public Flux<NodeOutput> stream(String message, RunnableConfig config) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(message);
		// 调用doStream方法执行流式调用
		return doStream(inputs, config);
	}

	// 使用用户消息流式调用代理的方法，返回节点输出流
	public Flux<NodeOutput> stream(UserMessage message) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(message);
		// 调用doStream方法执行流式调用
		return doStream(inputs, buildStreamConfig(null));
	}

	// 使用用户消息和配置流式调用代理的方法，返回节点输出流
	public Flux<NodeOutput> stream(UserMessage message, RunnableConfig config) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(message);
		// 调用doStream方法执行流式调用
		return doStream(inputs, config);
	}

	// 使用消息列表流式调用代理的方法，返回节点输出流
	public Flux<NodeOutput> stream(List<Message> messages) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(messages);
		// 调用doStream方法执行流式调用
		return doStream(inputs, buildStreamConfig(null));
	}

	// 使用消息列表和配置流式调用代理的方法，返回节点输出流
	public Flux<NodeOutput> stream(List<Message> messages, RunnableConfig config) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs = buildMessageInput(messages);
		// 调用doStream方法执行流式调用
		return doStream(inputs, config);
	}

	// 执行调用的核心方法，返回整体状态
	protected Optional<OverAllState> doInvoke(Map<String, Object> input, RunnableConfig runnableConfig) {
		// 获取并编译图结构
		CompiledGraph compiledGraph = getAndCompileGraph();
		// 调用编译图的invoke方法执行调用
		return compiledGraph.invoke(input, buildNonStreamConfig(runnableConfig));
	}

	// 执行调用的核心方法，返回节点输出
	protected Optional<NodeOutput> doInvokeAndGetOutput(Map<String, Object> input, RunnableConfig runnableConfig) {
		// 获取并编译图结构
		CompiledGraph compiledGraph = getAndCompileGraph();
		// 调用编译图的invokeAndGetOutput方法执行调用
		return compiledGraph.invokeAndGetOutput(input, buildNonStreamConfig(runnableConfig));
	}

	// 执行流式调用的核心方法，返回节点输出流
	protected Flux<NodeOutput> doStream(Map<String, Object> input, RunnableConfig runnableConfig) {
		// 获取并编译图结构
		CompiledGraph compiledGraph = getAndCompileGraph();
		// 调用编译图的stream方法执行流式调用
		return compiledGraph.stream(input, buildStreamConfig(runnableConfig));
	}

	// 构建非流式调用配置的方法
	protected RunnableConfig buildNonStreamConfig(RunnableConfig config) {
		// 如果配置为空，则创建新的配置并添加元数据
		if (config == null) {
			return RunnableConfig.builder().addMetadata("_stream_", false).addMetadata("_AGENT_", name).build();
		}
		// 如果配置不为空，则基于现有配置构建新配置并添加元数据
		return RunnableConfig.builder(config).addMetadata("_stream_", false).addMetadata("_AGENT_", name).build();
	}

	// 构建流式调用配置的方法
	protected RunnableConfig buildStreamConfig(RunnableConfig config) {
		// 如果配置为空，则创建新的配置并添加元数据
		if (config == null) {
			return RunnableConfig.builder().addMetadata("_AGENT_", name).build();
		}
		// 如果配置不为空，则基于现有配置构建新配置并添加元数据
		return RunnableConfig.builder(config).addMetadata("_AGENT_", name).build();
	}

	// 构建消息输入的方法
	protected Map<String, Object> buildMessageInput(Object message) {
		// 声明消息列表变量
		List<Message> messages;
		// 检查消息是否为列表类型
		if (message instanceof List) {
			// 如果是列表类型，直接转换为消息列表
			messages = (List<Message>) message;
		} else {
			// 如果不是列表类型，使用工具方法转换为消息列表
			messages = convertToMessages(message);
		}

		// 创建输入映射
		Map<String, Object> inputs = new HashMap<>();
		// 将消息列表添加到输入映射中
		inputs.put("messages", messages);

		// 声明最后一个用户消息变量
		UserMessage lastUserMessage = null;
		// 从消息列表末尾开始遍历，查找最后一个用户消息
		for (int i = messages.size() - 1; i >= 0; i--) {
			// 获取当前消息
			Message msg = messages.get(i);
			// 检查是否为用户消息
			if (msg instanceof UserMessage) {
				// 如果是用户消息，则保存并跳出循环
				lastUserMessage = (UserMessage) msg;
				break;
			}
		}
		// 如果找到最后一个用户消息，则将其文本内容添加到输入映射中
		if (lastUserMessage != null) {
			inputs.put("input", lastUserMessage.getText());
		}
		// 返回构建的输入映射
		return inputs;
	}

	// 抽象方法，由子类实现图结构的初始化
	protected abstract StateGraph initGraph() throws GraphStateException;
}
