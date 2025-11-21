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

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.SubGraphNode;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.factory.AgentBuilderFactory;
import com.alibaba.cloud.ai.graph.agent.factory.DefaultAgentBuilderFactory;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.ToolInjection;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.serializer.AgentInstructionMessage;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import com.alibaba.cloud.ai.graph.agent.node.AgentLlmNode;
import com.alibaba.cloud.ai.graph.agent.node.AgentToolNode;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig.node_async;
import static java.lang.String.format;


public class ReactAgent extends BaseAgent {
	Logger logger = LoggerFactory.getLogger(ReactAgent.class);

	// 代理的LLM节点，负责处理大语言模型相关的逻辑
	private final AgentLlmNode llmNode;

	// 代理的工具节点，负责处理工具调用相关的逻辑
	private final AgentToolNode toolNode;

	// 编译后的图结构，用于执行代理的完整工作流
	private CompiledGraph compiledGraph;

	// 钩子列表，包含在代理执行过程中触发的各种钩子
	private List<? extends Hook> hooks;

	// 模型拦截器列表，用于拦截和处理模型相关的操作
	private List<ModelInterceptor> modelInterceptors;

	// 工具拦截器列表，用于拦截和处理工具调用相关的操作
	private List<ToolInterceptor> toolInterceptors;

	// 代理的指令信息，定义代理的行为准则和操作指南
	private String instruction;

	public ReactAgent(AgentLlmNode llmNode, AgentToolNode toolNode, CompileConfig compileConfig, Builder builder) {
		// 调用父类BaseAgent的构造函数，初始化基础属性
		super(builder.name, builder.description, builder.includeContents, builder.returnReasoningContents, builder.outputKey, builder.outputKeyStrategy);
		// 设置代理指令
		this.instruction = builder.instruction;
		// 设置LLM节点
		this.llmNode = llmNode;
		// 设置工具节点
		this.toolNode = toolNode;
		// 设置编译配置
		this.compileConfig = compileConfig;
		// 设置钩子列表
		this.hooks = builder.hooks;
		// 设置模型拦截器列表
		this.modelInterceptors = builder.modelInterceptors;
		// 设置工具拦截器列表
		this.toolInterceptors = builder.toolInterceptors;
		// 设置是否包含内容标志
		this.includeContents = builder.includeContents;
		// 设置输入模式
		this.inputSchema = builder.inputSchema;
		// 设置输入类型
		this.inputType = builder.inputType;
		// 设置输出模式
		this.outputSchema = builder.outputSchema;
		// 设置输出类型
		this.outputType = builder.outputType;

		// 为节点设置拦截器
		// 如果模型拦截器列表不为空且不为空列表，则设置给LLM节点
		if (this.modelInterceptors != null && !this.modelInterceptors.isEmpty()) {
			this.llmNode.setModelInterceptors(this.modelInterceptors);
		}
		// 如果工具拦截器列表不为空且不为空列表，则设置给工具节点
		if (this.toolInterceptors != null && !this.toolInterceptors.isEmpty()) {
			this.toolNode.setToolInterceptors(this.toolInterceptors);
		}
	}

	public static Builder builder() {
		return new DefaultAgentBuilderFactory().builder();
	}

	public static Builder builder(AgentBuilderFactory agentBuilderFactory) {
		return agentBuilderFactory.builder();
	}

	public AssistantMessage call(String message) throws GraphRunnerException {
		return doMessageInvoke(message, null);
	}

	public AssistantMessage call(String message, RunnableConfig config) throws GraphRunnerException {
		return doMessageInvoke(message, config);
	}

	public AssistantMessage call(UserMessage message) throws GraphRunnerException {
		return doMessageInvoke(message, null);
	}

	public AssistantMessage call(UserMessage message, RunnableConfig config) throws GraphRunnerException {
		return doMessageInvoke(message, config);
	}

	public AssistantMessage call(List<Message> messages) throws GraphRunnerException {
		return doMessageInvoke(messages, null);
	}

