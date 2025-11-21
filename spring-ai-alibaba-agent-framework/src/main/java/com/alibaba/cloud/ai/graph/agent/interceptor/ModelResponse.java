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

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;

import reactor.core.publisher.Flux;

/**
 * Response object for model calls.
 * Contains the model's response message.
 */
public class ModelResponse {

	// 存储聊天响应的ChatResponse对象
	private ChatResponse chatResponse;

	// 存储消息内容的对象，可以是AssistantMessage或Flux<ChatResponse>
	private final Object message;

	// 构造函数，接受一个消息对象进行初始化
	public ModelResponse(Object message) {
		// 初始化message字段
		this.message = message;
	}

	// 构造函数，接受消息对象和ChatResponse对象进行初始化
	public ModelResponse(Object message, ChatResponse chatResponse) {
		// 初始化message字段
		this.message = message;
		// 初始化chatResponse字段
		this.chatResponse = chatResponse;
	}

	// 静态工厂方法，根据AssistantMessage创建ModelResponse实例
	public static ModelResponse of(AssistantMessage message) {
		// 创建并返回新的ModelResponse实例
		return new ModelResponse(message);
	}

	// 静态工厂方法，根据AssistantMessage和ChatResponse创建ModelResponse实例
	public static ModelResponse of(AssistantMessage message, ChatResponse chatResponse) {
		// 创建并返回新的ModelResponse实例
		return new ModelResponse(message, chatResponse);
	}

	// 静态工厂方法，根据Flux<ChatResponse>创建ModelResponse实例
	public static ModelResponse of(Flux<ChatResponse> flux) {
		// 创建并返回新的ModelResponse实例
		return new ModelResponse(flux);
	}

	// 获取message字段的getter方法
	public Object getMessage() {
		// 返回message对象
		return message;
	}

	// 获取chatResponse字段的getter方法
	public ChatResponse getChatResponse() {
		// 返回chatResponse对象
		return chatResponse;
	}
}

