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
package com.alibaba.cloud.ai.graph;

import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.Command;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.exception.Errors;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.exception.RunnableErrors;
import com.alibaba.cloud.ai.graph.internal.edge.Edge;
import com.alibaba.cloud.ai.graph.internal.edge.EdgeValue;
import com.alibaba.cloud.ai.graph.internal.node.ParallelNode;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import com.alibaba.cloud.ai.graph.scheduling.ScheduleConfig;
import com.alibaba.cloud.ai.graph.scheduling.ScheduledAgentTask;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.Edges;
import static com.alibaba.cloud.ai.graph.StateGraph.Nodes;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;

/**
 * The type Compiled graph.
 */
public class CompiledGraph {

	private static final Logger log = LoggerFactory.getLogger(CompiledGraph.class);

	// 中断后标记常量
	private static String INTERRUPT_AFTER = "__INTERRUPTED__";

	/**
	 * The State graph.
	 */
	// 状态图实例
	public final StateGraph stateGraph;

	/**
	 * The Compile config.
	 */
	// 编译配置
	public final CompileConfig compileConfig;

	/**
	 * The Node Factories - stores factory functions instead of instances to ensure thread safety.
	 */
	// 节点工厂映射表，存储工厂函数而非实例以确保线程安全
	final Map<String, Node.ActionFactory> nodeFactories = new LinkedHashMap<>();

	/**
	 * The Edges.
	 */
	// 边映射表
	final Map<String, EdgeValue> edges = new LinkedHashMap<>();

	// 键策略映射表
	private final Map<String, KeyStrategy> keyStrategyMap;

	// 处理后的节点、边和配置数据
	private final ProcessedNodesEdgesAndConfig processedData;

	// 最大迭代次数，默认为25
	private int maxIterations = 25;

