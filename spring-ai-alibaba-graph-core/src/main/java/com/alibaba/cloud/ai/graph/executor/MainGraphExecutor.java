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

import com.alibaba.cloud.ai.graph.GraphRunnerContext;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.action.Command;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.utils.TypeRef;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.alibaba.cloud.ai.graph.GraphRunnerContext.INTERRUPT_AFTER;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.ERROR;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * Main graph executor that handles the primary execution flow. This class demonstrates
 * inheritance by extending BaseGraphExecutor. It also demonstrates polymorphism through
 * its specific implementation of execute.
 */
public class MainGraphExecutor extends BaseGraphExecutor {

	private final NodeExecutor nodeExecutor;

	public MainGraphExecutor() {
		this.nodeExecutor = new NodeExecutor(this);
	}

	/**
	 * Implementation of the execute method. This demonstrates polymorphism as it provides
	 * a specific implementation for main execution flow.
	 * @param context the graph runner context
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with execution result
	 */
	// 重写execute方法，提供主执行流程的具体实现
	@Override
	public Flux<GraphResponse<NodeOutput>> execute(GraphRunnerContext context, AtomicReference<Object> resultValue) {
		// 使用try-catch捕获执行过程中的异常
		try {
			// 检查是否应该停止执行或已达到最大迭代次数
			if (context.shouldStop() || context.isMaxIterationsReached()) {
				// 如果是，则调用处理完成的方法
				return handleCompletion(context, resultValue);
			}

			// 获取并重置从嵌入返回的值
			final var returnFromEmbed = context.getReturnFromEmbedAndReset();
			// 检查是否有返回值
			if (returnFromEmbed.isPresent()) {
				// 尝试将返回值转换为中断元数据
				var interruption = returnFromEmbed.get().value(new TypeRef<InterruptionMetadata>() {
				});
				// 检查中断元数据是否存在
				if (interruption.isPresent()) {
					// 如果存在，则返回完成的图响应
					return Flux.just(GraphResponse.done(interruption.get()));
				}
				// 否则返回带有检查点的节点输出
				return Flux.just(GraphResponse.done(context.buildNodeOutputAndAddCheckpoint(Map.of())));
			}

			// 检查当前节点ID是否存在且配置中该节点被中断
			if (context.getCurrentNodeId() != null && context.getConfig().isInterrupted(context.getCurrentNodeId())) {
				// 标记该节点已恢复执行
				context.getConfig().withNodeResumed(context.getCurrentNodeId());
				// 返回当前状态数据的完成响应
				return Flux.just(GraphResponse.done(GraphResponse.done(context.getCurrentStateData())));
			}

			// 检查是否为起始节点
			if (context.isStartNode()) {
				// 处理起始节点
				return handleStartNode(context);
			}

			// 检查是否为结束节点
			if (context.isEndNode()) {
				// 处理结束节点
				return handleEndNode(context, resultValue);
			}

			// 获取并重置恢复来源
			final var resumeFrom = context.getResumeFromAndReset();
			// 检查是否有恢复来源
			if (resumeFrom.isPresent()) {
				// 检查是否需要在边之前中断且下一个节点是中断后节点
				if (context.getCompiledGraph().compileConfig.interruptBeforeEdge()
						&& java.util.Objects.equals(context.getNextNodeId(), INTERRUPT_AFTER)) {
					// 获取下一个节点命令
					var nextNodeCommand = context.nextNodeId(resumeFrom.get(), context.getCurrentStateData());
					// 设置下一个节点ID
					context.setNextNodeId(nextNodeCommand.gotoNode());
					// 清空当前节点ID
					context.setCurrentNodeId(null);
				}
			}

			// 检查是否应该中断执行
			if (context.shouldInterrupt()) {
				// 使用try-catch处理中断过程中的异常
				try {
					// 构建中断元数据
					InterruptionMetadata metadata = InterruptionMetadata
						.builder(context.getCurrentNodeId(), context.cloneState(context.getCurrentStateData()))
						.build();
					// 返回中断元数据的完成响应
					return Flux.just(GraphResponse.done(metadata));
				}
				// 捕获异常并返回错误响应
				catch (Exception e) {
					return Flux.just(GraphResponse.error(e));
				}
			}

			// 执行节点执行器
			return nodeExecutor.execute(context, resultValue);
		}
		// 捕获执行过程中的异常
		catch (Exception e) {
			// 触发错误监听器
			context.doListeners(ERROR, e);
			// 记录错误日志
			org.slf4j.LoggerFactory.getLogger(com.alibaba.cloud.ai.graph.GraphRunner.class)
				.error("Error during graph execution", e);
			// 返回错误响应
			return Flux.just(GraphResponse.error(e));
		}
	}

	/**
	 * Handles the start node execution.
	 * @param context the graph runner context
	 * @return Flux of GraphResponse with start node handling result
	 */
	// 处理起始节点执行的方法
	private Flux<GraphResponse<NodeOutput>> handleStartNode(GraphRunnerContext context) {
		// 使用try-catch捕获处理过程中的异常
		try {
			// 触发起始监听器
			context.doListeners(START, null);
			// 获取入口点命令
			Command nextCommand = context.getEntryPoint();
			// 设置下一个节点ID
			context.setNextNodeId(nextCommand.gotoNode());

			// 添加检查点
			Optional<Checkpoint> cp = context.addCheckpoint(START, context.getNextNodeId());
			// 构建输出
			NodeOutput output = context.buildOutput(START, cp);

			// 设置当前节点ID为下一个节点ID
			context.setCurrentNodeId(context.getNextNodeId());
			// 递归调用主执行处理程序
			return Flux.just(GraphResponse.of(output))
				.concatWith(Flux.defer(() -> execute(context, new AtomicReference<>())));
		}
		// 捕获异常并返回错误响应
		catch (Exception e) {
			return Flux.just(GraphResponse.error(e));
		}
	}

	/**
	 * Handles the end node execution.
	 * @param context the graph runner context
	 * @param resultValue the atomic reference to store the result value
	 * @return Flux of GraphResponse with end node handling result
	 */
	// 处理结束节点执行的方法
	private Flux<GraphResponse<NodeOutput>> handleEndNode(GraphRunnerContext context,
			AtomicReference<Object> resultValue) {
		// 使用try-catch捕获处理过程中的异常
		try {
			// 触发结束监听器
			context.doListeners(END, null);
			// 构建结束节点输出
			NodeOutput output = context.buildNodeOutput(END);
			// 返回输出响应并连接完成处理结果
			return Flux.just(GraphResponse.of(output))
				.concatWith(Flux.defer(() -> handleCompletion(context, resultValue)));
		}
		// 捕获异常并返回错误响应
		catch (Exception e) {
			return Flux.just(GraphResponse.error(e));
		}
	}

}

