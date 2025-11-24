/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.graph.internal.node;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.utils.TypeRef;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static java.lang.String.format;

/**
 * Represents an action to perform a subgraph on a given state with a specific
 * configuration.
 *
 * <p>
 * This record encapsulates the behavior required to execute a compiled graph using a
 * provided state. It implements the {@link AsyncNodeActionWithConfig} interface, ensuring
 * that the execution is handled asynchronously with the ability to configure settings.
 * </p>
 *
 * {@link OverAllState}.
 *
 * @param subGraph sub graph instance
 * @see CompiledGraph
 * @see AsyncNodeActionWithConfig
 */
public record SubCompiledGraphNodeAction(String nodeId, CompileConfig parentCompileConfig,
		CompiledGraph subGraph) implements AsyncNodeActionWithConfig {
	public String subGraphId() {
		return format("subgraph_%s", nodeId);
	}

	public String resumeSubGraphId() {
		return format("resume_%s", subGraphId());
	}

	/**
	 * Executes the given graph with the provided state and configuration.
	 * @param state The current state of the system, containing input data and
	 * intermediate results.
	 * @param config The configuration for the graph execution.
	 * @return A {@link CompletableFuture} that will complete with a result of type
	 * {@code Map<String, Object>}. If an exception occurs during execution, the future
	 * will be completed exceptionally.
	 */
	// 实现AsyncNodeActionWithConfig接口的apply方法，用于异步执行子图
	@Override
	public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
		// 从配置中获取是否需要恢复子图的元数据，如果没有则默认为false
		final boolean resumeSubgraph = config.metadata(resumeSubGraphId(), new TypeRef<Boolean>() {
		}).orElse(false);

		// 构建子图的运行配置，清除上下文、检查点ID和下一个节点信息
		RunnableConfig subGraphRunnableConfig = RunnableConfig.builder(config).clearContext().checkPointId(null).nextNode(null).build();

		// 获取父图和子图的检查点保存器
		var parentSaver = parentCompileConfig.checkpointSaver();
		var subGraphSaver = subGraph.compileConfig.checkpointSaver();

		// 如果子图有检查点保存器
		if (subGraphSaver.isPresent()) {
			// 但父图没有检查点保存器，则返回失败的CompletableFuture
			if (parentSaver.isEmpty()) {
				return CompletableFuture
					.failedFuture(new IllegalStateException("Missing CheckpointSaver in parent graph!"));
			}

			// 检查父图和子图的保存器是否为同一实例
			if (parentSaver.get() == subGraphSaver.get()) {
				// 如果是同一实例，则构建新的子图运行配置
				subGraphRunnableConfig = RunnableConfig.builder(config)
					.clearContext()
					.threadId(config.threadId()
						.map(threadId -> format("%s_%s", threadId, subGraphId()))
						.orElseGet(this::subGraphId))
					.nextNode(null)
					.checkPointId(null)
					.build();
			}
		}

		// 创建一个新的CompletableFuture用于返回结果
		final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

		try {
			// 如果需要恢复子图，则更新子图的状态
			if (resumeSubgraph) {
				subGraphRunnableConfig = subGraph.updateState(subGraphRunnableConfig, state.data());
			}

			// 获取子图的响应流
			var fluxStream = subGraph.graphResponseStream(state, subGraphRunnableConfig);

			// 完成future，返回包含子图ID和流的映射
			future.complete(Map.of(format("%s_%s", subGraphId(), UUID.randomUUID()), fluxStream));

		}
		// 捕获异常并完成异常future
		catch (Exception e) {

			future.completeExceptionally(e);
		}

		// 返回future
		return future;
	}

}