	/**
	 * Constructs a CompiledGraph with the given StateGraph.
	 * @param stateGraph the StateGraph to be used in this CompiledGraph
	 * @param compileConfig the compile config
	 * @throws GraphStateException the graph state exception
	 */
	// 构造函数，使用给定的状态图和编译配置创建CompiledGraph
	protected CompiledGraph(StateGraph stateGraph, CompileConfig compileConfig) throws GraphStateException {
		// 设置最大迭代次数为编译配置中的递归限制
		maxIterations = compileConfig.recursionLimit();

		// 保存状态图
		this.stateGraph = stateGraph;
		// 构建键策略映射表
		this.keyStrategyMap = stateGraph.getKeyStrategyFactory()
			.apply()
			.entrySet()
			.stream()
			.map(e -> Map.entry(e.getKey(), e.getValue()))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		// 处理节点、边和配置数据
		this.processedData = ProcessedNodesEdgesAndConfig.process(stateGraph, compileConfig);

		// 检查中断节点是否存在
		// 检查中断前节点是否存在
		for (String interruption : processedData.interruptsBefore()) {
			if (!processedData.nodes().anyMatchById(interruption)) {
				throw Errors.interruptionNodeNotExist.exception(interruption);
			}
		}
		// 检查中断后节点是否存在
		for (String interruption : processedData.interruptsAfter()) {
			if (!processedData.nodes().anyMatchById(interruption)) {
				throw Errors.interruptionNodeNotExist.exception(interruption);
			}
		}

		// 重新创建可能已更新的编译配置
		this.compileConfig = CompileConfig.builder(compileConfig)
			.interruptsBefore(processedData.interruptsBefore())
			.interruptsAfter(processedData.interruptsAfter())
			.build();

		// 存储节点工厂 - 为了线程安全，存储工厂函数而不是实例
		for (var n : processedData.nodes().elements) {
			var factory = n.actionFactory();
			// 确保节点的动作工厂不为空
			Objects.requireNonNull(factory, format("action factory for node id '%s' is null!", n.id()));
			nodeFactories.put(n.id(), factory);
		}

		// 评估边
		for (var e : processedData.edges().elements) {
			var targets = e.targets();
			// 如果只有一个目标节点
			if (targets.size() == 1) {
				edges.put(e.sourceId(), targets.get(0));
			}
			// 如果有多个目标节点（并行节点）
			else {
				// 创建并行节点流的供应器
				Supplier<Stream<EdgeValue>> parallelNodeStream = () -> targets.stream()
					.filter(target -> nodeFactories.containsKey(target.id()));

				// 获取并行节点边
				var parallelNodeEdges = parallelNodeStream.get()
					.map(target -> new Edge(target.id()))
					.filter(ee -> processedData.edges().elements.contains(ee))
					.map(ee -> processedData.edges().elements.indexOf(ee))
					.map(index -> processedData.edges().elements.get(index))
					.toList();

				// 收集并行节点目标
				var parallelNodeTargets = parallelNodeEdges.stream()
					.map(ee -> ee.target().id())
					.collect(Collectors.toSet());

				// 如果并行节点目标数量大于1
				if (parallelNodeTargets.size() > 1) {

					// 查找第一个延迟节点

					// 获取条件边
					var conditionalEdges = parallelNodeEdges.stream()
						.filter(ee -> ee.target().value() != null)
						.toList();
					// 如果存在条件边，抛出不支持异常
					if (!conditionalEdges.isEmpty()) {
						throw Errors.unsupportedConditionalEdgeOnParallelNode.exception(e.sourceId(),
								conditionalEdges.stream().map(Edge::sourceId).toList());
					}
					// 抛出非法多目标异常
					throw Errors.illegalMultipleTargetsOnParallelNode.exception(e.sourceId(), parallelNodeTargets);
				}

			// 获取目标列表
			var targetList = parallelNodeStream.get().toList();

			// 创建动作列表
			var actions = targetList.stream()
				.map(target -> {
					try {
						// 应用编译配置获取节点动作
						return nodeFactories.get(target.id()).apply(compileConfig);
					} catch (GraphStateException ex) {
						// 如果创建并行节点动作失败，抛出运行时异常
						throw new RuntimeException("Failed to create parallel node action for target: " + target.id() + ". Cause: " + ex.getMessage(), ex);
					}
				})
				.toList();

			// 获取动作节点ID列表
			var actionNodeIds = targetList.stream().map(EdgeValue::id).toList();

			// 创建并行节点
			var parallelNode = new ParallelNode(e.sourceId(), actions, actionNodeIds, keyStrategyMap, compileConfig);

				// 将并行节点的动作工厂存入节点工厂映射表
				nodeFactories.put(parallelNode.id(), parallelNode.actionFactory());

				// 设置源节点到并行节点的边
				edges.put(e.sourceId(), new EdgeValue(parallelNode.id()));

				// 设置并行节点到目标节点的边
				edges.put(parallelNode.id(), new EdgeValue(parallelNodeTargets.iterator().next()));

			}

		}
	}

	// 获取指定配置的历史状态快照集合
	public Collection<StateSnapshot> getStateHistory(RunnableConfig config) {
		// 获取检查点保存器，如果不存在则抛出异常
		BaseCheckpointSaver saver = compileConfig.checkpointSaver()
			.orElseThrow(() -> (new IllegalStateException("Missing CheckpointSaver!")));

		// 列出所有检查点并转换为状态快照
		return saver.list(config)
			.stream()
			.map(checkpoint -> StateSnapshot.of(keyStrategyMap, checkpoint, config, stateGraph.getStateFactory()))
			.collect(toList());
	}

	/**
	 * Same of {@link #stateOf(RunnableConfig)} but throws an IllegalStateException if
	 * checkpoint is not found.
	 * @param config the RunnableConfig
	 * @return the StateSnapshot of the given RunnableConfig
	 * @throws IllegalStateException if the saver is not defined, or no checkpoint is
	 * found
	 */
	// 获取指定配置的状态快照，如果检查点不存在则抛出异常
	public StateSnapshot getState(RunnableConfig config) {
		return stateOf(config).orElseThrow(() -> (new IllegalStateException("Missing Checkpoint!")));
	}

	/**
	 * Get the StateSnapshot of the given RunnableConfig.
	 * @param config the RunnableConfig
	 * @return an Optional of StateSnapshot of the given RunnableConfig
	 * @throws IllegalStateException if the saver is not defined
	 */
	// 获取指定配置的状态快照（可选）
	public Optional<StateSnapshot> stateOf(RunnableConfig config) {
		// 获取检查点保存器，如果不存在则抛出异常
		BaseCheckpointSaver saver = compileConfig.checkpointSaver()
			.orElseThrow(() -> (new IllegalStateException("Missing CheckpointSaver!")));

		// 获取检查点并转换为状态快照
		return saver.get(config)
			.map(checkpoint -> StateSnapshot.of(keyStrategyMap, checkpoint, config, stateGraph.getStateFactory()));

	}

