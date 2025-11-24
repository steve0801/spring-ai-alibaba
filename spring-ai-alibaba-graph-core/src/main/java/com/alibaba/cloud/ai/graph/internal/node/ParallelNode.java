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
package com.alibaba.cloud.ai.graph.internal.node;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.streaming.GraphFlux;
import com.alibaba.cloud.ai.graph.streaming.ParallelGraphFlux;
import reactor.core.publisher.Flux;
import com.alibaba.cloud.ai.graph.utils.LifeListenerUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.graph.StateGraph.NODE_AFTER;
import static com.alibaba.cloud.ai.graph.StateGraph.NODE_BEFORE;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class ParallelNode extends Node {

	public static final String PARALLEL_PREFIX = "__PARALLEL__";

	// 格式化并行节点ID，添加前缀以标识为并行节点
	public static String formatNodeId(String nodeId) {
		return format("%s(%s)", PARALLEL_PREFIX, requireNonNull(nodeId, "nodeId cannot be null!"));
	}

	// 定义异步并行节点动作记录类，实现AsyncNodeActionWithConfig接口
	public record AsyncParallelNodeAction(String nodeId, List<AsyncNodeActionWithConfig> actions,
			List<String> actionNodeIds, Map<String, KeyStrategy> channels, CompileConfig compileConfig)
			implements AsyncNodeActionWithConfig {

		// 同步执行节点动作的方法
		private CompletableFuture<Map<String, Object>> evalNodeActionSync(AsyncNodeActionWithConfig action,
				String actualNodeId, OverAllState state, RunnableConfig config) {
			// 在节点执行前处理生命周期监听器
			LifeListenerUtil.processListenersLIFO(actualNodeId,
					new LinkedBlockingDeque<>(compileConfig.lifecycleListeners()), state.data(), config, NODE_BEFORE,
					null);
			// 应用动作并返回CompletableFuture，在完成后处理节点执行后的生命周期监听器
			return action.apply(state, config)
				.whenComplete((stringObjectMap, throwable) -> LifeListenerUtil.processListenersLIFO(actualNodeId,
						new LinkedBlockingDeque<>(compileConfig.lifecycleListeners()), state.data(), config, NODE_AFTER,
						throwable));
		}

	// 异步执行节点动作的方法
	private CompletableFuture<Map<String, Object>> evalNodeActionAsync(AsyncNodeActionWithConfig action,
			String actualNodeId, OverAllState state, RunnableConfig config, Executor executor) {
		// 使用指定的执行器异步执行
		return CompletableFuture.supplyAsync(() -> {
			try {
				// 同步执行并获取结果
				return evalNodeActionSync(action, actualNodeId, state, config).join();
			}
			// 捕获异常并重新抛出
			catch (Exception e) {
				throw new RuntimeException(e);
			}
	}, executor);
	}

	// 应用并行节点动作的主要方法
	@Override
	public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
		// 创建CompletableFuture列表用于存储所有并行任务
		List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
		// 遍历所有动作
		for (int i = 0; i < actions.size(); i++) {
			// 获取当前动作和实际节点ID
			AsyncNodeActionWithConfig action = actions.get(i);
			String actualNodeId = actionNodeIds.get(i);

			// 创建CompletableFuture，根据配置决定使用异步还是同步执行
			CompletableFuture<Map<String, Object>> future = config.metadata(nodeId)
				// 过滤出Executor类型的元数据
				.filter(value -> value instanceof Executor)
				// 转换为Executor类型
				.map(Executor.class::cast)
				// 如果存在Executor则使用异步执行，否则使用同步执行
				.map(executor -> evalNodeActionAsync(action, actualNodeId, state, config, executor))
				.orElseGet(() -> evalNodeActionSync(action, actualNodeId, state, config));

			// 将future添加到列表中
			futures.add(future);
		}

		// 等待所有任务完成，然后处理结果
		return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(v -> {
			// 收集所有结果
			List<Map<String, Object>> results = futures.stream()
				.map(CompletableFuture::join)
				.collect(Collectors.toList());

			// 处理并行执行结果
			return processParallelResults(results, state, actions);
		});
	}

		/**
		 * Process parallel execution results, handling GraphFlux, traditional Flux, and regular objects.
		 * Priority: GraphFlux > traditional Flux > regular objects
		 */
		// 处理并行执行结果的方法，处理GraphFlux、传统Flux和常规对象
		private Map<String, Object> processParallelResults(List<Map<String, Object>> results,
														   OverAllState state, List<AsyncNodeActionWithConfig> actionList) {

			// 检查结果中是否包含GraphFlux或传统Flux
			List<GraphFlux<?>> graphFluxList = new ArrayList<>();
			List<String> graphFluxNodeIds = new ArrayList<>();

			// 收集非流式状态
			Map<String, Object> mergedState = new HashMap<>();
			// 第一次遍历：收集GraphFlux和传统Flux实例
			for (int i = 0; i < results.size(); i++) {
				// 获取当前结果和动作
				Map<String, Object> result = results.get(i);
				AsyncNodeActionWithConfig action = actionList.get(i);
				// 生成有效的节点ID
				String effectiveNodeId = generateEffectiveNodeId(action, i);

				// 遍历结果中的每个条目
				for (Map.Entry<String, Object> entry : result.entrySet()) {
					// 获取值
					Object value = entry.getValue();

					// 如果值是GraphFlux类型
					if (value instanceof GraphFlux) {
						GraphFlux<?> graphFlux = (GraphFlux<?>) value;
						// 使用GraphFlux自己的nodeId，如果没有正确设置则生成一个
						String graphFluxNodeId = graphFlux.getNodeId() != null ?
								graphFlux.getNodeId() : effectiveNodeId;

						// 如果需要，创建具有正确nodeId的新GraphFlux
						if (!graphFluxNodeId.equals(graphFlux.getNodeId())) {
							@SuppressWarnings("unchecked")
							GraphFlux<Object> castedFlux = (GraphFlux<Object>) graphFlux;
							@SuppressWarnings("unchecked")
							GraphFlux<Object> newGraphFlux = GraphFlux.of(graphFluxNodeId, entry.getKey(),
									castedFlux.getFlux(), castedFlux.getMapResult(), castedFlux.getChunkResult());
							graphFlux = newGraphFlux;
						}

						// 将GraphFlux添加到列表中
						graphFluxList.add(graphFlux);
						graphFluxNodeIds.add(graphFluxNodeId);
					// 如果值是Flux类型
					} else if (value instanceof Flux flux) {
						// 传统Flux - 包装在GraphFlux中以便统一处理
						GraphFlux<Object> graphFlux = GraphFlux.of(effectiveNodeId, entry.getKey(), flux, null, null);
						graphFluxList.add(graphFlux);
					// 如果是常规对象
					} else {
						// 常规对象 - 添加到合并状态中
						Map<String, Object> singleEntryMap = Map.of(entry.getKey(), value);
						mergedState = OverAllState.updateState(mergedState, singleEntryMap, channels);
					}
				}
			}

			// 根据发现的内容处理结果
			if (!graphFluxList.isEmpty()) {
				// 我们有GraphFlux实例 - 创建ParallelGraphFlux以保留节点身份
				ParallelGraphFlux parallelGraphFlux = ParallelGraphFlux.of(graphFluxList);

				// 将ParallelGraphFlux放入合并状态中
				mergedState.put("__parallel_graph_flux__", parallelGraphFlux);
				return mergedState;
			// 如果没有流式输出
			}  else {
				Map<String, Object> initialState = new HashMap<>();
				// 没有流式输出，直接合并所有结果
				return results.stream()
						.reduce(initialState,
								(result, actionResult) -> OverAllState.updateState(result, actionResult, channels));
			}
		}

		/**
		 * Generate effective node ID for parallel execution.
		 * This ensures each parallel branch has a unique and traceable identifier.
		 */
		// 为并行执行生成有效的节点ID
		private String generateEffectiveNodeId(AsyncNodeActionWithConfig action, int index) {
			// 尝试从动作中提取有意义的标识符
			String actionClass = action.getClass().getSimpleName();
			// 格式化生成节点ID
			return String.format("%s_parallel_%d_%s", nodeId, index, actionClass);
		}
	}

	// 并行节点构造函数
	public ParallelNode(String id, List<AsyncNodeActionWithConfig> actions, List<String> actionNodeIds,
			Map<String, KeyStrategy> channels, CompileConfig compileConfig) {
		// 调用父类构造函数，使用格式化的节点ID
		super(formatNodeId(id),
				(config) -> new AsyncParallelNodeAction(formatNodeId(id), actions, actionNodeIds, channels, compileConfig));
	}

	// 重写isParallel方法，返回true表示这是并行节点
	@Override
	public final boolean isParallel() {
		return true;
	}

}
