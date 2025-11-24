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
package com.alibaba.cloud.ai.graph.executor;

import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.GraphRunnerContext;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.Command;
import com.alibaba.cloud.ai.graph.action.InterruptableAction;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.exception.RunnableErrors;
import com.alibaba.cloud.ai.graph.streaming.GraphFlux;
import com.alibaba.cloud.ai.graph.streaming.ParallelGraphFlux;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.graph.GraphRunnerContext.INTERRUPT_AFTER;
import static com.alibaba.cloud.ai.graph.StateGraph.*;
import static java.util.Objects.requireNonNull;

/**
 * Node executor that processes node execution and result handling. This class
 * demonstrates inheritance by extending BaseGraphExecutor. It also demonstrates
 * polymorphism through its specific implementation of execute.
 */
public class NodeExecutor extends BaseGraphExecutor {

	private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

	private final MainGraphExecutor mainGraphExecutor;

	public NodeExecutor(MainGraphExecutor mainGraphExecutor) {
		this.mainGraphExecutor = mainGraphExecutor;
	}

	/**
	 * Implementation of the execute method. This demonstrates polymorphism as it provides
	 * a specific implementation for node execution.
	 * @param context the graph runner context
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with execution result
	 */
	@Override
	public Flux<GraphResponse<NodeOutput>> execute(GraphRunnerContext context, AtomicReference<Object> resultValue) {
		return executeNode(context, resultValue);
	}

	/**
	 * Executes a node and handles its result.
	 * @param context the graph runner context
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with node execution result
	 */
	// 执行节点并处理其结果的方法
	private Flux<GraphResponse<NodeOutput>> executeNode(GraphRunnerContext context,
			AtomicReference<Object> resultValue) {
		// 使用try-catch捕获执行过程中的异常
		try {
			// 设置当前节点ID为下一个节点ID
			context.setCurrentNodeId(context.getNextNodeId());
			// 获取当前节点ID
			String currentNodeId = context.getCurrentNodeId();
			// 获取当前节点的动作处理器
			AsyncNodeActionWithConfig action = context.getNodeAction(currentNodeId);

			// 如果动作处理器为空，则返回节点缺失错误
			if (action == null) {
				return Flux.just(GraphResponse.error(RunnableErrors.missingNode.exception(currentNodeId)));
			}

			// 检查动作是否为可中断动作
			if (action instanceof InterruptableAction) {
				// 从配置中获取状态更新元数据
				context.getConfig().metadata(RunnableConfig.STATE_UPDATE_METADATA_KEY).ifPresent(updateFromFeedback -> {
					// 如果更新数据是Map类型，则合并到当前状态
					if (updateFromFeedback instanceof Map<?, ?>) {
						context.mergeIntoCurrentState((Map<String, Object>) updateFromFeedback);
					} else {
						// 否则抛出运行时异常
						throw new RuntimeException();
					}
				});
				// 执行中断检查
				Optional<InterruptionMetadata> interruptMetadata = ((InterruptableAction) action)
					.interrupt(currentNodeId, context.cloneState(context.getCurrentStateData()), context.getConfig());
				// 如果存在中断元数据，则设置结果值并返回完成响应
				if (interruptMetadata.isPresent()) {
					resultValue.set(interruptMetadata.get());
					return Flux.just(GraphResponse.done(interruptMetadata.get()));
				}
			}

			// 触发节点执行前监听器
			context.doListeners(NODE_BEFORE, null);

			// 执行节点动作并获取CompletableFuture结果
			CompletableFuture<Map<String, Object>> future = action.apply(context.getOverallState(),
					context.getConfig());

			// 将CompletableFuture转换为Mono并处理结果
			return Mono.fromFuture(future)
					.flatMapMany(updateState -> handleActionResult(context, updateState, resultValue))
					// 异常处理
					.onErrorResume(error -> {
						context.doListeners(ERROR, new Exception(error));
						return Flux.just(GraphResponse.error(error));
					});

		}
		// 捕获异常并返回错误响应
		catch (Exception e) {
			return Flux.just(GraphResponse.error(e));
		}
	}