	/**
	 * Update the state of the graph with the given values. If asNode is given, it will be
	 * used to determine the next node to run. If not given, the next node will be
	 * determined by the state graph.
	 * @param config the RunnableConfig containing the graph state
	 * @param values the values to be updated
	 * @param asNode the node id to be used for the next node. can be null
	 * @return the updated RunnableConfig
	 * @throws Exception when something goes wrong
	 */
	// 更新图的状态，如果指定了asNode，则用于确定下一个要运行的节点
	public RunnableConfig updateState(RunnableConfig config, Map<String, Object> values, String asNode)
			throws Exception {
		// 获取检查点保存器，如果不存在则抛出异常
		BaseCheckpointSaver saver = compileConfig.checkpointSaver()
			.orElseThrow(() -> (new IllegalStateException("Missing CheckpointSaver!")));

		// 合并值与检查点值
		Checkpoint branchCheckpoint = saver.get(config)
			.map(Checkpoint::copyOf)
			.map(cp -> cp.updateState(values, keyStrategyMap))
			.orElseThrow(() -> (new IllegalStateException("Missing Checkpoint!")));

		String nextNodeId = null;
		// 如果指定了asNode
		if (asNode != null) {
			// 获取下一个节点命令
			var nextNodeCommand = nextNodeId(asNode, branchCheckpoint.getState(), config);

			// 设置下一个节点ID
			nextNodeId = nextNodeCommand.gotoNode();
			// 更新检查点状态
			branchCheckpoint = branchCheckpoint.updateState(nextNodeCommand.update(), keyStrategyMap);

		}
		// 在保存器中更新检查点
		RunnableConfig newConfig = saver.put(config, branchCheckpoint);

		// 构建并返回更新后的配置
		return RunnableConfig.builder(newConfig).checkPointId(branchCheckpoint.getId()).nextNode(nextNodeId).build();
	}

	/***
	 * Update the state of the graph with the given values.
	 * @param config the RunnableConfig containing the graph state
	 * @param values the values to be updated
	 * @return the updated RunnableConfig
	 * @throws Exception when something goes wrong
	 */
	// 更新图的状态（不指定下一个节点）
	public RunnableConfig updateState(RunnableConfig config, Map<String, Object> values) throws Exception {
		return updateState(config, values, null);
	}

	// 根据路由确定下一个节点ID
	private Command nextNodeId(EdgeValue route, Map<String, Object> state, String nodeId, RunnableConfig config)
			throws Exception {

		// 如果路由为空，抛出缺少边异常
		if (route == null) {
			throw RunnableErrors.missingEdge.exception(nodeId);
		}
		// 如果路由ID不为空，返回新命令
		if (route.id() != null) {
			return new Command(route.id(), state);
		}
		// 如果路由值不为空
		if (route.value() != null) {
			// 应用状态工厂创建总体状态
			OverAllState derefState = stateGraph.getStateFactory().apply(state);

			// 应用路由动作并获取命令
			var command = route.value().action().apply(derefState, config).get();

			// 获取新路由
			var newRoute = command.gotoNode();

			// 从映射中获取结果
			String result = route.value().mappings().get(newRoute);
			// 如果结果为空，抛出缺少节点映射异常
			if (result == null) {
				throw RunnableErrors.missingNodeInEdgeMapping.exception(nodeId, newRoute);
			}

			// 更新当前状态
			var currentState = OverAllState.updateState(state, command.update(), keyStrategyMap);

			// 返回新命令
			return new Command(result, currentState);
		}
		// 抛出执行错误异常
		throw RunnableErrors.executionError.exception(format("invalid edge value for nodeId: [%s] !", nodeId));
	}

