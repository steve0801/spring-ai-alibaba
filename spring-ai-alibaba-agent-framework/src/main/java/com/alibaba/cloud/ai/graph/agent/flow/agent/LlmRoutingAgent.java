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
package com.alibaba.cloud.ai.graph.agent.flow.agent;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.flow.builder.FlowAgentBuilder;
import com.alibaba.cloud.ai.graph.agent.flow.builder.FlowGraphBuilder;
import com.alibaba.cloud.ai.graph.agent.flow.enums.FlowAgentEnum;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;

import org.springframework.ai.chat.model.ChatModel;

public class LlmRoutingAgent extends FlowAgent {

	// 用于LLM路由决策的聊天模型
	private final ChatModel chatModel;

	// 构造函数，使用构建器初始化LlmRoutingAgent
	protected LlmRoutingAgent(LlmRoutingAgentBuilder builder) throws GraphStateException {
		// 调用父类构造函数初始化基本信息
		super(builder.name, builder.description, builder.compileConfig, builder.subAgents);
		// 设置聊天模型
		this.chatModel = builder.chatModel;
	}

	// 创建LlmRoutingAgent构建器的静态方法
	public static LlmRoutingAgentBuilder builder() {
		return new LlmRoutingAgentBuilder();
	}

	// 实现抽象方法，构建特定的图结构
	@Override
	protected StateGraph buildSpecificGraph(FlowGraphBuilder.FlowGraphConfig config) throws GraphStateException {
		// 在配置中设置聊天模型
		config.setChatModel(this.chatModel);
		// 使用FlowGraphBuilder构建路由类型的图
		return FlowGraphBuilder.buildGraph(FlowAgentEnum.ROUTING.getType(), config);
	}

	/**
	 * Builder for creating LlmRoutingAgent instances. Extends the common FlowAgentBuilder
	 * and adds LLM-specific configuration.
	 */
	// LlmRoutingAgent的构建器类，继承自FlowAgentBuilder
	public static class LlmRoutingAgentBuilder extends FlowAgentBuilder<LlmRoutingAgent, LlmRoutingAgentBuilder> {

		// 路由决策使用的聊天模型
		private ChatModel chatModel;

		/**
		 * Sets the ChatModel for LLM-based routing decisions.
		 * @param chatModel the chat model to use for routing
		 * @return this builder instance for method chaining
		 */
		// 设置用于LLM路由决策的聊天模型
		public LlmRoutingAgentBuilder model(ChatModel chatModel) {
			this.chatModel = chatModel;
			return this;
		}

		// 返回构建器自身的实例
		@Override
		protected LlmRoutingAgentBuilder self() {
			return this;
		}

		// 验证构建器配置的有效性
		@Override
		protected void validate() {
			// 调用父类验证方法
			super.validate();
			// 验证聊天模型是否已设置
			if (chatModel == null) {
				throw new IllegalArgumentException("ChatModel must be provided for LLM routing agent");
			}
		}

		// 构建LlmRoutingAgent实例
		@Override
		public LlmRoutingAgent build() throws GraphStateException {
			// 验证配置
			validate();
			// 创建新的LlmRoutingAgent实例
			return new LlmRoutingAgent(this);
		}

	}

}
