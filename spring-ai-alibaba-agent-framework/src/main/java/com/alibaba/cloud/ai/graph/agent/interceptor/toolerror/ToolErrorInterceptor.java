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
package com.alibaba.cloud.ai.graph.agent.interceptor.toolerror;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;

public class ToolErrorInterceptor extends ToolInterceptor {

	// 拦截工具调用的核心方法，用于处理工具执行过程中的异常
	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		// 尝试执行工具调用
		try {
			// 调用处理器执行工具调用
			return handler.call(request);
		// 捕获执行过程中出现的异常
		} catch (Exception e) {
			// 当工具调用失败时，返回包含错误信息的工具调用响应
			return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
					"Tool failed: " + e.getMessage());
		}
	}

	// 获取拦截器名称的方法
	@Override
	public String getName() {
		// 返回工具错误拦截器的名称
		return "ToolError";
	}

	// 静态方法，返回Builder实例用于创建ToolErrorInterceptor
	public static Builder builder() {
		// 创建并返回新的Builder实例
		return new Builder();
	}

	// 构建器类，用于创建ToolErrorInterceptor实例
	public static class Builder {
		// 构建ToolErrorInterceptor实例的方法
		public ToolErrorInterceptor build() {
			// 创建并返回新的ToolErrorInterceptor实例
			return new ToolErrorInterceptor();
		}
	}
}