	public AssistantMessage call(List<Message> messages, RunnableConfig config) throws GraphRunnerException {
		return doMessageInvoke(messages, config);
	}

	private AssistantMessage doMessageInvoke(Object message, RunnableConfig config) throws GraphRunnerException {
		// 构建消息输入
		Map<String, Object> inputs= buildMessageInput(message);
		// 执行调用并获取状态
		Optional<OverAllState> state = doInvoke(inputs, config);

		// 如果输出键不为空
		if (StringUtils.hasLength(outputKey)) {
			// 从状态中获取指定输出键的值并转换为AssistantMessage
			return state.flatMap(s -> s.value(outputKey))
					.map(msg -> (AssistantMessage) msg)
					.orElseThrow(() -> new IllegalStateException("Output key " + outputKey + " not found in agent state") );
		}
		// 如果没有指定输出键，则从messages状态中查找AssistantMessage
		return state.flatMap(s -> s.value("messages"))
				.map(messageList -> (List<Message>) messageList)
				.stream()
				.flatMap(messageList -> messageList.stream())
				.filter(msg -> msg instanceof AssistantMessage)
				.map(msg -> (AssistantMessage) msg)
				.reduce((first, second) -> second)
				.orElseThrow(() -> new IllegalStateException("No AssistantMessage found in 'messages' state") );
	}

	// 获取状态图的方法
	public StateGraph getStateGraph() {
		return graph;
	}

	// 获取编译后的图结构的方法
	public CompiledGraph getCompiledGraph() {
		return compiledGraph;
	}

	// 将代理作为节点的方法
	@Override
	public Node asNode(boolean includeContents, boolean returnReasoningContents, String outputKeyToParent) {
		// 如果编译后的图结构为空，则获取并编译图结构
		if (this.compiledGraph == null) {
			this.compiledGraph = getAndCompileGraph();
		}
		// 返回新的AgentSubGraphNode实例
		return new AgentSubGraphNode(this.name, includeContents, returnReasoningContents, outputKeyToParent, this.compiledGraph, this.instruction);
	}

	// 初始化图结构的方法
	@Override
	protected StateGraph initGraph() throws GraphStateException {

		// 如果钩子列表为空，则初始化为空列表
		if (hooks == null) {
			hooks = new ArrayList<>();
		}

		// 验证钩子的唯一性
		Set<String> hookNames = new HashSet<>();
		for (Hook hook : hooks) {
			// 检查钩子名称是否重复
			if (!hookNames.add(hook.getName())) {
				throw new IllegalArgumentException("Duplicate hook instances found");
			}

			// 为每个钩子节点设置代理名称
			hook.setAgentName(this.name);
		}

		// 创建状态图
		StateGraph graph = new StateGraph(name, buildMessagesKeyStrategyFactory(hooks));

		// 添加模型节点和工具节点
		graph.addNode("model", node_async(this.llmNode));
		graph.addNode("tool", node_async(this.toolNode));

		// 为需要工具的钩子设置和注入工具
		setupToolsForHooks(hooks, toolNode);

		// 按位置分类钩子
		List<Hook> beforeAgentHooks = filterHooksByPosition(hooks, HookPosition.BEFORE_AGENT);
		List<Hook> afterAgentHooks = filterHooksByPosition(hooks, HookPosition.AFTER_AGENT);
		List<Hook> beforeModelHooks = filterHooksByPosition(hooks, HookPosition.BEFORE_MODEL);
		List<Hook> afterModelHooks = filterHooksByPosition(hooks, HookPosition.AFTER_MODEL);

		// 为beforeAgent钩子添加节点
		for (Hook hook : beforeAgentHooks) {
			if (hook instanceof AgentHook agentHook) {
				graph.addNode(hook.getName() + ".before", agentHook::beforeAgent);
			}
		}

		// 为afterAgent钩子添加节点
		for (Hook hook : afterAgentHooks) {
			if (hook instanceof AgentHook agentHook) {
				graph.addNode(hook.getName() + ".after", agentHook::afterAgent);
			}
		}

		// 为beforeModel钩子添加节点
		for (Hook hook : beforeModelHooks) {
			if (hook instanceof ModelHook modelHook) {
				graph.addNode(hook.getName() + ".beforeModel", modelHook::beforeModel);
			}
		}

		// 为afterModel钩子添加节点
		for (Hook hook : afterModelHooks) {
			if (hook instanceof ModelHook modelHook) {
				// 特殊处理HumanInTheLoopHook
				if (hook instanceof HumanInTheLoopHook humanInTheLoopHook) {
					graph.addNode(hook.getName() + ".afterModel", humanInTheLoopHook);
				} else {
					graph.addNode(hook.getName() + ".afterModel", modelHook::afterModel);
				}
			}
		}

		// 确定节点流程
		String entryNode = determineEntryNode(beforeAgentHooks, beforeModelHooks);
		String loopEntryNode = determineLoopEntryNode(beforeModelHooks);
		String loopExitNode = determineLoopExitNode(afterModelHooks);
		String exitNode = determineExitNode(afterAgentHooks);

		// 设置边连接
		graph.addEdge(START, entryNode);
		setupHookEdges(graph, beforeAgentHooks, afterAgentHooks, beforeModelHooks, afterModelHooks,
				entryNode, loopEntryNode, loopExitNode, exitNode, true, this);
		return graph;
	}

