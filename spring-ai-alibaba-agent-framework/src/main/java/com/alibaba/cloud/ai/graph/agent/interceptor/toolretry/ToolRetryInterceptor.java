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
package com.alibaba.cloud.ai.graph.agent.interceptor.toolretry;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool interceptor that automatically retries failed tool calls with configurable backoff.
 *
 * Supports retrying on specific exceptions and exponential backoff.
 *
 * Example:
 * ToolRetryInterceptor interceptor = ToolRetryInterceptor.builder()
 *     .maxRetries(3)
 *     .backoffFactor(2.0)
 *     .initialDelay(1000)
 *     .build();
 */
public class ToolRetryInterceptor extends ToolInterceptor {

	private static final Logger log = LoggerFactory.getLogger(ToolRetryInterceptor.class);

	// 最大重试次数
	private final int maxRetries;
	// 需要重试的工具名称集合
	private final Set<String> toolNames;
	// 判断是否应该重试的谓词条件
	private final Predicate<Exception> retryOn;
	// 失败时的行为策略
	private final OnFailureBehavior onFailure;
	// 错误信息格式化函数
	private final Function<Exception, String> errorFormatter;
	// 退避因子，用于计算延迟时间
	private final double backoffFactor;
	// 初始延迟时间（毫秒）
	private final long initialDelayMs;
	// 最大延迟时间（毫秒）
	private final long maxDelayMs;
	// 是否启用抖动机制
	private final boolean jitter;

	// 私有构造函数，通过Builder模式创建ToolRetryInterceptor实例
	private ToolRetryInterceptor(Builder builder) {
		// 设置最大重试次数
		this.maxRetries = builder.maxRetries;
		// 设置需要重试的工具名称集合，如果为空则设为null
		this.toolNames = builder.toolNames != null ? new HashSet<>(builder.toolNames) : null;
		// 设置重试条件谓词
		this.retryOn = builder.retryOn;
		// 设置失败时的行为策略
		this.onFailure = builder.onFailure;
		// 设置错误信息格式化函数
		this.errorFormatter = builder.errorFormatter;
		// 设置退避因子
		this.backoffFactor = builder.backoffFactor;
		// 设置初始延迟时间
		this.initialDelayMs = builder.initialDelayMs;
		// 设置最大延迟时间
		this.maxDelayMs = builder.maxDelayMs;
		// 设置是否启用抖动
		this.jitter = builder.jitter;
	}

	// 静态方法，返回Builder实例用于创建ToolRetryInterceptor
	public static Builder builder() {
		return new Builder();
	}

	// 拦截工具调用的核心方法，实现重试逻辑
	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		// 获取工具名称
		String toolName = request.getToolName();

		// 检查此工具是否应该重试，如果不应该重试则直接调用处理器
		if (toolNames != null && !toolNames.contains(toolName)) {
			return handler.call(request);
		}

		// 保存最后一次异常和重试次数
		Exception lastException = null;
		int attempt = 0;

		// 循环重试直到达到最大重试次数
		while (attempt <= maxRetries) {
			try {
				// 尝试调用工具
				return handler.call(request);
			}
			// 捕获异常并进行重试处理
			catch (Exception e) {
				// 保存最后一次异常
				lastException = e;

				// 检查此异常是否应该重试，如果不应该重试则重新抛出
				if (!retryOn.test(e)) {
					log.debug("Exception {} not configured for retry, re-throwing", e.getClass().getSimpleName());
					throw e;
				}

				// 如果已达到最大重试次数，则退出循环
				if (attempt == maxRetries) {
					// Max retries reached
					break;
				}

				// 计算延迟时间
				long delay = calculateDelay(attempt);
				// 记录警告日志，工具调用失败并准备重试
				log.warn("Tool '{}' failed (attempt {}/{}), retrying in {}ms: {}",
						toolName, attempt + 1, maxRetries + 1, delay, e.getMessage());

				try {
					// 线程休眠指定延迟时间
					Thread.sleep(delay);
				}
				// 捕获中断异常
				catch (InterruptedException ie) {
					// 恢复中断状态并抛出运行时异常
					Thread.currentThread().interrupt();
					throw new RuntimeException("Retry interrupted", ie);
				}

				// 增加重试次数
				attempt++;
			}
		}