	/**
	 * Handles the action result and returns appropriate response.
	 * @param context the graph runner context
	 * @param updateState the updated state from the action
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with action result handling
	 */
	// 处理动作结果并返回适当响应的方法
	private Flux<GraphResponse<NodeOutput>> handleActionResult(GraphRunnerContext context,
			Map<String, Object> updateState, AtomicReference<Object> resultValue) {
		// 使用try-catch捕获处理过程中的异常
		try {
           // 优先级1: 检查GraphFlux (最高优先级)
			Optional<GraphFlux<?>> embedGraphFlux = getEmbedGraphFlux(updateState,context);
			// 如果存在GraphFlux，则处理GraphFlux
			if (embedGraphFlux.isPresent()) {
				return handleGraphFlux(context, embedGraphFlux.get(), updateState, resultValue);
			}

			// 优先级2: 检查ParallelGraphFlux
			Optional<ParallelGraphFlux> embedParallelGraphFlux = getEmbedParallelGraphFlux(updateState);
			// 如果存在ParallelGraphFlux，则处理ParallelGraphFlux
			if (embedParallelGraphFlux.isPresent()) {
				return handleParallelGraphFlux(context, embedParallelGraphFlux.get(), updateState, resultValue);
			}

			// 优先级3: 检查传统的Flux (向后兼容)
			Optional<Flux<GraphResponse<NodeOutput>>> embedFlux = getEmbedFlux(context, updateState);
			// 如果存在嵌入的Flux，则处理嵌入的Flux
			if (embedFlux.isPresent()) {
				return handleEmbeddedFlux(context, embedFlux.get(), updateState, resultValue);
			}

			// 合并更新状态到当前状态
			context.mergeIntoCurrentState(updateState);

			// 检查是否需要在边之前中断且当前节点在中断列表中
			if (context.getCompiledGraph().compileConfig.interruptBeforeEdge()
					&& context.getCompiledGraph().compileConfig.interruptsAfter()
						.contains(context.getCurrentNodeId())) {
				// 设置下一个节点ID为中断后标识
				context.setNextNodeId(INTERRUPT_AFTER);
			}
			else {
				// 获取下一个节点命令
				Command nextCommand = context.nextNodeId(context.getCurrentNodeId(), context.getCurrentStateData());
				// 设置下一个节点ID
				context.setNextNodeId(nextCommand.gotoNode());
			}
			// 构建节点输出并添加检查点
			NodeOutput output = context.buildNodeOutputAndAddCheckpoint(updateState);

			// 触发节点执行后监听器
			context.doListeners(NODE_AFTER, null);
			// 递归调用主执行处理程序
			return Flux.just(GraphResponse.of(output))
				.concatWith(Flux.defer(() -> mainGraphExecutor.execute(context, resultValue)));
		}
		// 捕获异常并返回错误响应
		catch (Exception e) {
			return Flux.just(GraphResponse.error(e));
		}
	}