	/**
	 * Setup and inject tools for hooks that implement ToolInjection interface.
	 * Only the tool matching the hook's required tool name or type will be injected.
	 *
	 * @param hooks the list of hooks
	 * @param toolNode the agent tool node containing available tools
	 */
	// 为实现ToolInjection接口的钩子设置和注入工具
	private void setupToolsForHooks(List<? extends Hook> hooks, AgentToolNode toolNode) {
		// 检查参数有效性
		if (hooks == null || hooks.isEmpty() || toolNode == null) {
			return;
		}

		// 获取可用工具列表
		List<ToolCallback> availableTools = toolNode.getToolCallbacks();
		if (availableTools == null || availableTools.isEmpty()) {
			return;
		}

		// 遍历钩子列表，为实现ToolInjection接口的钩子注入工具
		for (Hook hook : hooks) {
			if (hook instanceof ToolInjection) {
				ToolInjection toolInjection = (ToolInjection) hook;
				// 查找匹配的工具
				ToolCallback toolToInject = findToolForHook(toolInjection, availableTools);
				if (toolToInject != null) {
					// 注入工具
					toolInjection.injectTool(toolToInject);
				}
			}
		}
	}

	/**
	 * Find the matching tool based on hook's requirements.
	 * Matching priority: 1) by name, 2) by type, 3) first available tool
	 *
	 * @param toolInjection the hook that needs a tool
	 * @param availableTools all available tool callbacks
	 * @return the matching tool, or null if no match found
	 */
	// 根据钩子需求查找匹配的工具
	private ToolCallback findToolForHook(ToolInjection toolInjection, List<ToolCallback> availableTools) {
		// 获取钩子所需的工具名称和类型
		String requiredToolName = toolInjection.getRequiredToolName();
		Class<? extends ToolCallback> requiredToolType = toolInjection.getRequiredToolType();

		// 优先级1: 按工具名称匹配
		if (requiredToolName != null) {
			for (ToolCallback tool : availableTools) {
				String toolName = tool.getToolDefinition().name();
				if (requiredToolName.equals(toolName)) {
					return tool;
				}
			}
		}

		// 优先级2: 按工具类型匹配
		if (requiredToolType != null) {
			for (ToolCallback tool : availableTools) {
				if (requiredToolType.isInstance(tool)) {
					return tool;
				}
			}
		}

		// 优先级3: 如果没有特定需求，返回第一个可用工具
		if (requiredToolName == null && requiredToolType == null && !availableTools.isEmpty()) {
			return availableTools.get(0);
		}

		return null;
	}

