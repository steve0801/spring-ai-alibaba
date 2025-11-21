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
package com.alibaba.cloud.ai.graph.agent.interceptor;

import java.util.List;

/**
 * Utility class for chaining model and tool interceptors.
 *
 * This implements the Chain of Responsibility pattern.
 * Interceptors are composed so that the first in the list becomes the outermost layer.
 */
public class InterceptorChain {

	/**
	 * Chain multiple ModelInterceptors into a single handler.
	 *
	 * The first interceptor wraps all others, creating a nested structure:
	 * interceptors[0] -> interceptors[1] -> ... -> base handler
	 *
	 * @param interceptors List of ModelInterceptors to chain
	 * @param baseHandler The base handler that executes the actual model call
	 * @return A composed handler, or the base handler if no interceptors
	 */
	public static ModelCallHandler chainModelInterceptors(
			List<ModelInterceptor> interceptors,
			ModelCallHandler baseHandler) {

		// 如果拦截器列表为空或长度为0，直接返回基础处理器
		if (interceptors == null || interceptors.isEmpty()) {
			return baseHandler;
		}

		// 初始化当前处理器为基础处理器
		ModelCallHandler current = baseHandler;

		// 从最后一个拦截器开始向前包装（右到左组合）
		// 这样确保第一个拦截器是最外层的
		for (int i = interceptors.size() - 1; i >= 0; i--) {
			// 获取当前位置的拦截器
			ModelInterceptor interceptor = interceptors.get(i);
			// 保存下一个处理器引用
			ModelCallHandler nextHandler = current;

			// 创建一个包装器，调用拦截器的 interceptModel 方法
			current = request -> interceptor.interceptModel(request, nextHandler);
		}

		// 返回组合后的处理器
		return current;
	}

	/**
	 * Chain multiple ToolInterceptors into a single handler.
	 *
	 * The first interceptor wraps all others, creating a nested structure:
	 * interceptors[0] -> interceptors[1] -> ... -> base handler
	 *
	 * @param interceptors List of ToolInterceptors to chain
	 * @param baseHandler The base handler that executes the actual tool call
	 * @return A composed handler, or the base handler if no interceptors
	 */
	public static ToolCallHandler chainToolInterceptors(
			List<ToolInterceptor> interceptors,
			ToolCallHandler baseHandler) {

		// 如果拦截器列表为空或长度为0，直接返回基础处理器
		if (interceptors == null || interceptors.isEmpty()) {
			return baseHandler;
		}

		// 初始化当前处理器为基础处理器
		ToolCallHandler current = baseHandler;

		// 从最后一个拦截器开始向前包装（右到左组合）
		// 这样确保第一个拦截器是最外层的
		for (int i = interceptors.size() - 1; i >= 0; i--) {
			// 获取当前位置的拦截器
			ToolInterceptor interceptor = interceptors.get(i);
			// 保存下一个处理器引用
			ToolCallHandler nextHandler = current;

			// 创建一个包装器，调用拦截器的 interceptToolCall 方法
			current = request -> interceptor.interceptToolCall(request, nextHandler);
		}

		// 返回组合后的处理器
		return current;
	}

	/**
	 * Example of how interceptors are chained:
	 *
	 * Given interceptors [auth, retry, cache] and baseHandler:
	 *
	 * 1. Start: current = baseHandler
	 * 2. Wrap with cache: current = req -> cache.wrap(req, baseHandler)
	 * 3. Wrap with retry: current = req -> retry.wrap(req, cache.wrap(...))
	 * 4. Wrap with auth: current = req -> auth.wrap(req, retry.wrap(...))
	 *
	 * Final call flow:
	 * request -> auth -> retry -> cache -> baseHandler
	 *
	 * Response flow:
	 * baseHandler -> cache -> retry -> auth -> response
	 */
}