	/**
	 * Gets embed flux from partial state.
	 * @param context the graph runner context
	 * @param partialState the partial state containing flux instances
	 * @return an Optional containing Data with the flux if found, empty otherwise
	 */
	// 从部分状态中获取嵌入的flux
	private Optional<Flux<GraphResponse<NodeOutput>>> getEmbedFlux(GraphRunnerContext context,
			Map<String, Object> partialState) {
		// 过滤出值为Flux类型的条目并获取第一个
		return partialState.entrySet().stream().filter(e -> e.getValue() instanceof Flux<?>).findFirst().map(e -> {
			// 获取chat flux实例
			var chatFlux = (Flux<?>) e.getValue();
			// 创建ChatResponse引用用于跟踪最后一个响应
			var lastChatResponseRef = new AtomicReference<ChatResponse>(null);
			// 创建GraphResponse引用用于跟踪最后一个图响应
			var lastGraphResponseRef = new AtomicReference<GraphResponse<NodeOutput>>(null);

            // 过滤元素，跳过getResult()为null的ChatResponse
            return chatFlux.filter(element -> {
                // skip ChatResponse.getResult() == null
                if (element instanceof ChatResponse response) {
                    return response.getResult() != null;
                }
                return true;
            })
			// 错误处理，记录Flux流中的错误
			.doOnError(error -> {
				// Debug logging for Flux errors
				log.error("Error occurred in embedded Flux stream for key '{}': {}",
					e.getKey(), error.getMessage(), error);
			})
			// 映射元素到GraphResponse
			.map(element -> {
				// 如果元素是ChatResponse
				if (element instanceof ChatResponse response) {
					// 获取最后一个响应
					ChatResponse lastResponse = lastChatResponseRef.get();
					// 如果是第一个响应
					if (lastResponse == null) {
						// 获取响应输出消息
						var message = response.getResult().getOutput();
						// 创建最后一个图响应
						GraphResponse<NodeOutput> lastGraphResponse = null;
						// 检查消息是否包含工具调用
						if (message.hasToolCalls()) {
							lastGraphResponse =
									GraphResponse.of(context.buildStreamingOutput(message, response, context.getCurrentNodeId()));
						} else {
							lastGraphResponse =
									GraphResponse.of(context.buildStreamingOutput(message, response, context.getCurrentNodeId()));
						}
						// 设置最后一个ChatResponse
						lastChatResponseRef.set(response);
						// 设置最后一个GraphResponse
						lastGraphResponseRef.set(lastGraphResponse);
						// 返回最后一个图响应
						return lastGraphResponse;
					}

					// 获取当前消息
					final var currentMessage = response.getResult().getOutput();

					// 检查当前消息是否包含工具调用
					if (currentMessage.hasToolCalls()) {
						// 创建图响应
						GraphResponse<NodeOutput> lastGraphResponse = GraphResponse
							.of(context.buildStreamingOutput(currentMessage, response, context.getCurrentNodeId()));
						// 设置最后一个图响应
						lastGraphResponseRef.set(lastGraphResponse);
						// 返回图响应
						return lastGraphResponse;
					}

					// 获取最后一个消息文本
					final var lastMessageText = requireNonNull(lastResponse.getResult().getOutput().getText(),
							"lastResponse text cannot be null");

					// 获取当前消息文本
					final var currentMessageText = currentMessage.getText();

					// 创建新的消息，将文本连接起来
					var newMessage = new AssistantMessage(
							currentMessageText != null ? lastMessageText.concat(currentMessageText) : lastMessageText,
							currentMessage.getMetadata(), currentMessage.getToolCalls(), currentMessage.getMedia());

					// 创建新的生成结果
					var newGeneration = new Generation(newMessage,
							response.getResult().getMetadata());

					// 创建新的响应
					ChatResponse newResponse = new ChatResponse(
							List.of(newGeneration), response.getMetadata());
					// 设置最后一个ChatResponse为新响应
					lastChatResponseRef.set(newResponse);
					// 创建图响应
					GraphResponse<NodeOutput> lastGraphResponse = GraphResponse
						.of(context.buildStreamingOutput(response.getResult().getOutput(), response, context.getCurrentNodeId()));
					// 返回图响应
					return lastGraphResponse;
				}
				// 如果元素是GraphResponse
				else if (element instanceof GraphResponse) {
					// 转换为GraphResponse<NodeOutput>
					GraphResponse<NodeOutput> graphResponse = (GraphResponse<NodeOutput>) element;
					// 设置最后一个图响应
					lastGraphResponseRef.set(graphResponse);
					// 返回图响应
					return graphResponse;
				}
				// 其他不支持的类型
				else {
					// 构造错误信息
					String errorMsg = "Unsupported flux element type: "
							+ (element != null ? element.getClass().getSimpleName() : "null");
					// 返回错误响应
					return GraphResponse.<NodeOutput>error(new IllegalArgumentException(errorMsg));
				}
			// 连接Mono用于处理流完成后的操作
			}).concatWith(Mono.defer(() -> {
				// 如果最后一个ChatResponse为空
				if (lastChatResponseRef.get() == null) {
					// 获取最后一个图响应
					GraphResponse<?> lastGraphResponse = lastGraphResponseRef.get();
					// 如果图响应存在且有结果值
					if (lastGraphResponse != null && lastGraphResponse.resultValue().isPresent()) {
						// 获取结果对象
						Object result = lastGraphResponse.resultValue().get();
						// 如果结果是Map类型
						if (result instanceof Map resultMap) {
							// 检查结果Map中是否不包含键且包含messages键
							if (!resultMap.containsKey(e.getKey()) && resultMap.containsKey("messages")) {
								// 获取消息列表
								List<Object> messages = (List<Object>) resultMap.get("messages");
								// 获取最后一条消息
								Object lastMessage = messages.get(messages.size() - 1);
								// 如果最后一条消息是AssistantMessage
								if (lastMessage instanceof AssistantMessage lastAssistantMessage) {
									// 将文本放入结果Map
									resultMap.put(e.getKey(), lastAssistantMessage.getText());
								}
							}
						}
						// 返回最后一个图响应
						return Mono.just(lastGraphResponseRef.get());
					}
					// 返回空Mono
					return Mono.empty();
				}
				// 如果最后一个ChatResponse不为空
				else {
					// 从Callable创建Mono
					return Mono.fromCallable(() -> {
						// 创建完成结果Map
						Map<String, Object> completionResult = new HashMap<>();
						// 将最后一个ChatResponse的结果输出放入完成结果
						completionResult.put(e.getKey(), lastChatResponseRef.get().getResult().getOutput());
						// 如果键不是messages，则也将结果输出放入messages键
						if (!e.getKey().equals("messages")) {
							completionResult.put("messages", lastChatResponseRef.get().getResult().getOutput());
						}
						// 返回完成的图响应
						return GraphResponse.done(completionResult);
					});
				}
			}));
		});
	}