	/**
	 * Filter hooks by their position based on @HookPositions annotation.
	 * A hook will be included if its getHookPositions() contains the specified position.
	 *
	 * @param hooks the list of hooks to filter
	 * @param position the position to filter by
	 * @return list of hooks that should execute at the specified position
	 */
	// 根据位置过滤钩子
	private static List<Hook> filterHooksByPosition(List<? extends Hook> hooks, HookPosition position) {
		return hooks.stream()
				.filter(hook -> {
					HookPosition[] positions = hook.getHookPositions();
					return Arrays.asList(positions).contains(position);
				})
				.collect(Collectors.toList());
	}

	// 确定入口节点
	private static String determineEntryNode(
			List<Hook> agentHooks,
			List<Hook> modelHooks) {

		if (!agentHooks.isEmpty()) {
			return agentHooks.get(0).getName() + ".before";
		} else if (!modelHooks.isEmpty()) {
			return modelHooks.get(0).getName() + ".beforeModel";
		} else {
			return "model";
		}
	}

	// 确定循环入口节点
	private static String determineLoopEntryNode(
			List<Hook> modelHooks) {

		if (!modelHooks.isEmpty()) {
			return modelHooks.get(0).getName() + ".beforeModel";
		} else {
			return "model";
		}
	}

	// 确定循环退出节点
	private static String determineLoopExitNode(
			List<Hook> modelHooks) {

		if (!modelHooks.isEmpty()) {
			return modelHooks.get(0).getName() + ".afterModel";
		} else {
			return "model";
		}
	}

	// 确定退出节点
	private static String determineExitNode(
			List<Hook> agentHooks) {

		if (!agentHooks.isEmpty()) {
			return agentHooks.get(agentHooks.size() - 1).getName() + ".after";
		} else {
			return StateGraph.END;
		}
	}

	// 设置钩子边连接
	private static void setupHookEdges(
			StateGraph graph,
			List<Hook> beforeAgentHooks,
			List<Hook> afterAgentHooks,
			List<Hook> beforeModelHooks,
			List<Hook> afterModelHooks,
			String entryNode,
			String loopEntryNode,
			String loopExitNode,
			String exitNode,
			boolean hasTools,
			ReactAgent agentInstance) throws GraphStateException {

		// 链接before_agent钩子
		chainHook(graph, beforeAgentHooks, ".before", loopEntryNode, loopEntryNode, exitNode);

		// 链接before_model钩子
		chainHook(graph, beforeModelHooks, ".beforeModel", "model", loopEntryNode, exitNode);

		// 链接after_model钩子（逆序）
		if (!afterModelHooks.isEmpty()) {
			chainModelHookReverse(graph, afterModelHooks, ".afterModel", "model", loopEntryNode, exitNode);
		}

		// 链接after_agent钩子（逆序）
		if (!afterAgentHooks.isEmpty()) {
			chainAgentHookReverse(graph, afterAgentHooks, ".after", exitNode, loopEntryNode, exitNode);
		}

		// 如果有工具则添加工具路由
		if (hasTools) {
			setupToolRouting(graph, loopExitNode, loopEntryNode, exitNode, agentInstance);
		} else if (!loopExitNode.equals("model")) {
			// 没有工具但有after_model钩子 - 连接到退出节点
			addHookEdge(graph, loopExitNode, exitNode, loopEntryNode, exitNode, afterModelHooks.get(afterModelHooks.size() - 1).canJumpTo());
		} else {
			// 没有工具也没有after_model钩子 - 直接连接到退出节点
			graph.addEdge(loopExitNode, exitNode);
		}
	}

	// 逆序链接模型钩子
	private static void chainModelHookReverse(
			StateGraph graph,
			List<Hook> hooks,
			String nameSuffix,
			String defaultNext,
			String modelDestination,
			String endDestination) throws GraphStateException {

		graph.addEdge(defaultNext, hooks.get(hooks.size() - 1).getName() + nameSuffix);

		for (int i = hooks.size() - 1; i > 0; i--) {
			Hook m1 = hooks.get(i);
			Hook m2 = hooks.get(i - 1);
			addHookEdge(graph,
					m1.getName() + nameSuffix,
					m2.getName() + nameSuffix,
					modelDestination, endDestination,
					m1.canJumpTo());
		}
	}

