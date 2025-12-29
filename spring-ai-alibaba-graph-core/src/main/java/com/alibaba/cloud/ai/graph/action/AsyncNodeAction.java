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
package com.alibaba.cloud.ai.graph.action;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.opentelemetry.context.Context;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Represents an asynchronous node action that operates on an agent state and returns
 * state update.
 *
 */
@FunctionalInterface
public interface AsyncNodeAction extends Function<OverAllState, CompletableFuture<Map<String, Object>>> {

	/**
	 * Applies this action to the given agent state.
	 * @param state the agent state
	 * @return a CompletableFuture representing the result of the action
	 */
	CompletableFuture<Map<String, Object>> apply(OverAllState state);

	/**
	 * Creates an asynchronous node action from a synchronous node action.
	 * @param syncAction the synchronous node action
	 * @return an asynchronous node action
	 */
	// 将同步节点动作转换为异步节点动作的静态方法
	static AsyncNodeAction node_async(NodeAction syncAction) {
		// 返回一个接收状态参数的Lambda表达式，实现异步节点动作
		return state -> {
			// 获取当前OpenTelemetry上下文
			Context context = Context.current();
			// 创建一个Map<String, Object>类型的CompletableFuture实例
			CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
			// 尝试执行同步动作并完成future
			try {
				// 执行同步节点动作并将结果设置到CompletableFuture中
				result.complete(syncAction.apply(state));
			}
			// 捕获执行过程中可能发生的异常
			catch (Exception e) {
				// 当发生异常时，将异常设置到CompletableFuture中
				result.completeExceptionally(e);
			}
			// 返回CompletableFuture实例
			return result;
		};
	}

}