	/**
	 * Handles embedded flux processing.
	 * @param context the graph runner context
	 * @param embedFlux the embedded flux to handle
	 * @param partialState the partial state
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with embedded flux handling result
	 */
	// 处理嵌入的flux处理
	private Flux<GraphResponse<NodeOutput>> handleEmbeddedFlux(GraphRunnerContext context,
			Flux<GraphResponse<NodeOutput>> embedFlux, Map<String, Object> partialState,
			AtomicReference<Object> resultValue) {

		// 创建最后一个数据的原子引用
		AtomicReference<GraphResponse<NodeOutput>> lastData = new AtomicReference<>();

		// 处理flux，映射数据
		Flux<GraphResponse<NodeOutput>> processedFlux = embedFlux.map(data -> {
			// 如果数据有输出
			if (data.getOutput() != null) {
				// 获取输出并连接
				var output = data.getOutput().join();
				// 设置为子图
				output.setSubGraph(true);
				// 创建新的图响应数据
				GraphResponse<NodeOutput> newData = GraphResponse.of(output);
				// 设置最后一个数据
				lastData.set(newData);
				// 返回新数据
				return newData;
			}
			// 设置最后一个数据为当前数据
			lastData.set(data);
			// 返回数据
			return data;
		});

		// 更新上下文的Mono
		Mono<Void> updateContextMono = Mono.fromRunnable(() -> {
			// 获取数据
			var data = lastData.get();
			// 如果数据为空则返回
			if (data == null)
				return;
			// 获取节点结果值
			var nodeResultValue = data.resultValue();

			// 如果节点结果值存在且是中断元数据
			if (nodeResultValue.isPresent() && nodeResultValue.get() instanceof InterruptionMetadata) {
				// 设置从嵌入返回的值为中断元数据
				context.setReturnFromEmbedWithValue(nodeResultValue.get());
				// 返回
				return;
			}

			// 过滤出非Flux类型的条目并收集到Map
			Map<String, Object> partialStateWithoutFlux = partialState.entrySet()
					.stream()
					.filter(e -> !(e.getValue() instanceof Flux))
					.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

			// 合并非flux状态到当前状态
			context.mergeIntoCurrentState(partialStateWithoutFlux);

			// 创建更新状态Map
			Map<String, Object> updateState = new HashMap<>();
			// 如果节点结果值存在
			if (nodeResultValue.isPresent()) {
				// 获取值
				Object value = nodeResultValue.get();
				// 如果值是Map类型
				if (value instanceof Map<?, ?>) {
					// 转换为更新状态
					updateState = (Map<String, Object>) value;
					// 合并更新状态到当前状态
					context.mergeIntoCurrentState(updateState);
				}
				// 如果不是Map类型则抛出异常
				else {
					throw new IllegalArgumentException("Node stream must return Map result using Data.done(),");
				}
			}

			// 使用try-catch处理异常
			try {
				// 获取下一个节点命令
				Command nextCommand = context.nextNodeId(context.getCurrentNodeId(), context.getCurrentStateData());
				// 设置下一个节点ID
				context.setNextNodeId(nextCommand.gotoNode());

				// 构建节点输出并添加检查点
				context.buildNodeOutputAndAddCheckpoint(updateState);

				// 触发节点执行后监听器
				context.doListeners(NODE_AFTER, null);
			}
			// 捕获异常并抛出运行时异常
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		});

		// 返回处理后的flux并连接更新上下文的Mono以及主执行器的执行结果
		return processedFlux
			.concatWith(updateContextMono.thenMany(Flux.defer(() -> mainGraphExecutor.execute(context, resultValue))));
	}


