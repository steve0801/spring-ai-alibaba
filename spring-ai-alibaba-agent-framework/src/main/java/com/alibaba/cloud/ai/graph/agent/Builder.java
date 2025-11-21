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
package com.alibaba.cloud.ai.graph.agent;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

public abstract class Builder {

	protected String name;

	// 代理的描述信息，用于说明代理的功能和用途
	protected String description;

	// 代理的指令信息，定义代理的行为准则和操作指南
	protected String instruction;

	// 系统提示信息，用于设置代理的系统级行为和上下文
	protected String systemPrompt;

	// 聊天模型实例，用于处理代理的聊天和对话功能
	protected ChatModel model;

	// 聊天选项配置，定义聊天过程中的各种参数和设置
	protected ChatOptions chatOptions;

	// 聊天客户端实例，用于与聊天模型进行交互（已弃用）
	protected ChatClient chatClient;

	// 工具回调列表，包含代理可以调用的各种工具
	protected List<ToolCallback> tools;

	// 工具回调解析器，用于解析和处理工具回调
	protected ToolCallbackResolver resolver;

	// 是否释放线程的标志，控制代理执行完毕后是否释放线程资源
	protected boolean releaseThread;

	// 基础检查点保存器，用于保存代理执行过程中的检查点状态
	protected BaseCheckpointSaver saver;

	// 钩子列表，包含在代理执行过程中触发的各类钩子
	protected List<? extends Hook> hooks;
	// 拦截器列表，用于拦截和处理代理执行过程中的请求
	protected List<? extends Interceptor> interceptors;
	// 模型拦截器列表，专门用于拦截和处理模型相关的操作
	protected List<ModelInterceptor> modelInterceptors;
	// 工具拦截器列表，专门用于拦截和处理工具调用相关的操作
	protected List<ToolInterceptor> toolInterceptors;

	// 是否包含内容的标志，默认为true，控制代理输出是否包含详细内容
	protected boolean includeContents = true;
	// 是否返回推理内容的标志，控制代理是否返回推理过程中的中间内容
	protected boolean returnReasoningContents;

	// 输出键，用于在状态中存储代理的输出结果
	protected String outputKey;

	// 输出键策略，定义如何处理输出键的更新和管理
	protected KeyStrategy outputKeyStrategy;

	// 输入模式字符串，描述代理期望的输入格式
	protected String inputSchema;
	// 输入类型，表示代理期望的输入数据类型
	protected Type inputType;

	// 输出模式字符串，描述代理返回的输出格式
	protected String outputSchema;
	// 输出类型，表示代理返回的输出数据类型
	protected Class<?> outputType;

	// 观测注册表，用于注册和管理观测点
	protected ObservationRegistry observationRegistry;

	// 自定义观测约定，允许自定义聊天客户端的观测行为
	protected ChatClientObservationConvention customObservationConvention;

	// 是否启用日志记录的标志，控制代理是否记录执行日志
	protected boolean enableLogging;

	public Builder name(String name) {
		this.name = name;
		return this;
	}

	@Deprecated
	public Builder chatClient(ChatClient chatClient) {
		this.chatClient = chatClient;
		return this;
	}

	public Builder model(ChatModel model) {
		this.model = model;
		return this;
	}

	public Builder chatOptions(ChatOptions chatOptions) {
		this.chatOptions = chatOptions;
		return this;
	}

	public Builder tools(List<ToolCallback> tools) {
		this.tools = tools;
		return this;
	}

	public Builder tools(ToolCallback... tools) {
		this.tools = Arrays.asList(tools);
		return this;
	}

	public Builder resolver(ToolCallbackResolver resolver) {
		this.resolver = resolver;
		return this;
	}

	public Builder releaseThread(boolean releaseThread) {
		this.releaseThread = releaseThread;
		return this;
	}

	public Builder saver(BaseCheckpointSaver saver) {
		this.saver = saver;
		return this;
	}

	public Builder description(String description) {
		this.description = description;
		return this;
	}

	public Builder instruction(String instruction) {
		this.instruction = instruction;
		return this;
	}

	public Builder systemPrompt(String systemPrompt) {
		this.systemPrompt = systemPrompt;
		return this;
	}

	public Builder outputKey(String outputKey) {
		this.outputKey = outputKey;
		return this;
	}

	public Builder outputKeyStrategy(KeyStrategy outputKeyStrategy) {
		this.outputKeyStrategy = outputKeyStrategy;
		return this;
	}

	public Builder inputSchema(String inputSchema) {
		this.inputSchema = inputSchema;
		return this;
	}

	public Builder inputType(Type inputType) {
		this.inputType = inputType;
		return this;
	}

	public Builder outputSchema(String outputSchema) {
		this.outputSchema = outputSchema;
		return this;
	}

	public Builder outputType(Class<?> outputType) {
		this.outputType = outputType;
		return this;
	}

	public Builder includeContents(boolean includeContents) {
		this.includeContents = includeContents;
		return this;
	}

	public Builder returnReasoningContents(boolean returnReasoningContents) {
		this.returnReasoningContents = returnReasoningContents;
		return this;
	}

	public Builder hooks(List<? extends Hook> hooks) {
		this.hooks = hooks;
		return this;
	}

	public Builder hooks(Hook... hooks) {
		this.hooks = Arrays.asList(hooks);
		return this;
	}

	public Builder interceptors(List<? extends Interceptor> interceptors) {
		this.interceptors = interceptors;
		return this;
	}

	public Builder interceptors(Interceptor... interceptors) {
		this.interceptors = Arrays.asList(interceptors);
		return this;
	}

	public Builder observationRegistry(ObservationRegistry observationRegistry) {
		this.observationRegistry = observationRegistry;
		return this;
	}

	public Builder customObservationConvention(ChatClientObservationConvention customObservationConvention) {
		this.customObservationConvention = customObservationConvention;
		return this;
	}

	public Builder enableLogging(boolean enableLogging) {
		this.enableLogging = enableLogging;
		return this;
	}

	protected CompileConfig buildConfig() {
		// 创建SaverConfig构建器，并注册当前的saver实例
		SaverConfig saverConfig = SaverConfig.builder()
				.register(saver)
				.build();
		// 创建CompileConfig构建器，配置相关参数
		return CompileConfig.builder()
				// 设置保存器配置
				.saverConfig(saverConfig)
				// 设置递归限制为最大整数值
				.recursionLimit(Integer.MAX_VALUE)
				// 设置是否释放线程
				.releaseThread(releaseThread)
				.build();
	}

	public abstract ReactAgent build();

}
