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
package com.alibaba.cloud.ai.graph.agent.a2a;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.SubGraphNode;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.BaseAgent;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import com.alibaba.cloud.ai.graph.scheduling.ScheduleConfig;
import com.alibaba.cloud.ai.graph.scheduling.ScheduledAgentTask;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import io.a2a.spec.AgentCard;

import java.util.HashMap;
import java.util.Objects;
import java.util.logging.Logger;


public class A2aRemoteAgent extends BaseAgent {
	Logger logger = Logger.getLogger(A2aRemoteAgent.class.getName());

	private final AgentCardWrapper agentCard;

	private KeyStrategyFactory keyStrategyFactory;

	private String instruction;

	private boolean streaming;

	private boolean shareState;

	// Private constructor for Builder pattern
	// 私有构造函数，用于Builder模式
	private A2aRemoteAgent(Builder builder) {
		// 调用父类构造函数，初始化基础属性
		super(builder.name, builder.description, builder.includeContents, builder.returnReasoningContents, builder.outputKey, builder.outputKeyStrategy);
		// 设置agentCard属性
		this.agentCard = builder.agentCard;
		// 设置keyStrategyFactory属性
		this.keyStrategyFactory = builder.keyStrategyFactory;
		// 设置compileConfig属性
		this.compileConfig = builder.compileConfig;
		// 设置includeContents属性
		this.includeContents = builder.includeContents;
		// 设置streaming属性
		this.streaming = builder.streaming;
		// 设置instruction属性
		this.instruction = builder.instruction;
		// 设置shareState属性
		this.shareState = builder.shareState;
	}

	@Override
	// 初始化图结构的方法
	protected StateGraph initGraph() throws GraphStateException {
		// 如果keyStrategyFactory为空，则创建默认的keyStrategyFactory
		if (keyStrategyFactory == null) {
			this.keyStrategyFactory = () -> {
				HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
				// 默认使用AppendStrategy策略处理messages键
				keyStrategyHashMap.put("messages", new AppendStrategy());
				return keyStrategyHashMap;
			};
		}

		// 创建新的StateGraph实例
		StateGraph graph = new StateGraph(name, this.keyStrategyFactory);
		// 添加A2aNode节点，使用A2aNodeActionWithConfig作为节点动作
		graph.addNode("A2aNode", AsyncNodeActionWithConfig.node_async(new A2aNodeActionWithConfig(agentCard, name, includeContents, outputKey, instruction, streaming)));
		// 添加从START到A2aNode的边
		graph.addEdge(StateGraph.START, "A2aNode");
		// 添加从A2aNode到END的边
		graph.addEdge("A2aNode", StateGraph.END);
		// 返回构建好的图
		return graph;
	}