	/**
	 * Determines the next node ID based on the current node ID and state.
	 * @param nodeId the current node ID
	 * @param state the current state
	 * @return the next node command
	 * @throws Exception if there is an error determining the next node ID
	 */
	// 根据当前节点ID和状态确定下一个节点ID
	private Command nextNodeId(String nodeId, Map<String, Object> state, RunnableConfig config) throws Exception {
		return nextNodeId(edges.get(nodeId), state, nodeId, config);

	}

	// 获取入口点命令
	private Command getEntryPoint(Map<String, Object> state, RunnableConfig config) throws Exception {
		var entryPoint = this.edges.get(START);
		return nextNodeId(entryPoint, state, "entryPoint", config);
	}

	// 判断是否应在指定节点前中断
	private boolean shouldInterruptBefore(String nodeId, String previousNodeId) {
		if (previousNodeId == null) { // FIX RESUME ERROR
			return false;
		}
		return compileConfig.interruptsBefore().contains(nodeId);
	}

	// 判断是否应在指定节点后中断
	private boolean shouldInterruptAfter(String nodeId, String previousNodeId) {
		if (nodeId == null || Objects.equals(nodeId, previousNodeId)) { // FIX RESUME
			// ERROR
			return false;
		}
		return (compileConfig.interruptBeforeEdge() && Objects.equals(nodeId, INTERRUPT_AFTER))
				|| compileConfig.interruptsAfter().contains(nodeId);
	}

	// 添加检查点
	private Optional<Checkpoint> addCheckpoint(RunnableConfig config, String nodeId, Map<String, Object> state,
			String nextNodeId, OverAllState overAllState) throws Exception {
		if (compileConfig.checkpointSaver().isPresent()) {
			var cp = Checkpoint.builder()
				.nodeId(nodeId)
				.state(cloneState(state, overAllState))
				.nextNodeId(nextNodeId)
				.build();
			compileConfig.checkpointSaver().get().put(config, cp);
			return Optional.of(cp);
		}
		return Optional.empty();

	}

	/**
	 * Gets initial state.
	 * @param inputs the inputs
	 * @param config the config
	 * @return the initial state
	 */
	// 获取初始状态
	public Map<String, Object> getInitialState(Map<String, Object> inputs, RunnableConfig config) {

		return compileConfig.checkpointSaver()
			.flatMap(saver -> saver.get(config))
			.map(cp -> OverAllState.updateState(cp.getState(), inputs, keyStrategyMap))
			.orElseGet(() -> OverAllState.updateState(new HashMap<>(), inputs, keyStrategyMap));
	}

	/**
	 * Clone state over all state.
	 * @param data the data
	 * @return the over all state
	 */
	// 克隆状态（带总体状态参数）
	OverAllState cloneState(Map<String, Object> data, OverAllState overAllState)
			throws IOException, ClassNotFoundException {
		return new OverAllState(stateGraph.getStateSerializer().cloneObject(data).data(), overAllState.keyStrategies(), overAllState.getStore());
	}

	/**
	 * Clone state over all state.
	 * @param data the data
	 * @return the over all state
	 */
	// 克隆状态
	public OverAllState cloneState(Map<String, Object> data) throws IOException, ClassNotFoundException {
		return new OverAllState(stateGraph.getStateSerializer().cloneObject(data).data());
	}

	/**
	 * Package-private access to nodes for ReactiveNodeGenerator.
	 */
	// 获取节点动作（包私有访问权限）
	public AsyncNodeActionWithConfig getNodeAction(String nodeId) {
		Node.ActionFactory factory = nodeFactories.get(nodeId);
		try {
			return factory != null ? factory.apply(compileConfig) : null;
		} catch (GraphStateException e) {
			throw new RuntimeException("Failed to create node action for nodeId: " + nodeId + ". Cause: " + e.getMessage(), e);
		}
	}

	/**
	 * Package-private access to edges for ReactiveNodeGenerator
	 */
	// 获取指定节点ID的边值
	public EdgeValue getEdge(String nodeId) {
		return edges.get(nodeId);
	}

	/**
	 * Package-private access to keyStrategyMap for ReactiveNodeGenerator
	 */
	// 获取键策略映射表（包私有访问权限，供ReactiveNodeGenerator使用）
	public Map<String, KeyStrategy> getKeyStrategyMap() {
		return keyStrategyMap;
	}