		// 所有重试都已用尽
		if (onFailure == OnFailureBehavior.RAISE) {
			// 抛出运行时异常，包含所有重试失败的信息
			throw new RuntimeException("Tool call failed after " + (maxRetries + 1) + " attempts", lastException);
		}
		else {
			// 将错误信息作为工具响应返回
			String errorMessage = errorFormatter != null
					? errorFormatter.apply(lastException)
					: "Tool call failed after " + (maxRetries + 1) + " attempts: " + lastException.getMessage();

			// 记录错误日志，工具调用最终失败
			log.error("Tool '{}' failed after {} attempts: {}", toolName, maxRetries + 1, lastException.getMessage());
			// 返回包含错误信息的工具调用响应
			return ToolCallResponse.of(request.getToolCallId(), request.getToolName(), errorMessage);
		}
	}

	// 计算延迟时间的方法
	private long calculateDelay(int retryNumber) {
		// 根据退避因子计算延迟时间
		long delay = (long) (initialDelayMs * Math.pow(backoffFactor, retryNumber));
		// 确保延迟时间不超过最大延迟时间
		delay = Math.min(delay, maxDelayMs);

		// 如果启用抖动机制
		if (jitter) {
			// 添加随机抖动 ±25%
			double jitterFactor = 0.75 + (Math.random() * 0.5);
			delay = (long) (delay * jitterFactor);
		}

		// 返回计算后的延迟时间
		return delay;
	}

	// 获取拦截器名称的方法
	@Override
	public String getName() {
		return "ToolRetry";
	}

	// 失败时的行为策略枚举
	public enum OnFailureBehavior {
		// 抛出异常
		RAISE,
		// 返回错误消息
		RETURN_MESSAGE
	}

	// 构建器类，用于创建ToolRetryInterceptor实例
	public static class Builder {
		// 默认最大重试次数为2次
		private int maxRetries = 2;
		// 工具名称集合
		private Set<String> toolNames;
		// 默认重试条件为所有异常都重试
		private Predicate<Exception> retryOn = e -> true; // Retry on all exceptions by default
		// 默认失败行为是返回消息
		private OnFailureBehavior onFailure = OnFailureBehavior.RETURN_MESSAGE;
		// 错误信息格式化函数
		private Function<Exception, String> errorFormatter;
		// 默认退避因子为2.0
		private double backoffFactor = 2.0;
		// 默认初始延迟时间为1000毫秒
		private long initialDelayMs = 1000;
		// 默认最大延迟时间为60000毫秒
		private long maxDelayMs = 60000;
		// 默认启用抖动机制
		private boolean jitter = true;

		// 设置最大重试次数
		public Builder maxRetries(int maxRetries) {
			// 检查重试次数是否合法
			if (maxRetries < 0) {
				throw new IllegalArgumentException("maxRetries must be >= 0");
			}
			// 设置最大重试次数
			this.maxRetries = maxRetries;
			// 返回当前Builder实例以支持链式调用
			return this;
		}

		// 设置工具名称集合
		public Builder toolNames(Set<String> toolNames) {
			this.toolNames = toolNames;
			return this;
		}

		// 添加单个工具名称
		public Builder toolName(String toolName) {
			// 如果工具名称集合为空，则创建新的HashSet
			if (this.toolNames == null) {
				this.toolNames = new HashSet<>();
			}
			// 将工具名称添加到集合中
			this.toolNames.add(toolName);
			return this;
		}

		// 设置重试的异常类型（可变参数版本）
		@SafeVarargs
		public final Builder retryOn(Class<? extends Exception>... exceptionTypes) {
			// 创建异常类型集合
			Set<Class<? extends Exception>> types = new HashSet<>(Arrays.asList(exceptionTypes));
			// 设置重试条件谓词
			this.retryOn = e -> {
				// 遍历异常类型集合，检查异常是否匹配
				for (Class<? extends Exception> type : types) {
					if (type.isInstance(e)) {
						return true;
					}
				}
				return false;
			};
			return this;
		}

		// 设置重试条件谓词
		public Builder retryOn(Predicate<Exception> predicate) {
			this.retryOn = predicate;
			return this;
		}

		// 设置失败时的行为策略
		public Builder onFailure(OnFailureBehavior behavior) {
			this.onFailure = behavior;
			return this;
		}

		// 设置错误信息格式化函数
		public Builder errorFormatter(Function<Exception, String> formatter) {
			this.errorFormatter = formatter;
			// 设置失败行为为返回消息
			this.onFailure = OnFailureBehavior.RETURN_MESSAGE;
			return this;
		}

		// 设置退避因子
		public Builder backoffFactor(double backoffFactor) {
			this.backoffFactor = backoffFactor;
			return this;
		}

		// 设置初始延迟时间
		public Builder initialDelay(long initialDelayMs) {
			this.initialDelayMs = initialDelayMs;
			return this;
		}

		// 设置最大延迟时间
		public Builder maxDelay(long maxDelayMs) {
			this.maxDelayMs = maxDelayMs;
			return this;
		}

		// 设置是否启用抖动机制
		public Builder jitter(boolean jitter) {
			this.jitter = jitter;
			return this;
		}

		// 构建ToolRetryInterceptor实例
		public ToolRetryInterceptor build() {
			return new ToolRetryInterceptor(this);
		}
	}
}