	// 逆序链接代理钩子
	private static void chainAgentHookReverse(
			StateGraph graph,
			List<Hook> hooks,
			String nameSuffix,
			String defaultNext,
			String modelDestination,
			String endDestination) throws GraphStateException {
		if (!hooks.isEmpty()) {
			Hook last = hooks.get(hooks.size() - 1);
			addHookEdge(graph,
					defaultNext,
					StateGraph.END,
					modelDestination, endDestination,
					last.canJumpTo());
		}

		for (int i = hooks.size() - 1; i > 0; i--) {
			Hook m1 = hooks.get(i);
			Hook m2 = hooks.get(i - 1);
			addHookEdge(graph,
					m1.getName() + nameSuffix,
					m2.getName() + nameSuffix,
					modelDestination, endDestination,
					m1.canJumpTo());
		}
	}

	// 链接钩子
	private static void chainHook(
			StateGraph graph,
			List<Hook> hooks,
			String nameSuffix,
			String defaultNext,
			String modelDestination,
			String endDestination) throws GraphStateException {

		for (int i = 0; i < hooks.size() - 1; i++) {
			Hook m1 = hooks.get(i);
			Hook m2 = hooks.get(i + 1);
			addHookEdge(graph,
					m1.getName() + nameSuffix,
					m2.getName() + nameSuffix,
					modelDestination, endDestination,
					m1.canJumpTo());
		}

		if (!hooks.isEmpty()) {
			Hook last = hooks.get(hooks.size() - 1);
			addHookEdge(graph,
					last.getName() + nameSuffix,
					defaultNext,
					modelDestination, endDestination,
					last.canJumpTo());
		}
	}

	// 添加钩子边连接
	private static void addHookEdge(
			StateGraph graph,
			String name,
			String defaultDestination,
			String modelDestination,
			String endDestination,
			List<JumpTo> canJumpTo) throws GraphStateException {

		// 如果可以跳转到的节点列表不为空
		if (canJumpTo != null && !canJumpTo.isEmpty()) {
			// 创建路由函数
			EdgeAction router = state -> {
				JumpTo jumpTo = (JumpTo)state.value("jump_to").orElse(null);
				return resolveJump(jumpTo, modelDestination, endDestination, defaultDestination);
			};

			// 创建目标映射
			Map<String, String> destinations = new HashMap<>();
			destinations.put(defaultDestination, defaultDestination);

			// 根据可跳转的节点类型添加目标
			if (canJumpTo.contains(JumpTo.end)) {
				destinations.put(endDestination, endDestination);
			}
			if (canJumpTo.contains(JumpTo.tool)) {
				destinations.put("tool", "tool");
			}
			if (canJumpTo.contains(JumpTo.model) && !name.equals(modelDestination)) {
				destinations.put(modelDestination, modelDestination);
			}

			// 添加条件边
			graph.addConditionalEdges(name, edge_async(router), destinations);
		} else {
			// 添加普通边
			graph.addEdge(name, defaultDestination);
		}
	}

	// 设置工具路由
	private static void setupToolRouting(
			StateGraph graph,
			String loopExitNode,
			String loopEntryNode,
			String exitNode,
			ReactAgent agentInstance) throws GraphStateException {

		// 模型到工具的路由
		graph.addConditionalEdges(loopExitNode, edge_async(agentInstance.makeModelToTools(loopEntryNode, exitNode)), Map.of("tool", "tool", exitNode, exitNode, loopEntryNode, loopEntryNode));

		// 工具到模型的路由
		graph.addConditionalEdges("tool", edge_async(agentInstance.makeToolsToModelEdge(loopEntryNode, exitNode)), Map.of(loopEntryNode, loopEntryNode, exitNode, exitNode));
	}

	// 解析跳转目标
	private static String resolveJump(JumpTo jumpTo, String modelDestination, String endDestination, String defaultDestination) {
		if (jumpTo == null) {
			return defaultDestination;
		}

		return switch (jumpTo) {
			case model -> modelDestination;
			case end -> endDestination;
			case tool -> "tool";
		};
	}