	/**
	 * Package-private access to maxIterations for ReactiveNodeGenerator
	 */
	// 获取最大迭代次数（包私有访问权限，供ReactiveNodeGenerator使用）
	public int getMaxIterations() {
		return maxIterations;
	}

	/**
	 * Sets the maximum number of iterations for the graph execution.
	 *
	 * @param maxIterations the maximum number of iterations
	 * @throws IllegalArgumentException if maxIterations is less than or equal to 0
	 * @deprecated use CompileConfig.recursionLimit() instead
	 */
	// 设置图执行的最大迭代次数（已废弃，推荐使用CompileConfig.recursionLimit()）
	@Deprecated(forRemoval = true)
	public void setMaxIterations(int maxIterations) {
		// 检查迭代次数是否大于0
		if (maxIterations <= 0) {
			throw new IllegalArgumentException("maxIterations must be > 0!");
		}
		// 设置最大迭代次数
		this.maxIterations = maxIterations;
	}

	// 调用图执行并获取响应（基于输入和配置）
	public GraphResponse<NodeOutput> invokeAndGetResponse(Map<String, Object> inputs, RunnableConfig config) {
		return graphResponseStream(inputs, config).last().block();
	}

	// 调用图执行并获取响应（基于状态和配置）
	public GraphResponse<NodeOutput> invokeAndGetResponse(OverAllState state, RunnableConfig config) {
		return graphResponseStream(state, config).last().block();
	}

	// 创建图响应流（基于输入和配置）
	public Flux<GraphResponse<NodeOutput>> graphResponseStream(Map<String, Object> inputs, RunnableConfig config) {
		return graphResponseStream(stateCreate(inputs), config);
	}

	// 创建图响应流（基于状态和配置）
	public Flux<GraphResponse<NodeOutput>> graphResponseStream(OverAllState state, RunnableConfig config) {
		// 检查配置是否为空
		Objects.requireNonNull(config, "config cannot be null");
		try {
			// 创建图运行器
			GraphRunner runner = new GraphRunner(this, config);
			// 运行图并返回结果流
			return runner.run(state);
		}
		// 捕获异常并返回错误流
		catch (Exception e) {
			return Flux.error(e);
		}
	}

	/**
	 * Creates a Flux stream of NodeOutput based on the provided inputs. This is the
	 * modern reactive approach using Project Reactor.
	 * @param inputs the input map
	 * @param config the invoke configuration
	 * @return a Flux stream of NodeOutput
	 */
	// 基于提供的输入创建NodeOutput的Flux流（使用Project Reactor的现代响应式方法）
	public Flux<NodeOutput> stream(Map<String, Object> inputs, RunnableConfig config) {
		return streamFromInitialNode(stateCreate(inputs), config);
	}

	/**
	 * Creates a Flux stream from an initial state.
	 * @param overAllState the initial state
	 * @param config the configuration
	 * @return a Flux stream of NodeOutput
	 */
	// 从初始状态创建Flux流
	public Flux<NodeOutput> streamFromInitialNode(OverAllState overAllState, RunnableConfig config) {
		// 检查配置是否为空
		Objects.requireNonNull(config, "config cannot be null");
		try {
			// 创建图运行器
			GraphRunner runner = new GraphRunner(this, config);
			// 运行图并处理结果
			return runner.run(overAllState).flatMap(data -> {
				// 如果数据已完成
				if (data.isDone()) {
					// 如果结果值存在且为NodeOutput类型，返回该值
					if (data.resultValue().isPresent() && data.resultValue().get() instanceof NodeOutput) {
						return Flux.just((NodeOutput)data.resultValue().get());
					} else {
						// 否则返回空流
						return Flux.empty();
					}
				}
				// 如果数据有错误
				if (data.isError()) {
					// 从Future获取数据并处理错误
					return Mono.fromFuture(data.getOutput()).onErrorMap(throwable -> throwable).flux();
				}

				// 从Future获取数据并返回流
				return Mono.fromFuture(data.getOutput()).flux();
			});
		}
		// 捕获异常并返回错误流
		catch (Exception e) {
			return Flux.error(e);
		}
	}