	/**
	 * Gets GraphFlux from partial state.
	 * @param partialState the partial state containing GraphFlux instances
	 * @return an Optional containing GraphFlux if found, empty otherwise
	 */
	/**
	 * Gets GraphFlux from partial state.
	 * @param partialState the partial state containing GraphFlux instances
	 * @return an Optional containing GraphFlux if found, empty otherwise
	 */
	// 从部分状态中获取嵌入的GraphFlux对象
	private Optional<GraphFlux<?>> getEmbedGraphFlux(Map<String, Object> partialState, GraphRunnerContext context) {
		// 过滤出值为GraphFlux类型的条目并获取第一个
		return partialState.entrySet()
				.stream()
				.filter(e -> e.getValue() instanceof GraphFlux)
				.findFirst()
				.map(e -> {
					// 将值转换为GraphFlux<Object>类型
					GraphFlux<Object> graphFlux = (GraphFlux<Object>) e.getValue();
					// 构建新的GraphFlux对象，如果graphFlux.getNodeId()有文本则使用它，否则使用context.getCurrentNodeId()
					// 如果graphFlux.getKey()有文本则使用它，否则使用条目的键
					return GraphFlux.of(StringUtils.hasText(graphFlux.getNodeId()) ? graphFlux.getNodeId() : context.getCurrentNodeId(),
							StringUtils.hasText(graphFlux.getKey()) ? graphFlux.getKey() : e.getKey(),
							graphFlux.getFlux(),
							graphFlux.getMapResult(),
							graphFlux.getChunkResult());
				});
	}

	/**
	 * Gets ParallelGraphFlux from partial state.
	 * @param partialState the partial state containing ParallelGraphFlux instances
	 * @return an Optional containing ParallelGraphFlux if found, empty otherwise
	 */
	// 从部分状态中获取嵌入的ParallelGraphFlux对象
	private Optional<ParallelGraphFlux> getEmbedParallelGraphFlux(Map<String, Object> partialState) {
		// 过滤出值为ParallelGraphFlux类型的条目并获取第一个
		return partialState.entrySet()
				.stream()
				.filter(e -> e.getValue() instanceof ParallelGraphFlux)
				.findFirst()
				.map(e -> (ParallelGraphFlux) e.getValue());
	}

