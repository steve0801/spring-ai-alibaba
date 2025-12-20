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

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.flow.builder.FlowGraphBuilder;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.scheduling.ScheduleConfig;
import com.alibaba.cloud.ai.graph.scheduling.ScheduledAgentTask;



import static com.alibaba.cloud.ai.graph.utils.Messageutils.convertToMessages;

public abstract class FlowAgent extends Agent {

	// 中断点列表，在执行流程中可能会在此处暂停
	protected List<String> interruptBefore;

	// 子代理列表，包含该流程代理管理的所有子代理
	protected List<Agent> subAgents;

	// 构造函数，初始化流程代理的基本信息和配置
	protected FlowAgent(String name, String description, CompileConfig compileConfig, List<Agent> subAgents)
			throws GraphStateException {
		// 调用父类构造函数初始化名称和描述
		super(name, description);
		// 设置编译配置
		this.compileConfig = compileConfig;
		// 设置子代理列表
		this.subAgents = subAgents;
	}

	// 初始化图结构的方法，用于构建流程代理的状态图
	@Override
	protected StateGraph initGraph() throws GraphStateException {
		// 使用 FlowGraphBuilder 构造图结构
		FlowGraphBuilder.FlowGraphConfig config = FlowGraphBuilder.FlowGraphConfig.builder()
			.name(this.name())
			.rootAgent(this)
			.subAgents(this.subAgents());

		// 委托给具体的图构建方法，根据代理类型构建特定的图结构
		return buildSpecificGraph(config);
	}

	// 调度方法，用于安排代理任务的执行
	@Override
	public ScheduledAgentTask schedule(ScheduleConfig scheduleConfig) throws GraphStateException {
		// 获取并编译图结构
		CompiledGraph compiledGraph = getAndCompileGraph();
		// 调用编译后的图进行任务调度
		return compiledGraph.schedule(scheduleConfig);
	}

	// 将流程代理转换为状态图的方法
	public StateGraph asStateGraph(){
		// 返回当前代理的图结构
		return getGraph();
	}

	/**
	 * Abstract method for subclasses to specify their graph building strategy. This
	 * method should be implemented by concrete FlowAgent subclasses to define how their
	 * specific graph structure should be built.
	 * @param config the graph configuration
	 * @return the constructed StateGraph
	 * @throws GraphStateException if graph construction fails
	 */
	// 抽象方法，由子类实现具体的图构建策略
	protected abstract StateGraph buildSpecificGraph(FlowGraphBuilder.FlowGraphConfig config)
			throws GraphStateException;

	// 获取编译配置的方法
	public CompileConfig compileConfig() {
		return compileConfig;
	}

	// 获取子代理列表的方法
	public List<Agent> subAgents() {
		return this.subAgents;
	}

	/**
	 * Creates a map with messages and input for String message
	 */
	// 创建输入映射的私有方法，将字符串消息转换为包含消息和输入的映射
	private Map<String, Object> createInputMap(String message) {
		return Map.of("messages", convertToMessages(message), "input", message);
	}

}