	/**
	 * Creates a Flux stream of NodeOutput based on the provided inputs.
	 * @param inputs the input map
	 * @return a Flux stream of NodeOutput
	 */
	// 基于提供的输入创建NodeOutput的Flux流
	public Flux<NodeOutput> stream(Map<String, Object> inputs) {
		return stream(inputs, RunnableConfig.builder().build());
	}

	/**
	 * Creates a Flux stream with empty inputs.
	 * @return a Flux stream of NodeOutput
	 */
	// 创建空输入的Flux流
	public Flux<NodeOutput> stream() {
		return stream(Map.of());
	}

	/**
	 * Creates a Flux stream for snapshots based on the provided inputs.
	 * @param inputs the input map
	 * @param config the invoke configuration
	 * @return a Flux stream of NodeOutput containing snapshots
	 */
	// 基于提供的输入创建快照的Flux流
	public Flux<NodeOutput> streamSnapshots(Map<String, Object> inputs, RunnableConfig config) {
		// 检查配置是否为空
		Objects.requireNonNull(config, "config cannot be null");
		// 使用快照流模式创建流
		return stream(inputs, config.withStreamMode(StreamMode.SNAPSHOTS));
	}

	/**
	 * Calls the graph execution and returns the final state.
	 * @param inputs the input map
	 * @param config the invoke configuration
	 * @return an Optional containing the final state
	 */
	// 调用图执行并返回最终状态
	public Optional<OverAllState> invoke(Map<String, Object> inputs, RunnableConfig config) {
		return Optional.ofNullable(stream(inputs, config).last().map(NodeOutput::state).block());
	}

	/**
	 * Calls the graph execution from initial state and returns the final state.
	 * @param overAllState the initial state
	 * @param config the configuration
	 * @return an Optional containing the final state
	 */
	// 从初始状态调用图执行并返回最终状态
	public Optional<OverAllState> invoke(OverAllState overAllState, RunnableConfig config) {
		return Optional
			.ofNullable(streamFromInitialNode(overAllState, config).last().map(NodeOutput::state).block());
	}

	/**
	 * Calls the graph execution and returns the final state.
	 * @param inputs the input map
	 * @return an Optional containing the final state
	 */
	// 调用图执行并返回最终状态（使用默认配置）
	public Optional<OverAllState> invoke(Map<String, Object> inputs) {
		return invoke(inputs, RunnableConfig.builder().build());
	}

	// 调用图执行并获取输出（基于状态和配置）
	public Optional<NodeOutput> invokeAndGetOutput(OverAllState overAllState, RunnableConfig config) {
		return Optional.ofNullable(streamFromInitialNode(overAllState, config).last().block());
	}

	// 调用图执行并获取输出（基于输入和配置）
	public Optional<NodeOutput> invokeAndGetOutput(Map<String, Object> inputs, RunnableConfig config) {
		return Optional.ofNullable(stream(inputs, config).last().block());
	}

	// 调用图执行并获取输出（使用默认配置）
	public Optional<NodeOutput> invokeAndGetOutput(Map<String, Object> inputs) {
		return invokeAndGetOutput(inputs, RunnableConfig.builder().build());
	}

	/**
	 * Schedule the graph execution with enhanced configuration options.
	 * @param scheduleConfig the schedule configuration
	 * @return a ScheduledGraphExecution instance for managing the scheduled task
	 */
	// 使用增强配置选项调度图执行
	public ScheduledAgentTask schedule(ScheduleConfig scheduleConfig) {
		return new ScheduledAgentTask(this, scheduleConfig).start();
	}

	// 创建总体状态（基于输入）
	private OverAllState stateCreate(Map<String, Object> inputs) {
		// Creates a new OverAllState instance using key strategies from the graph
		// and provided input data.
		return OverAllStateBuilder.builder()
			.withKeyStrategies(stateGraph.getKeyStrategyFactory().apply())
			.withData(inputs)
			.withStore(compileConfig.getStore())
			.build();
	}

	/**
	 * Get the last StateSnapshot of the given RunnableConfig.
	 * @param config - the RunnableConfig
	 * @return the last StateSnapshot of the given RunnableConfig if any
	 */
	// 获取给定RunnableConfig的最后一个状态快照
	Optional<StateSnapshot> lastStateOf(RunnableConfig config) {
		return getStateHistory(config).stream().findFirst();
	}

