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
package com.alibaba.cloud.ai.graph.agent.interceptor.modelfallback;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automatic fallback to alternative models on errors.
 *
 * Retries failed model calls with alternative models in sequence until
 * success or all models exhausted.
 *
 * Example:
 * ModelFallbackInterceptor interceptor = ModelFallbackInterceptor.builder()
 *     .addFallbackModel(gpt4oMiniModel)
 *     .addFallbackModel(claude35SonnetModel)
 *     .build();
 */
public class ModelFallbackInterceptor extends ModelInterceptor {

	// 日志记录器，用于记录拦截器的执行日志
	private static final Logger log = LoggerFactory.getLogger(ModelFallbackInterceptor.class);

	// 存储备用模型列表
	private final List<ChatModel> fallbackModels;

	// 私有构造函数，通过Builder模式创建ModelFallbackInterceptor实例
	private ModelFallbackInterceptor(Builder builder) {
		// 将Builder中的备用模型列表复制到实例变量中
		this.fallbackModels = new ArrayList<>(builder.fallbackModels);
	}

	// 静态方法，返回Builder实例用于创建ModelFallbackInterceptor
	public static Builder builder() {
		return new Builder();
	}

	// 拦截模型调用的核心方法，实现模型降级逻辑
	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		// 保存最后一次异常，用于在所有模型都失败时抛出
		Exception lastException = null;

		// 首先尝试主模型
		try {
			// 调用主模型处理请求
			ModelResponse modelResponse = handler.call(request);
			// 获取响应消息
			Message message = (Message) modelResponse.getMessage();

			// 检查响应是否包含错误指示器
			if (message.getText() != null && message.getText().contains("Exception:")) {
				// 如果包含错误信息，则抛出运行时异常
				throw new RuntimeException(message.getText());
			}

			// 返回成功的响应
			return modelResponse;
		}
		// 捕获主模型调用异常
		catch (Exception e) {
			// 记录警告日志，主模型调用失败
			log.warn("Primary model failed: {}", e.getMessage());
			// 保存异常信息
			lastException = e;
		}

		// 按顺序尝试备用模型
		for (int i = 0; i < fallbackModels.size(); i++) {
			// 获取当前备用模型
			ChatModel fallbackModel = fallbackModels.get(i);
			try {
				// 记录信息日志，正在尝试备用模型
				log.info("Trying fallback model {} of {}", i + 1, fallbackModels.size());

				// 直接调用备用模型
				Prompt prompt = new Prompt(request.getMessages(), request.getOptions());
				// 执行模型调用
				var response = fallbackModel.call(prompt);

				// 返回备用模型的成功响应
				return ModelResponse.of(response.getResult().getOutput());
			}
			// 捕获备用模型调用异常
			catch (Exception e) {
				// 记录警告日志，备用模型调用失败
				log.warn("Fallback model {} failed: {}", i + 1, e.getMessage());
				// 保存异常信息
				lastException = e;
			}
		}

		// 所有模型都调用失败，抛出运行时异常
		throw new RuntimeException("All models failed after " + (fallbackModels.size() + 1) + " attempts", lastException);
	}

	// 获取拦截器名称的方法
	@Override
	public String getName() {
		return "ModelFallback";
	}

	// 构建器类，用于创建ModelFallbackInterceptor实例
	public static class Builder {
		// 存储备用模型列表
		private final List<ChatModel> fallbackModels = new ArrayList<>();

		// 添加单个备用模型
		public Builder addFallbackModel(ChatModel model) {
			// 将模型添加到备用模型列表
			this.fallbackModels.add(model);
			// 返回当前Builder实例以支持链式调用
			return this;
		}

		// 设置备用模型列表
		public Builder fallbackModels(List<ChatModel> models) {
			// 将模型列表添加到备用模型列表
			this.fallbackModels.addAll(models);
			// 返回当前Builder实例以支持链式调用
			return this;
		}

		// 构建ModelFallbackInterceptor实例
		public ModelFallbackInterceptor build() {
			// 检查是否至少指定了一个备用模型
			if (fallbackModels.isEmpty()) {
				// 如果没有指定备用模型，则抛出非法参数异常
				throw new IllegalArgumentException("At least one fallback model must be specified");
			}
			// 创建并返回ModelFallbackInterceptor实例
			return new ModelFallbackInterceptor(this);
		}
	}
}