	/**
	 * Handles GraphFlux processing with node ID preservation.
	 * @param context the graph runner context
	 * @param graphFlux the GraphFlux to handle
	 * @param partialState the partial state
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with GraphFlux handling result
	 */
	// 处理GraphFlux，保留节点ID
	private Flux<GraphResponse<NodeOutput>> handleGraphFlux(GraphRunnerContext context,
															GraphFlux<?> graphFlux, Map<String, Object> partialState,
															AtomicReference<Object> resultValue) {

		// 使用GraphFlux中的nodeId而不是context中的，以保留真实的节点身份
		String effectiveNodeId = graphFlux.getNodeId();
		// 创建原子引用用于存储最后一个数据
		AtomicReference<Object> lastDataRef = new AtomicReference<>();

		// 处理GraphFlux流，保留节点ID
		Flux<GraphResponse<NodeOutput>> processedFlux = graphFlux.getFlux()
				.map(element -> {
					// 如果graphFlux有mapResult函数则应用它，否则直接存储元素
					lastDataRef.set(graphFlux.hasMapResult() ? graphFlux.getMapResult().apply(element) : element);

					// 使用GraphFlux的nodeId创建StreamingOutput（保留真实的节点身份）
					StreamingOutput output = context.buildStreamingOutput(graphFlux, element, effectiveNodeId);
					// 返回包含输出的GraphResponse
					return GraphResponse.<NodeOutput>of(output);
				})
				// 错误映射，将错误包装为运行时异常
				.onErrorMap(error -> new RuntimeException("GraphFlux processing error in node: " + effectiveNodeId, error));

		// 处理完成和结果映射
		Mono<Void> updateContextMono = Mono.fromRunnable(() -> {
			// 获取最后一个数据
			Object lastData = lastDataRef.get();

			// 如果有mapResult函数则应用它
			Map<String, Object> resultMap = new HashMap<>();
			resultMap.put(graphFlux.getKey(), lastData);

			// 合并非GraphFlux状态
			Map<String, Object> partialStateWithoutGraphFlux = partialState.entrySet()
					.stream()
					.filter(e -> !(e.getValue() instanceof GraphFlux))
					.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

			// 合并到当前状态
			context.mergeIntoCurrentState(partialStateWithoutGraphFlux);

			// 合并GraphFlux处理的结果
			if (!resultMap.isEmpty()) {
				context.mergeIntoCurrentState(resultMap);
			}

			// 使用try-catch处理可能的异常
			try {
				// 获取下一个节点命令
				Command nextCommand = context.nextNodeId(context.getCurrentNodeId(), context.getCurrentStateData());
				// 设置下一个节点ID
				context.setNextNodeId(nextCommand.gotoNode());

				// 构建节点输出并添加检查点
				context.buildNodeOutputAndAddCheckpoint(partialStateWithoutGraphFlux);

				// 触发节点执行后监听器
				context.doListeners(NODE_AFTER, null);
			} catch (Exception e) {
				// 抛出运行时异常
				throw new RuntimeException(e);
			}
		});

		// 返回处理后的flux并连接更新上下文的Mono以及主执行器的执行结果
		return processedFlux
				.concatWith(updateContextMono.thenMany(Flux.defer(() -> mainGraphExecutor.execute(context, resultValue))));
	}