	/**
	 * Generates a drawable graph representation of the state graph.
	 * @param type the type of graph representation to generate
	 * @param title the title of the graph
	 * @param printConditionalEdges whether to print conditional edges
	 * @return a diagram code of the state graph
	 */
	// 生成状态图的可绘制图表示
	public GraphRepresentation getGraph(GraphRepresentation.Type type, String title, boolean printConditionalEdges) {

		String content = type.generator.generate(processedData.nodes(), processedData.edges(), title,
				printConditionalEdges);

		return new GraphRepresentation(type, content);
	}

	/**
	 * Generates a drawable graph representation of the state graph.
	 * @param type the type of graph representation to generate
	 * @param title the title of the graph
	 * @return a diagram code of the state graph
	 */
	// 生成状态图的可绘制图表示（默认打印条件边）
	public GraphRepresentation getGraph(GraphRepresentation.Type type, String title) {

		String content = type.generator.generate(processedData.nodes(), processedData.edges(), title, true);

		return new GraphRepresentation(type, content);
	}

	/**
	 * Generates a drawable graph representation of the state graph with default title.
	 * @param type the type of graph representation to generate
	 * @return a diagram code of the state graph
	 */
	// 生成状态图的可绘制图表示（使用默认标题和打印条件边）
	public GraphRepresentation getGraph(GraphRepresentation.Type type) {
		return getGraph(type, "Graph Diagram", true);
	}

	/**
	 * The enum Stream mode.
	 */
	// 流模式枚举
	public enum StreamMode {

		/**
		 * Values stream mode.
		 */
		// 值流模式
		VALUES,
		/**
		 * Snapshots stream mode.
		 */
		// 快照流模式
		SNAPSHOTS

	}

}

/**
 * The type Processed nodes edges and config.
 */
