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

import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.node.AgentLlmNode;
import com.alibaba.cloud.ai.graph.agent.node.AgentToolNode;
import io.micrometer.observation.ObservationRegistry;
import org.apache.commons.collections4.CollectionUtils;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.FormatProvider;
import org.springframework.ai.tool.ToolCallback;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class DefaultBuilder extends Builder {

	@Override
	public ReactAgent build() {

		// 验证代理名称不为空
		if (!StringUtils.hasText(this.name)) {
			throw new IllegalArgumentException("Agent name must not be empty");
		}

		// 验证必须提供chatClient或model中的一个
		if (chatClient == null && model == null) {
			throw new IllegalArgumentException("Either chatClient or model must be provided");
		}

		// 如果未提供chatClient，则基于model构建一个新的
		if (chatClient == null) {

			// 创建ChatClient构建器，设置模型和观测相关配置
			ChatClient.Builder clientBuilder = ChatClient.builder(model, this.observationRegistry == null ? ObservationRegistry.NOOP : this.observationRegistry,
					this.customObservationConvention);

			// 如果提供了聊天选项，则设置默认选项
			if (chatOptions != null) {
				clientBuilder.defaultOptions(chatOptions);
			}

			// 构建chatClient实例
			chatClient = clientBuilder.build();
		}

		// 创建AgentLlmNode构建器，设置代理名称和聊天客户端
		AgentLlmNode.Builder llmNodeBuilder = AgentLlmNode.builder().agentName(this.name).chatClient(chatClient);

		// 如果输出键不为空，则设置输出键
		if (outputKey != null && !outputKey.isEmpty()) {
			llmNodeBuilder.outputKey(outputKey);
		}

		// 如果系统提示不为空，则设置系统提示
		if (systemPrompt != null) {
			llmNodeBuilder.systemPrompt(systemPrompt);
		}

		// 初始化输出模式变量
		String outputSchema = null;
		// 如果提供了自定义输出模式，则使用该模式
		if (StringUtils.hasLength(this.outputSchema) ) {
			outputSchema = this.outputSchema;
		// 否则如果提供了输出类型，则基于该类型生成输出模式
		} else if (this.outputType != null) {
			FormatProvider formatProvider = new BeanOutputConverter<>(this.outputType);
			outputSchema = formatProvider.getFormat();
		}

		// 如果输出模式不为空，则设置到LLM节点构建器中
		if (StringUtils.hasLength(outputSchema)) {
			llmNodeBuilder.outputSchema(outputSchema);
		}

		// 按类型分离统一拦截器
		if (CollectionUtils.isNotEmpty(interceptors)) {
			// 初始化模型拦截器列表
			modelInterceptors = new ArrayList<>();
			// 初始化工具拦截器列表
			toolInterceptors = new ArrayList<>();

			// 遍历所有拦截器，按类型分类
			for (Interceptor interceptor : interceptors) {
				// 如果是模型拦截器，则添加到模型拦截器列表
				if (interceptor instanceof ModelInterceptor) {
					modelInterceptors.add((ModelInterceptor) interceptor);
				}
				// 如果是工具拦截器，则添加到工具拦截器列表
				if (interceptor instanceof ToolInterceptor) {
					toolInterceptors.add((ToolInterceptor) interceptor);
				}
			}
		}

		// 从拦截器中收集工具
		// - regularTools: 用户提供的工具
		// - interceptorTools: 来自拦截器的工具
		// 创建常规工具列表
		List<ToolCallback> regularTools = new ArrayList<>();

		// 从用户提供的工具中提取常规工具
		if (CollectionUtils.isNotEmpty(tools)) {
			regularTools.addAll(tools);
		}

		// 提取拦截器工具
		List<ToolCallback> interceptorTools = new ArrayList<>();
		// 如果模型拦截器列表不为空，则从中提取工具
		if (CollectionUtils.isNotEmpty(modelInterceptors)) {
			interceptorTools = modelInterceptors.stream()
				.flatMap(interceptor -> interceptor.getTools().stream())
				.toList();
		}

		// 合并所有工具：拦截器工具 + 常规工具
		List<ToolCallback> allTools = new ArrayList<>();
		allTools.addAll(interceptorTools);
		allTools.addAll(regularTools);

		// 将合并后的工具设置到LLM节点中
		if (CollectionUtils.isNotEmpty(allTools)) {
			llmNodeBuilder.toolCallbacks(allTools);
		}

		// 如果启用日志记录，则启用推理日志
		if (enableLogging) {
			llmNodeBuilder.enableReasoningLog(true);
		}

		// 构建AgentLlmNode实例
		AgentLlmNode llmNode = llmNodeBuilder.build();

		// 使用所有可用工具设置工具节点
		AgentToolNode toolNode;
		// 创建AgentToolNode构建器，设置代理名称
		AgentToolNode.Builder toolBuilder = AgentToolNode.builder().agentName(this.name);

		// 如果提供了工具回调解析器，则设置到构建器中
		if (resolver != null) {
			toolBuilder.toolCallbackResolver(resolver);
		}
		// 如果工具列表不为空，则设置到构建器中
		if (CollectionUtils.isNotEmpty(allTools)) {
			toolBuilder.toolCallbacks(allTools);
		}

		// 如果启用日志记录，则启用执行日志
		if (enableLogging) {
			toolBuilder.enableActingLog(true);
		}

		// 构建工具节点
		toolNode = toolBuilder.build();

		// 创建并返回ReactAgent实例
		return new ReactAgent(llmNode, toolNode, buildConfig(), this);
	}

}