	/**
	 * Handles ParallelGraphFlux processing with node ID preservation for all parallel streams.
	 * @param context the graph runner context
	 * @param parallelGraphFlux the ParallelGraphFlux to handle
	 * @param partialState the partial state
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with ParallelGraphFlux handling result
	 */
	// 处理ParallelGraphFlux，为所有并行流保留节点ID
	private Flux<GraphResponse<NodeOutput>> handleParallelGraphFlux(GraphRunnerContext context,
																	ParallelGraphFlux parallelGraphFlux, Map<String, Object> partialState,
																	AtomicReference<Object> resultValue) throws Exception {

		// 如果ParallelGraphFlux为空，则处理非流式结果
		if (parallelGraphFlux.isEmpty()) {
			// Handle empty ParallelGraphFlux
			return handleNonStreamingResult(context, partialState, resultValue);
		}

		// 创建节点数据引用映射
		Map<String, AtomicReference<Object>> nodeDataRefs = new HashMap<>();

		// 从所有GraphFlux实例创建合并的flux，保留节点ID
		List<Flux<GraphResponse<NodeOutput>>> fluxList = parallelGraphFlux.getGraphFluxes()
				.stream()
				.map(graphFlux -> {
					// 获取节点ID
					String nodeId = graphFlux.getNodeId();
					// 创建节点数据引用
					AtomicReference<Object> nodeDataRef = new AtomicReference<>();
					// 将节点ID和数据引用放入映射
					nodeDataRefs.put(nodeId, nodeDataRef);

					// 返回处理后的flux
					return graphFlux.getFlux()
							.map(element -> {
								// 如果graphFlux有mapResult函数则应用它，否则直接存储元素
								nodeDataRef.set(graphFlux.hasMapResult() ? graphFlux.getMapResult().apply(element) : element);
								// 使用特定的nodeId创建StreamingOutput（保留并行节点身份）
								StreamingOutput output = context.buildStreamingOutput(graphFlux, element, nodeId);
								// 返回包含输出的GraphResponse
								return GraphResponse.<NodeOutput>of(output);
							})
							// 错误映射，将错误包装为运行时异常
							.onErrorMap(error -> new RuntimeException("ParallelGraphFlux processing error in node: " + nodeId, error));
				})
				.collect(Collectors.toList());

		// 合并所有并行流，同时保留节点身份
		Flux<GraphResponse<NodeOutput>> mergedFlux = Flux.merge(fluxList);

		// 处理所有节点的完成和结果映射
		Mono<Void> updateContextMono = Mono.fromRunnable(() -> {
			// 创建组合结果映射
			Map<String, Object> combinedResultMap = new HashMap<>();

			// 处理每个GraphFlux的结果，使用节点特定的前缀
			for (GraphFlux<?> graphFlux : parallelGraphFlux.getGraphFluxes()) {
				// 获取节点ID
				String nodeId = graphFlux.getNodeId();
				// 获取节点数据
				Object nodeData = nodeDataRefs.get(nodeId).get();

				// 将节点数据放入组合结果映射
				combinedResultMap.put(graphFlux.getKey(),nodeData);
			}

			// 合并非ParallelGraphFlux状态
			Map<String, Object> partialStateWithoutParallelGraphFlux = partialState.entrySet()
					.stream()
					.filter(e -> !(e.getValue() instanceof ParallelGraphFlux))
					.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

			// 合并到当前状态
			context.mergeIntoCurrentState(partialStateWithoutParallelGraphFlux);

			// 合并ParallelGraphFlux处理的组合结果
			if (!combinedResultMap.isEmpty()) {
				context.mergeIntoCurrentState(combinedResultMap);
			}

			// 使用try-catch处理可能的异常
			try {
				// 获取下一个节点命令
				Command nextCommand = context.nextNodeId(context.getCurrentNodeId(), context.getCurrentStateData());
				// 设置下一个节点ID
				context.setNextNodeId(nextCommand.gotoNode());

				// 构建节点输出并添加检查点
				context.buildNodeOutputAndAddCheckpoint(partialStateWithoutParallelGraphFlux);

				// 触发节点执行后监听器
				context.doListeners(NODE_AFTER, null);
			} catch (Exception e) {
				// 抛出运行时异常
				throw new RuntimeException(e);
			}
		});

		// 返回合并后的flux并连接更新上下文的Mono以及主执行器的执行结果
		return mergedFlux
				.concatWith(updateContextMono.thenMany(Flux.defer(() -> mainGraphExecutor.execute(context, resultValue))));
	}

	/**
	 * Handles non-streaming result processing.
	 * @param context the graph runner context
	 * @param partialState the partial state
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with non-streaming result
	 */
	// 处理非流式结果
	private Flux<GraphResponse<NodeOutput>> handleNonStreamingResult(GraphRunnerContext context,
																	 Map<String, Object> partialState, AtomicReference<Object> resultValue) throws Exception {
		// 检查是否需要在边之前中断且当前节点在中断列表中
		if (context.getCompiledGraph().compileConfig.interruptBeforeEdge()
				&& context.getCompiledGraph().compileConfig.interruptsAfter()
				.contains(context.getCurrentNodeId())) {
			// 设置下一个节点ID为INTERRUPT_AFTER
			context.setNextNodeId(INTERRUPT_AFTER);
		}
		else {
			// 获取下一个节点命令
			Command nextCommand = context.nextNodeId(context.getCurrentNodeId(), context.getCurrentStateData());
			// 设置下一个节点ID
			context.setNextNodeId(nextCommand.gotoNode());
		}

		// 构建节点输出并添加检查点
		NodeOutput output = context.buildNodeOutputAndAddCheckpoint(partialState);
		// 递归调用主执行处理程序
		return Flux.just(GraphResponse.of(output))
				.concatWith(Flux.defer(() -> mainGraphExecutor.execute(context, resultValue)));
	}

}