// 处理后的节点、边和配置记录类
record ProcessedNodesEdgesAndConfig(Nodes nodes, Edges edges, Set<String> interruptsBefore,
		Set<String> interruptsAfter) {

	/**
	 * Instantiates a new Processed nodes edges and config.
	 * @param stateGraph the state graph
	 * @param config the config
	 */
	// 构造函数，实例化新的处理后的节点、边和配置
	ProcessedNodesEdgesAndConfig(StateGraph stateGraph, CompileConfig config) {
		this(stateGraph.nodes, stateGraph.edges, config.interruptsBefore(), config.interruptsAfter());
	}

	/**
	 * Process processed nodes edges and config.
	 * @param stateGraph the state graph
	 * @param config the config
	 * @return the processed nodes edges and config
	 * @throws GraphStateException the graph state exception
	 */
	// 处理节点、边和配置
	static ProcessedNodesEdgesAndConfig process(StateGraph stateGraph, CompileConfig config)
			throws GraphStateException {

		// 获取子图节点
		var subgraphNodes = stateGraph.nodes.onlySubStateGraphNodes();

		// 如果子图节点为空，返回新的处理后的节点、边和配置
		if (subgraphNodes.isEmpty()) {
			return new ProcessedNodesEdgesAndConfig(stateGraph, config);
		}

		// 初始化中断前和中断后节点集合
		var interruptsBefore = config.interruptsBefore();
		var interruptsAfter = config.interruptsAfter();
		// 创建除子图节点外的节点集合
		var nodes = new Nodes(stateGraph.nodes.exceptSubStateGraphNodes());
		// 创建边集合
		var edges = new Edges(stateGraph.edges.elements);

		// 遍历子图节点
		for (var subgraphNode : subgraphNodes) {

			// 获取子图工作流
			var sgWorkflow = subgraphNode.subGraph();

			// 递归处理子图
			ProcessedNodesEdgesAndConfig processedSubGraph = process(sgWorkflow, config);
			// 获取处理后的子图节点和边
			Nodes processedSubGraphNodes = processedSubGraph.nodes;
			Edges processedSubGraphEdges = processedSubGraph.edges;

			//
			// Process START Node
			//
			// 获取处理后的子图起始边
			var sgEdgeStart = processedSubGraphEdges.edgeBySourceId(START).orElseThrow();

			// 如果起始边是并行的，抛出异常
			if (sgEdgeStart.isParallel()) {
				throw new GraphStateException("subgraph not support start with parallel branches yet!");
			}

			// 获取起始边的目标
			var sgEdgeStartTarget = sgEdgeStart.target();

			// 如果起始边目标ID为空，抛出异常
			if (sgEdgeStartTarget.id() == null) {
				throw new GraphStateException(format("the target for node '%s' is null!", subgraphNode.id()));
			}

			// 格式化起始边真实目标ID
			var sgEdgeStartRealTargetId = subgraphNode.formatId(sgEdgeStartTarget.id());

			// Process Interruption (Before) Subgraph(s)
			// 处理中断前子图
			interruptsBefore = interruptsBefore.stream()
				.map(interrupt -> Objects.equals(subgraphNode.id(), interrupt) ? sgEdgeStartRealTargetId : interrupt)
				.collect(Collectors.toUnmodifiableSet());

			// 获取指向子图节点的边
			var edgesWithSubgraphTargetId = edges.edgesByTargetId(subgraphNode.id());

			// 如果指向子图节点的边为空，抛出异常
			if (edgesWithSubgraphTargetId.isEmpty()) {
				throw new GraphStateException(
						format("the node '%s' is not present as target in graph!", subgraphNode.id()));
			}

			// 遍历指向子图节点的边
			for (var edgeWithSubgraphTargetId : edgesWithSubgraphTargetId) {

				// 更新边的源和目标ID
				var newEdge = edgeWithSubgraphTargetId.withSourceAndTargetIdsUpdated(subgraphNode, Function.identity(),
						id -> new EdgeValue((Objects.equals(id, subgraphNode.id())
								? subgraphNode.formatId(sgEdgeStartTarget.id()) : id)));
				// 移除旧边并添加新边
				edges.elements.remove(edgeWithSubgraphTargetId);
				edges.elements.add(newEdge);
			}
			//
			// Process END Nodes
			//
			// 获取处理后的子图结束边
			var sgEdgesEnd = processedSubGraphEdges.edgesByTargetId(END);

			// 获取从子图节点出发的边
			var edgeWithSubgraphSourceId = edges.edgeBySourceId(subgraphNode.id()).orElseThrow();

			// 如果从子图节点出发的边是并行的，抛出异常
			if (edgeWithSubgraphSourceId.isParallel()) {
				throw new GraphStateException("subgraph not support routes to parallel branches yet!");
			}

			// Process Interruption (After) Subgraph(s)
			// 处理中断后子图
			if (interruptsAfter.contains(subgraphNode.id())) {

				// 构造异常消息
				var exceptionMessage = (edgeWithSubgraphSourceId.target()
					.id() == null) ? "'interruption after' on subgraph is not supported yet!" : format(
							"'interruption after' on subgraph is not supported yet! consider to use 'interruption before' node: '%s'",
							edgeWithSubgraphSourceId.target().id());
				// 抛出异常
				throw new GraphStateException(exceptionMessage);
			}

			// 处理结束边
			sgEdgesEnd.stream()
				.map(e -> e.withSourceAndTargetIdsUpdated(subgraphNode, subgraphNode::formatId,
						id -> (Objects.equals(id, END) ? edgeWithSubgraphSourceId.target()
								: new EdgeValue(subgraphNode.formatId(id)))))
				.forEach(edges.elements::add);
			// 移除从子图节点出发的边
			edges.elements.remove(edgeWithSubgraphSourceId);

			//
			// Process edges
			//
			// 处理边
			processedSubGraphEdges.elements.stream()
				.filter(e -> !Objects.equals(e.sourceId(), START))
				.filter(e -> !e.anyMatchByTargetId(END))
				.map(e -> e.withSourceAndTargetIdsUpdated(subgraphNode, subgraphNode::formatId,
						id -> new EdgeValue(subgraphNode.formatId(id))))
				.forEach(edges.elements::add);

			//
			// Process nodes
			//
			// 处理节点
			processedSubGraphNodes.elements.stream().map(n -> {
				return n.withIdUpdated(subgraphNode::formatId);
			}).forEach(nodes.elements::add);
		}

		// 返回新的处理后的节点、边和配置
		return new ProcessedNodesEdgesAndConfig(nodes, edges, interruptsBefore, interruptsAfter);
	}
}