	@Override
	// 调度方法，A2aRemoteAgent暂不支持调度功能
	public ScheduledAgentTask schedule(ScheduleConfig scheduleConfig) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException("A2aRemoteAgent has not support schedule.");
	}

	// 创建Builder实例的静态方法
	public static Builder builder() {
		// 返回新的Builder实例
		return new Builder();
	}

	@Override
	// 将当前Agent转换为Node节点的方法
	public Node asNode(boolean includeContents, boolean returnReasoningContents, String outputKeyToParent) {
		// 返回新的A2aRemoteAgentNode实例
		return new A2aRemoteAgentNode(this.name, includeContents, returnReasoningContents, outputKeyToParent, this.instruction, this.agentCard, this.streaming, this.shareState, this.getAndCompileGraph());
	}

	/**
	 * Internal class that adapts an A2aRemoteAgent to be used as a Node.
	 * Similar to AgentSubGraphNode but uses A2aNodeActionWithConfig internally.
	 * Implements SubGraphNode interface to provide subgraph functionality.
	 */
	// 内部类，将A2aRemoteAgent适配为Node使用
	private static class A2aRemoteAgentNode extends Node implements SubGraphNode {

		// 子图实例
		private final CompiledGraph subGraph;

		// 构造函数
		public A2aRemoteAgentNode(String id, boolean includeContents, boolean returnReasoningContents, String outputKeyToParent, String instruction, AgentCardWrapper agentCard, boolean streaming, boolean shareState, CompiledGraph subGraph) {
			// 调用父类构造函数，创建异步节点动作
			super(Objects.requireNonNull(id, "id cannot be null"),
					(config) -> AsyncNodeActionWithConfig.node_async(new A2aNodeActionWithConfig(agentCard, subGraph.stateGraph.getName(), includeContents, outputKeyToParent, instruction, streaming, shareState, config)));
			// 设置子图属性
			this.subGraph = subGraph;
		}

		@Override
		// 获取子图的方法
		public StateGraph subGraph() {
			// 返回子图实例
			return subGraph.stateGraph;
		}
	}

	// Builder静态内部类
	public static class Builder {

		// BaseAgent属性
		private String name;

		private String description;

		private String instruction;

		private String outputKey = "output";

		private KeyStrategy outputKeyStrategy;

		private boolean returnReasoningContents = false;

		// A2aRemoteAgent特定属性
		private AgentCardWrapper agentCard;

		private AgentCardProvider agentCardProvider;

		private boolean includeContents = true;

		private KeyStrategyFactory keyStrategyFactory;

		private CompileConfig compileConfig;

		private boolean streaming = false;

		private boolean shareState = true;

		// 设置name属性的方法
		public Builder name(String name) {
			this.name = name;
			return this;
		}

		// 设置description属性的方法
		public Builder description(String description) {
			this.description = description;
			return this;
		}

		// 设置instruction属性的方法
		public Builder instruction(String instruction) {
			this.instruction = instruction;
			return this;
		}

		// 设置outputKey属性的方法
		public Builder outputKey(String outputKey) {
			this.outputKey = outputKey;
			return this;
		}

		// 设置outputKeyStrategy属性的方法
		public Builder outputKeyStrategy(KeyStrategy outputKeyStrategy) {
			this.outputKeyStrategy = outputKeyStrategy;
			return this;
		}

		// 设置returnReasoningContents属性的方法
		public Builder returnReasoningContents(boolean returnReasoningContents) {
			this.returnReasoningContents = returnReasoningContents;
			return this;
		}

		// 设置agentCard属性的方法
		public Builder agentCard(AgentCard agentCard) {
			this.agentCard = new AgentCardWrapper(agentCard);
			return this;
		}

		// 设置agentCardProvider属性的方法
		public Builder agentCardProvider(AgentCardProvider agentCardProvider) {
			this.agentCardProvider = agentCardProvider;
			return this;
		}

		// 设置includeContents属性的方法
		public Builder includeContents(boolean includeContents) {
			this.includeContents = includeContents;
			return this;
		}

		// 设置keyStrategyFactory属性的方法
		public Builder state(KeyStrategyFactory keyStrategyFactory) {
			this.keyStrategyFactory = keyStrategyFactory;
			return this;
		}

		// 设置compileConfig属性的方法
		public Builder compileConfig(CompileConfig compileConfig) {
			this.compileConfig = compileConfig;
			return this;
		}

		// 设置streaming属性的方法
		public Builder streaming(boolean streaming) {
			this.streaming = streaming;
			return this;
		}

		// 设置shareState属性的方法
		public Builder shareState(boolean shareState) {
			this.shareState = shareState;
			return this;
		}

		// 构建A2aRemoteAgent实例的方法
		public A2aRemoteAgent build() {
			// Validation
			// 验证name属性是否提供
			if (name == null || name.trim().isEmpty()) {
				throw new IllegalArgumentException("Name must be provided");
			}
			// 验证description属性是否提供
			if (description == null || description.trim().isEmpty()) {
				throw new IllegalArgumentException("Description must be provided");
			}
			// 验证agentCard或agentCardProvider是否提供
			if (agentCard == null) {
				if (null == agentCardProvider) {
					throw new IllegalArgumentException("AgentCard or AgentCardProvider must be provided");
				}
				// 根据agentCardProvider的支持情况获取agentCard
				if (agentCardProvider.supportGetAgentCardByName()) {
					agentCard = agentCardProvider.getAgentCard(name);
				}
				else {
					agentCard = agentCardProvider.getAgentCard();
				}
			}

			// 根据agentCard的能力设置streaming属性
			this.streaming = agentCard.capabilities().streaming();

			// 返回新的A2aRemoteAgent实例
			return new A2aRemoteAgent(this);
		}

	}
}
