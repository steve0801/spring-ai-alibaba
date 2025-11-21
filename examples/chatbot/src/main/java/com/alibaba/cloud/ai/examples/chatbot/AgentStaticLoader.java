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

package com.alibaba.cloud.ai.examples.chatbot;

import com.alibaba.cloud.ai.agent.studio.loader.AgentLoader;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.agent.BaseAgent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Nonnull;


/**
 * Static Agent Loader for programmatically provided agents.
 *
 * <p>This loader takes a static list of pre-created agent instances and makes them available
 * through the AgentLoader interface. Perfect for cases where you already have agent instances and
 * just need a convenient way to wrap them in an AgentLoader.
 *
 * <p>This class is not a Spring component by itself - instances are created programmatically and
 * then registered as beans via factory methods.
 */
@Component
class AgentStaticLoader implements AgentLoader {

	private final Map<String, BaseAgent> agents = new ConcurrentHashMap<>();

	public AgentStaticLoader(BaseAgent agent) {
		// 构造函数，接收一个BaseAgent实例作为参数

		GraphRepresentation representation = agent.getAndCompileGraph().stateGraph.getGraph(GraphRepresentation.Type.PLANTUML);
		// 获取agent的图表示，并将其转换为PlantUML格式的图形表示
		System.out.println(representation.content());
		// 打印图形表示的内容到控制台

		this.agents.put("research_agent", agent);
		// 将传入的agent实例以"research_agent"为键存储到agents映射中
	}

	@Override
	@Nonnull
	public List<String> listAgents() {
		// 重写listAgents方法，返回可用agent名称列表
		return agents.keySet().stream().toList();
		// 将agents映射中的所有键（agent名称）转换为List并返回

	}

	@Override
	public BaseAgent loadAgent(String name) {
		// 重写loadAgent方法，根据名称加载指定的agent
		if (name == null || name.trim().isEmpty()) {
			// 检查传入的agent名称是否为null或空字符串
			throw new IllegalArgumentException("Agent name cannot be null or empty");
			// 如果名称无效，抛出IllegalArgumentException异常
		}

		BaseAgent agent = agents.get(name);
		// 从agents映射中根据名称获取对应的agent实例
		if (agent == null) {
			// 如果未找到对应名称的agent
			throw new NoSuchElementException("Agent not found: " + name);
			// 抛出NoSuchElementException异常，提示agent未找到
		}

		return agent;
		// 返回找到的agent实例
	}
}