	// 构建消息键策略工厂
	private KeyStrategyFactory buildMessagesKeyStrategyFactory(List<? extends Hook> hooks) {
		return () -> {
			HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
			// 如果输出键不为空，则设置输出键策略
			if (outputKey != null && !outputKey.isEmpty()) {
				keyStrategyHashMap.put(outputKey, outputKeyStrategy == null ? new ReplaceStrategy() : outputKeyStrategy);
			}
			// 设置消息键策略为追加策略
			keyStrategyHashMap.put("messages", new AppendStrategy());

			// 遍历钩子并收集它们的键策略
			if (hooks != null) {
				for (Hook hook : hooks) {
					Map<String, KeyStrategy> hookStrategies = hook.getKeyStrategys();
					if (hookStrategies != null && !hookStrategies.isEmpty()) {
						keyStrategyHashMap.putAll(hookStrategies);
					}
				}
			}

			return keyStrategyHashMap;
		};
	}

	// 创建模型到工具的边动作
	private EdgeAction makeModelToTools(String modelDestination, String endDestination) {
		return state -> {
			// 获取消息列表
			List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());
			if (messages.isEmpty()) {
				logger.warn("No messages found in state when routing from model to tools");
				return endDestination;
			}
			// 获取最后一条消息
			Message lastMessage = messages.get(messages.size() - 1);

			// 1. 检查最后一条消息的类型
			if (lastMessage instanceof AssistantMessage assistantMessage) {
				// 2. 如果最后一条消息是AssistantMessage
				if (assistantMessage.hasToolCalls()) {
					return "tool";
				} else {
					return endDestination;
				}
			} else if (lastMessage instanceof ToolResponseMessage) {
				// 3. 如果最后一条消息是ToolResponseMessage
				if (messages.size() < 2) {
					// 在有效的ReAct循环中不应该发生，但作为保障措施
					throw new RuntimeException("Less than 2 messages in state when last message is ToolResponseMessage");
				}

				// 获取倒数第二条消息
				Message secondLastMessage = messages.get(messages.size() - 2);
				if (secondLastMessage instanceof AssistantMessage) {
					AssistantMessage assistantMessage = (AssistantMessage) secondLastMessage;
					ToolResponseMessage toolResponseMessage = (ToolResponseMessage) lastMessage;

					// 如果助手消息有工具调用
					if (assistantMessage.hasToolCalls()) {
						// 获取请求的工具名称集合
						Set<String> requestedToolNames = assistantMessage.getToolCalls().stream()
								.map(toolCall -> toolCall.name())
								.collect(java.util.stream.Collectors.toSet());

						// 获取已执行的工具名称集合
						Set<String> executedToolNames = toolResponseMessage.getResponses().stream()
								.map(response -> response.name())
								.collect(java.util.stream.Collectors.toSet());

						// 检查是否所有请求的工具都已执行或响应
						if (executedToolNames.containsAll(requestedToolNames)) {
							return modelDestination; // 所有请求的工具都已执行或响应
						} else {
							return "tool"; // 一些工具仍在等待
						}
					}
				}
			}

			return endDestination;
		};
	}

	// 创建工具到模型的边动作
	private EdgeAction makeToolsToModelEdge(String modelDestination, String endDestination) {
		return state -> {
			// 1. 提取最后的AI消息和相应的工具消息
			ToolResponseMessage toolResponseMessage = fetchLastToolResponseMessage(state);
			// 2. 退出条件: 所有执行的工具都有return_direct=True
			if (toolResponseMessage != null && !toolResponseMessage.getResponses().isEmpty()) {
				boolean allReturnDirect = toolResponseMessage.getResponses().stream().allMatch(toolResponse -> {
					String toolName = toolResponse.name();
					return false; // FIXME
				});
				if (allReturnDirect) {
					return endDestination;
				}
			}

			// 3. 默认: 继续循环
			//    工具执行成功完成，路由回到模型
			//    以便它可以处理工具结果并决定下一步操作
			return modelDestination;
		};
	}

	// 获取最后的工具响应消息
	private ToolResponseMessage fetchLastToolResponseMessage(OverAllState state) {
		List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());

		ToolResponseMessage toolResponseMessage = null;

		for (int i = messages.size() - 1; i >= 0; i--) {
			if (messages.get(i) instanceof ToolResponseMessage) {
				toolResponseMessage = (ToolResponseMessage) messages.get(i);
				break;
			}
		}

		return toolResponseMessage;
	}

	// 获取指令的方法
	public String instruction() {
		return instruction;
	}

	// 设置指令的方法
	public void setInstruction(String instruction) {
		this.instruction = instruction;
		llmNode.setInstruction(instruction);
	}

	// 获取输出键策略的方法
	public KeyStrategy getOutputKeyStrategy() {
		return outputKeyStrategy;
	}

	// 设置输出键策略的方法
	public void setOutputKeyStrategy(KeyStrategy outputKeyStrategy) {
		this.outputKeyStrategy = outputKeyStrategy;
	}

	// 子图节点适配器类
	public static class SubGraphNodeAdapter implements NodeActionWithConfig {

		// 是否包含内容
		private boolean includeContents;

		// 是否返回推理内容
		private boolean returnReasoningContents;

		// 指令
		private String instruction;

		// 输出键到父节点
		private String outputKeyToParent;

		// 子图
		private CompiledGraph childGraph;

		// 父编译配置
		private CompileConfig parentCompileConfig;

		// 构造函数
		public SubGraphNodeAdapter(boolean includeContents, boolean returnReasoningContents, String outputKeyToParent,
				CompiledGraph childGraph, String instruction, CompileConfig parentCompileConfig) {
			this.includeContents = includeContents;
			this.returnReasoningContents = returnReasoningContents;
			this.instruction = instruction;
			this.outputKeyToParent = outputKeyToParent;
			this.childGraph = childGraph;
			this.parentCompileConfig = parentCompileConfig;
		}

		// 获取子图ID的方法
		public String subGraphId() {
			return format("subgraph_%s", childGraph.stateGraph.getName());
		}

		// 应用方法
		@Override
		public Map<String, Object> apply(OverAllState parentState, RunnableConfig config) throws Exception {
			// 获取子图运行配置
			RunnableConfig subGraphRunnableConfig = getSubGraphRunnableConfig(config);
			Flux<GraphResponse<NodeOutput>> subGraphResult;
			Object parentMessages = null;

			// 根据是否包含内容进行不同处理
			if (includeContents) {
				// 默认情况下，includeContents为true，我们将父状态的消息传递下去
				if (StringUtils.hasLength(instruction)) {
					// 指令将作为特殊的UserMessage添加到子图中
					parentState.updateState(Map.of("messages", new AgentInstructionMessage(instruction)));
				}
				subGraphResult = childGraph.graphResponseStream(parentState, subGraphRunnableConfig);
			} else {
				// 创建子图状态
				Map<String, Object> stateForChild = new HashMap<>(parentState.data());
				parentMessages = stateForChild.remove("messages");
				if (StringUtils.hasLength(instruction)) {
					// 指令将作为特殊的UserMessage添加到子图中
					stateForChild.put("messages", new AgentInstructionMessage(instruction));
				}
				subGraphResult = childGraph.graphResponseStream(stateForChild, subGraphRunnableConfig);
			}

			// 构建结果映射
			Map<String, Object> result = new HashMap<>();

			result.put(StringUtils.hasLength(this.outputKeyToParent) ? this.outputKeyToParent : "messages", getGraphResponseFlux(parentState, subGraphResult));
			if (parentMessages != null) {
				result.put("messages", parentMessages);
			}
			return result;
		}

		// 获取图响应流的方法
		private @NotNull Flux<GraphResponse<NodeOutput>> getGraphResponseFlux(OverAllState parentState, Flux<GraphResponse<NodeOutput>> subGraphResult) {
			return Flux.create(sink -> {
				AtomicReference<GraphResponse<NodeOutput>> lastRef = new AtomicReference<>();
				subGraphResult.subscribe(item -> {
					GraphResponse<NodeOutput> previous = lastRef.getAndSet(item);
					if (previous != null) {
						sink.next(previous);
					}
				}, sink::error, () -> {
					GraphResponse<NodeOutput> lastResponse = lastRef.get();
					if (lastResponse != null) {
						if (lastResponse.resultValue().isPresent()) {
							Object resultValue = lastResponse.resultValue().get();
							if (resultValue instanceof Map) {
								@SuppressWarnings("unchecked")
								Map<String, Object> resultMap = (Map<String, Object>) resultValue;
								if (resultMap.get("messages") instanceof List) {
									@SuppressWarnings("unchecked")
									List<Object> messages = new ArrayList<>((List<Object>) resultMap.get("messages"));
									if (!messages.isEmpty()) {
										parentState.value("messages").ifPresent(parentMsgs -> {
											if (parentMsgs instanceof List) {
												messages.removeAll((List<?>) parentMsgs);
											}
										});

										List<Object> finalMessages;
										// 根据是否返回推理内容决定最终消息列表
										if (returnReasoningContents) {
											finalMessages = messages;
										}
										else {
											if (!messages.isEmpty()) {
												finalMessages = List.of(messages.get(messages.size() - 1));
											} else {
												finalMessages = List.of();
											}
										}

										// 创建新的结果映射
										Map<String, Object> newResultMap = new HashMap<>(resultMap);
										newResultMap.put("messages", finalMessages);
										lastResponse = GraphResponse.done(newResultMap);
									}
								}
							}
						}
					}
					sink.next(lastResponse);
					sink.complete();
				});
			});
		}

		// 获取子图运行配置的方法
		private RunnableConfig getSubGraphRunnableConfig(RunnableConfig config) {
			// 创建子图运行配置
			RunnableConfig subGraphRunnableConfig = RunnableConfig.builder(config)
					.checkPointId(null)
					.clearContext()
					.nextNode(null)
					.addMetadata("_AGENT_", subGraphId()) // subGraphId与创建它的代理名称相同
					.build();
			var parentSaver = parentCompileConfig.checkpointSaver();
			var subGraphSaver = childGraph.compileConfig.checkpointSaver();

			// 如果子图保存器存在
			if (subGraphSaver.isPresent()) {
				if (parentSaver.isEmpty()) {
					throw new IllegalStateException("Missing CheckpointSaver in parent graph!");
				}

				// 检查保存器是否为同一实例
				if (parentSaver.get() == subGraphSaver.get()) {
					subGraphRunnableConfig = RunnableConfig.builder(config)
							.threadId(config.threadId()
									.map(threadId -> format("%s_%s", threadId, subGraphId()))
									.orElseGet(this::subGraphId))
							.nextNode(null)
							.checkPointId(null)
							.clearContext()
							.addMetadata("_AGENT_", subGraphId()) // subGraphId与创建它的代理名称相同
							.build();
				}
			}
			return subGraphRunnableConfig;
		}

	}

	/**
	 * Internal class that adapts a ReactAgent to be used as a SubGraph Node.
	 */
	// 代理子图节点类，适配ReactAgent以用作子图节点
	private static class AgentSubGraphNode extends Node implements SubGraphNode {

		// 子图
		private final CompiledGraph subGraph;

		// 构造函数
		public AgentSubGraphNode(String id, boolean includeContents, boolean returnReasoningContents, String outputKeyToParent, CompiledGraph subGraph, String instruction) {
			super(Objects.requireNonNull(id, "id cannot be null"),
					(config) -> node_async(new SubGraphNodeAdapter(includeContents, returnReasoningContents, outputKeyToParent, subGraph, instruction, config)));
			this.subGraph = subGraph;
		}

		// 获取子图的方法
		@Override
		public StateGraph subGraph() {
			return subGraph.stateGraph;
		}
	}
}
