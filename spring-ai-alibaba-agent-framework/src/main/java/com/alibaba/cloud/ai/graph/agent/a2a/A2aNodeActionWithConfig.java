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
package com.alibaba.cloud.ai.graph.agent.a2a;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.alibaba.cloud.ai.graph.async.AsyncGenerator;
import com.alibaba.cloud.ai.graph.async.AsyncGeneratorQueue;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;

import org.springframework.ai.chat.prompt.PromptTemplate;

import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import static java.lang.String.format;

public class A2aNodeActionWithConfig implements NodeActionWithConfig {

	private final String agentName;

	private final AgentCardWrapper agentCard;

	private final boolean includeContents;

	private final String outputKeyToParent;

	private final boolean streaming;

	private final String instruction;

	private boolean shareState;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private CompileConfig parentCompileConfig;


	public A2aNodeActionWithConfig(AgentCardWrapper agentCard, String agentName, boolean includeContents, String outputKeyToParent, String instruction, boolean streaming) {
		// 初始化A2A节点动作的基本属性
		this.agentName = agentName;
		// 保存AgentCard包装器，包含远程Agent的信息
		this.agentCard = agentCard;
		// 是否包含内容标志
		this.includeContents = includeContents;
		// 输出键名，用于将结果存储到父级状态中
		this.outputKeyToParent = outputKeyToParent;
		// 是否启用流式传输
		this.streaming = streaming;
		// 指令文本，用于指导Agent的行为
		this.instruction = instruction;
		// 默认不共享状态
		this.shareState = false;
	}

	// 带编译配置的构造函数
	public A2aNodeActionWithConfig(AgentCardWrapper agentCard, String agentName, boolean includeContents, String outputKeyToParent, String instruction, boolean streaming, boolean shareState, CompileConfig compileConfig) {
		// 调用基础构造函数初始化基本属性
		this(agentCard, agentName, includeContents, outputKeyToParent, instruction, streaming);
		// 保存父级编译配置
		this.parentCompileConfig = compileConfig;
		// 设置是否共享状态
		this.shareState = shareState;
	}

	@Override
	// 应用节点动作的核心方法，接收状态和配置参数
	public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
		// 获取子图的运行配置
		RunnableConfig subGraphRunnableConfig = getSubGraphRunnableConfig(config);
		// 判断是否启用流式传输
		if (streaming) {
			// 创建流式生成器
			AsyncGenerator<NodeOutput> generator = createStreamingGenerator(state, subGraphRunnableConfig);
			// 将异步生成器转换为Flux流
			Flux<GraphResponse<NodeOutput>> flux = toFlux(generator);
			// 返回包含流式输出的结果映射
			return Map.of(StringUtils.hasLength(this.outputKeyToParent) ? this.outputKeyToParent : "messages", flux);
		}
		// 非流式传输处理
		else {
			// 构建发送消息的请求载荷
			String requestPayload = buildSendMessageRequest(state, subGraphRunnableConfig);
			// 向服务器发送消息并获取响应
			String resultText = sendMessageToServer(this.agentCard, requestPayload);
			// 自动检测并解析响应
			Map<String, Object> resultMap = autoDetectAndParseResponse(resultText);
			// 提取结果部分
			Map<String, Object> result = (Map<String, Object>) resultMap.get("result");
			// 从结果中提取响应文本
			String responseText = extractResponseText(result);
			// 返回包含响应文本的结果映射
			return Map.of(this.outputKeyToParent, responseText);
		}
	}

	// 获取子图运行配置的方法
	private RunnableConfig getSubGraphRunnableConfig(RunnableConfig config) {
		// 如果共享状态，则直接返回原配置
		if (shareState) {
			return config;
		}
		// 构建新的运行配置，使用独立的线程ID
		return RunnableConfig.builder(config)
				// 设置线程ID，添加子图标识前缀
				.threadId(config.threadId()
						.map(threadId -> format("%s_%s", threadId, subGraphId()))
						.orElseGet(this::subGraphId))
				// 清空下一个节点设置
				.nextNode(null)
				// 清空检查点ID
				.checkPointId(null)
				// 构建配置对象
				.build();
	}

	// 生成子图ID的方法
	public String subGraphId() {
		// 格式化子图ID，包含Agent名称
		return format("subgraph_%s", agentCard.name());
	}

	/**
	 * Converts this AsyncGenerator to a Project Reactor Flux. This method provides
	 * forward compatibility for converting AsyncGenerator to reactive streams.
	 * @return a Flux that emits the elements from this AsyncGenerator
	 */
	// 将异步生成器转换为Flux流的方法
	private <E> Flux<GraphResponse<E>> toFlux(AsyncGenerator<E> generator) {
		// 创建Flux流
		return Flux.create(sink -> {
			// 在弹性调度器上安排任务执行生成器抽取
			Disposable disposable = Schedulers.boundedElastic().schedule(() -> drainGenerator(generator, sink));
			// 设置取消时的清理操作
			sink.onCancel(disposable::dispose);
			// 设置销毁时的清理操作
			sink.onDispose(disposable::dispose);
		});
	}

	// 抽取生成器数据并推送至Flux Sink的方法
	private <E> void drainGenerator(AsyncGenerator<E> generator, FluxSink<GraphResponse<E>> sink) {
		// 如果Sink已被取消，则直接返回
		if (sink.isCancelled()) {
			return;
		}

		// 获取生成器的下一个数据项
		final AsyncGenerator.Data<E> data;
		try {
			data = generator.next();
		}
		// 捕获异常并推送至Sink错误通道
		catch (Exception ex) {
			sink.error(ex);
			return;
		}

		// 如果数据已完成
		if (data.isDone()) {
			// 如果有结果值，则推送完成信号
			data.resultValue().ifPresent(result -> {
				if (!sink.isCancelled()) {
					sink.next(GraphResponse.done(result));
				}
			});
			// 如果未被取消，则完成Sink
			if (!sink.isCancelled()) {
				sink.complete();
			}
			return;
		}

		// 获取数据项
		var future = data.getData();
		// 如果数据为空，则推送错误
		if (future == null) {
			sink.error(new IllegalStateException("AsyncGenerator data is null without completion signal"));
			return;
		}

		// 当数据完成时的回调处理
		future.whenComplete((value, throwable) -> {
			// 如果Sink已被取消，则直接返回
			if (sink.isCancelled()) {
				return;
			}

			// 如果有异常，则处理异常并推送至错误通道
			if (throwable != null) {
				Throwable actual = unwrapCompletionException(throwable);
				sink.error(actual);
				return;
			}

			// 如果未被取消，则推送数据值
			if (!sink.isCancelled()) {
				sink.next(GraphResponse.of(value));
			}

			// 递归继续抽取生成器数据
			drainGenerator(generator, sink);
		});
	}

	// 解包CompletionException的方法
	private Throwable unwrapCompletionException(Throwable throwable) {
		// 如果是CompletionException且有原因，则返回原因
		if (throwable instanceof java.util.concurrent.CompletionException completionException
				&& completionException.getCause() != null) {
			return completionException.getCause();
		}
		// 否则返回原异常
		return throwable;
	}

	/**
	 * Create a streaming generator.
	 */
	// 创建流式生成器的方法
	private AsyncGenerator<NodeOutput> createStreamingGenerator(OverAllState state, RunnableConfig config) throws Exception {
		// 构建发送流式消息的请求载荷
		final String requestPayload = buildSendStreamingMessageRequest(state, config);
		// 创建阻塞队列用于存储生成的数据
		final BlockingQueue<AsyncGenerator.Data<NodeOutput>> queue = new LinkedBlockingQueue<>(1000);
		// 确定输出键名
		final String outputKey = StringUtils.hasLength(this.outputKeyToParent) ? this.outputKeyToParent : "messages";
		// 创建字符串构建器用于累积输出内容
		final StringBuilder accumulated = new StringBuilder();

		// 创建异步生成器队列
		return AsyncGeneratorQueue.of(queue, q -> {
			// 解析Agent的基础URL
			String baseUrl = resolveAgentBaseUrl(this.agentCard);
			// 如果URL为空或空白，则添加错误输出并返回
			if (baseUrl == null || baseUrl.isBlank()) {
				StreamingOutput errorOutput = new StreamingOutput("Error: AgentCard.url is empty", "a2aNode", agentName, state);
				queue.add(AsyncGenerator.Data.of(errorOutput));
				return;
			}

			// 创建HTTP客户端
			try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
				// 创建POST请求
				HttpPost post = new HttpPost(baseUrl);
				// 设置内容类型头
				post.setHeader("Content-Type", "application/json");
				// 设置接受头为SSE流格式
				post.setHeader("Accept", "text/event-stream");
				// 设置请求实体
				post.setEntity(new StringEntity(requestPayload, ContentType.APPLICATION_JSON));

				// 执行HTTP请求
				try (CloseableHttpResponse response = httpClient.execute(post)) {
					// 获取响应状态码
					int statusCode = response.getStatusLine().getStatusCode();
					// 如果状态码不是200，则添加错误输出并返回
					if (statusCode != 200) {
						StreamingOutput errorOutput = new StreamingOutput("HTTP request failed, status: " + statusCode,
								"a2aNode", agentName, state);
						queue.add(AsyncGenerator.Data.of(errorOutput));
						return;
					}

					// 获取响应实体
					HttpEntity entity = response.getEntity();
					// 如果实体为空，则添加错误输出并返回
					if (entity == null) {
						StreamingOutput errorOutput = new StreamingOutput("Empty HTTP entity", "a2aNode", agentName, state);
						queue.add(AsyncGenerator.Data.of(errorOutput));
						return;
					}

					// 获取内容类型
					String contentType = entity.getContentType() != null ? entity.getContentType().getValue() : "";
					// 判断是否为事件流格式
					boolean isEventStream = contentType.contains("text/event-stream");

					// 如果是事件流格式
					if (isEventStream) {
						// 创建缓冲读取器读取响应内容
						try (BufferedReader reader = new BufferedReader(
								new InputStreamReader(entity.getContent(), StandardCharsets.UTF_8))) {
							String line;
							// 逐行读取响应内容
							while ((line = reader.readLine()) != null) {
								// 去除行首尾空白字符
								String trimmed = line.trim();
								// 如果不是以"data:"开头，则跳过
								if (!trimmed.startsWith("data:")) {
									continue;
								}
								// 提取JSON内容部分
								String jsonContent = trimmed.substring(5).trim();
								// 如果是结束标记，则跳出循环
								if ("[DONE]".equals(jsonContent)) {
									break;
								}
								// 尝试解析JSON内容
								try {
									// 解析JSON内容为Map
									Map<String, Object> parsed = JSON.parseObject(jsonContent,
											new TypeReference<Map<String, Object>>() {
											});
									// 提取结果部分
									Map<String, Object> result = (Map<String, Object>) parsed.get("result");
									// 如果结果不为空
									if (result != null) {
										// 从结果中提取文本内容
										String text = extractResponseText(result);
										// 如果文本不为空
										if (text != null && !text.isEmpty()) {
											// 累积文本内容
											accumulated.append(text);
											// 添加流式输出数据到队列
											queue.add(AsyncGenerator.Data
												.of(new StreamingOutput(text, "a2aNode", agentName, state)));
										}
									}
								}
								// 捕获解析异常并忽略
								catch (Exception ignore) {
								}
							}
						}
					}
					// 非SSE格式处理
					else {
						// 读取完整响应体
						String body = EntityUtils.toString(entity, "UTF-8");
						// 尝试解析响应体
						try {
							// 解析响应体为Map
							Map<String, Object> resultMap = JSON.parseObject(body,
									new TypeReference<Map<String, Object>>() {
									});
							// 提取结果部分
							Map<String, Object> result = (Map<String, Object>) resultMap.get("result");
							// 从结果中提取文本内容
							String text = extractResponseText(result);
							// 如果文本不为空
							if (text != null && !text.isEmpty()) {
								// 累积文本内容
								accumulated.append(text);
								// 添加流式输出数据到队列
								queue.add(AsyncGenerator.Data.of(new StreamingOutput(text, "a2aNode", agentName, state)));
							}
						}
						// 捕获异常并添加错误输出
						catch (Exception ex) {
							queue.add(AsyncGenerator.Data
								.of(new StreamingOutput("Error: " + ex.getMessage(), "a2aNode", agentName, state)));
						}
					}
				}
			}
			// 捕获异常并添加错误输出
			catch (Exception e) {
				StreamingOutput errorOutput = new StreamingOutput("Error: " + e.getMessage(), "a2aNode", agentName, state);
				queue.add(AsyncGenerator.Data.of(errorOutput));
			}
			// 最终添加完成信号
			finally {
				queue.add(AsyncGenerator.Data.done(Map.of(outputKey, accumulated.toString())));
			}
		});
	}

	/**
	 * Check whether the given text looks like an SSE response.
	 */
	private boolean isSSEResponse(String responseText) {
		return responseText.contains("data: ");
	}

	/**
	 * Create a streaming generator for SSE-formatted text.
	 */
	// 创建SSE流式生成器的方法
	private AsyncGenerator<NodeOutput> createSseStreamingGenerator(String sseResponseText, OverAllState state) {
		// 使用新的实时流式方法
		return createRealTimeSseStreamingGenerator(sseResponseText, state);
	}

	/**
	 * Create a real-time SSE streaming generator (recommended). This method starts
	 * processing SSE data immediately and pushes chunks as they arrive.
	 */
	// 创建实时SSE流式生成器（推荐方式），该方法立即开始处理SSE数据，并在数据到达时推送数据块
	private AsyncGenerator<NodeOutput> createRealTimeSseStreamingGenerator(String sseResponseText, OverAllState state) {
		// 创建阻塞队列，容量为1000，用于存储异步生成的数据
		BlockingQueue<AsyncGenerator.Data<NodeOutput>> queue = new LinkedBlockingQueue<>(1000);
		// 确定输出键名，如果outputKeyToParent不为空则使用它，否则使用默认的"messages"
		final String outputKey = StringUtils.hasLength(this.outputKeyToParent) ? this.outputKeyToParent : "messages";
		// 创建字符串构建器，用于累积输出内容
		final StringBuilder accumulated = new StringBuilder();

		// 使用AsyncGeneratorQueue创建异步生成器，立即开始异步处理，不等待整个内容
		return AsyncGeneratorQueue.of(queue, executor -> {
			try {
				// 逐行处理SSE响应以实现真正的流式处理
				String[] lines = sseResponseText.split("\n");

				// 遍历每一行
				for (String line : lines) {
					// 去除行首尾空格
					line = line.trim();
					// 如果行以"data: "开头
					if (line.startsWith("data: ")) {
						try {
							// 提取JSON内容，移除"data: "前缀
							String jsonContent = line.substring(6); // remove "data: "
							// prefix

							// 结束标记
							if ("[DONE]".equals(jsonContent)) {
								break;
							}

							// 解析JSON内容为Map对象
							Map<String, Object> parsed = JSON.parseObject(jsonContent,
									new TypeReference<Map<String, Object>>() {
									});
							// 从解析结果中获取result部分
							Map<String, Object> result = (Map<String, Object>) parsed.get("result");

							// 如果result不为空
							if (result != null) {
								// 从结果创建流式输出
								StreamingOutput streamingOutput = createStreamingOutputFromResult(result, state);
								// 如果流式输出不为空
								if (streamingOutput != null) {
									// 将流式输出添加到队列中
									queue.add(AsyncGenerator.Data.of(streamingOutput));
								}
							}
						}
						// 捕获异常，忽略解析错误并继续处理
						catch (Exception e) {
							// Ignore parse errors and continue
							continue;
						}
					}
				}

				// 使用最终结果值发送完成信号
				queue.add(AsyncGenerator.Data.done(Map.of(outputKey, accumulated.toString())));

			}
			// 捕获异常
			catch (Exception e) {
				// 出现错误时，发送错误消息并发送完成信号
				StreamingOutput errorOutput = new StreamingOutput("Error: " + e.getMessage(), "a2aNode", agentName, state);
				queue.add(AsyncGenerator.Data.of(errorOutput));
				queue.add(AsyncGenerator.Data.done(Map.of(outputKey, accumulated.toString())));
			}
		});
	}

	/**
	 * Create a single-output streaming generator (for non-SSE responses).
	 */
	// 创建单一输出流式生成器（用于非SSE响应）
	private AsyncGenerator<NodeOutput> createSingleStreamingGenerator(String responseText, OverAllState state) {
		// 创建阻塞队列，容量为10，用于存储异步生成的数据
		BlockingQueue<AsyncGenerator.Data<NodeOutput>> queue = new LinkedBlockingQueue<>(10);
		// 确定输出键名，如果outputKeyToParent不为空则使用它，否则使用默认的"messages"
		final String outputKey = StringUtils.hasLength(this.outputKeyToParent) ? this.outputKeyToParent : "messages";
		// 创建字符串构建器，用于累积输出内容
		final StringBuilder accumulated = new StringBuilder();

		try {
			// 解析响应文本为Map对象
			Map<String, Object> resultMap = JSON.parseObject(responseText, new TypeReference<Map<String, Object>>() {
			});
			// 从解析结果中获取result部分
			Map<String, Object> result = (Map<String, Object>) resultMap.get("result");
			// 从结果中提取响应文本
			String responseText2 = extractResponseText(result);

			// 如果响应文本不为空
			if (responseText2 != null && !responseText2.isEmpty()) {
				// 累积响应文本
				accumulated.append(responseText2);
				// 创建流式输出
				StreamingOutput streamingOutput = new StreamingOutput(responseText2, "a2aNode", agentName, state);
				// 将流式输出添加到队列中
				queue.add(AsyncGenerator.Data.of(streamingOutput));
			}
		}
		// 捕获解析失败异常
		catch (Exception e) {
			// 解析失败时，发送错误消息
			StreamingOutput errorOutput = new StreamingOutput("Error: " + e.getMessage(), "a2aNode", agentName, state);
			queue.add(AsyncGenerator.Data.of(errorOutput));
		}

		// 使用最终结果值发送完成信号
		queue.add(AsyncGenerator.Data.done(Map.of(outputKey, accumulated.toString())));

		// 返回新的AsyncGeneratorQueue.Generator实例
		return new AsyncGeneratorQueue.Generator<>(queue);
	}

	/**
	 * Create a StreamingOutput from the parsed result map.
	 */
	// 从解析的结果Map创建StreamingOutput
	private StreamingOutput createStreamingOutputFromResult(Map<String, Object> result, OverAllState state) {
		// 从结果中提取文本
		String text = extractResponseText(result);
		// 如果文本不为空且不为空字符串
		if (text != null && !text.isEmpty()) {
			// 返回新的StreamingOutput实例
			return new StreamingOutput(text, "a2aNode", agentName, state);
		}
		// 否则返回null
		return null;
	}

//	/**
//	 * Get the streaming generator (similar to LlmNode.stream).
//	 */
//	public Flux<NodeOutput> stream(OverAllState state) throws Exception {
//		if (!this.streaming) {
//			throw new IllegalStateException("Streaming is not enabled for this A2aNode");
//		}
//		AsyncGenerator<NodeOutput> generator = createStreamingGenerator(state);
//		Flux<GraphResponse<NodeOutput>> graphResponseFlux = toFlux(generator);
//
//		// Convert Flux<GraphResponse<NodeOutput>> to Flux<NodeOutput>
//		return graphResponseFlux.filter(graphResponse -> !graphResponse.isDone()) // Filter out completion signals
//			.map(graphResponse -> {
//				try {
//					return graphResponse.getOutput().join();
//				}
//				catch (Exception e) {
//					throw new RuntimeException("Error extracting output from GraphResponse", e);
//				}
//			});
//	}

//	/**
//	 * Get the non-streaming result (similar to LlmNode.call).
//	 */
//	public String call(OverAllState state) throws Exception {
//		String requestPayload = buildSendMessageRequest(state, this.inputKeyFromParent);
//		String resultText = sendMessageToServer(this.agentCard, requestPayload);
//		Map<String, Object> resultMap = autoDetectAndParseResponse(resultText);
//		Map<String, Object> result = (Map<String, Object>) resultMap.get("result");
//		return extractResponseText(result);
//	}

	/**
	 * Auto-detect response format and parse accordingly.
	 * @param responseText The raw response text
	 * @return Parsed result map
	 */
	// 自动检测响应格式并相应地解析
	private Map<String, Object> autoDetectAndParseResponse(String responseText) {
		// 如果响应文本包含"data: "，则认为是流式响应
		if (responseText.contains("data: ")) {
			// 解析流式响应
			return parseStreamingResponse(responseText);
		}
		// 否则是标准JSON响应
		else {
			// Standard JSON response
			// 解析为JSON对象并返回
			return JSON.parseObject(responseText, new TypeReference<Map<String, Object>>() {
			});
		}
	}

	/**
	 * Parse streaming response in Server-Sent Events (SSE) format.
	 * @param responseText The raw SSE response text
	 * @return Parsed result map
	 */
	// 解析Server-Sent Events(SSE)格式的流式响应
	private Map<String, Object> parseStreamingResponse(String responseText) {
		// 按换行符分割响应文本
		String[] lines = responseText.split("\n");
		// 存储最后一个结果
		Map<String, Object> lastResult = null;
		// 遍历每一行
		for (String line : lines) {
			// 去除行首尾空格
			line = line.trim();
			// 如果行以"data: "开头
			if (line.startsWith("data: ")) {
				// 提取JSON内容，移除"data: "前缀
				String jsonContent = line.substring(6); // remove "data: " prefix
				try {
					// 解析JSON内容为Map对象
					Map<String, Object> parsed = JSON.parseObject(jsonContent,
							new TypeReference<Map<String, Object>>() {
							});
					// 从解析结果中获取result部分
					Map<String, Object> result = (Map<String, Object>) parsed.get("result");
					// 如果result不为空
					if (result != null) {
						// 如果result包含"artifact"键或lastResult为空
						if (result.containsKey("artifact") || lastResult == null) {
							// 更新lastResult
							lastResult = result;
						}
					}
				}
				// 捕获异常，继续处理下一行
				catch (Exception e) {
					continue;
				}
			}
		}

		// 如果lastResult仍为空，抛出异常
		if (lastResult == null) {
			throw new IllegalStateException("Failed to parse any valid result from streaming response");
		}
		// 创建结果Map
		Map<String, Object> resultMap = new HashMap<>();
		// 将lastResult放入resultMap中
		resultMap.put("result", lastResult);
		// 返回结果Map
		return resultMap;
	}

	// 从结果中提取响应文本
	private String extractResponseText(Map<String, Object> result) {
		// 如果结果为空，抛出异常
		if (result == null) {
			throw new IllegalStateException("Result is null, cannot extract response text");
		}

		// 如果结果的kind为"status-update"
		if ("status-update".equals(result.get("kind"))) {
			// 获取status部分
			Map<String, Object> status = (Map<String, Object>) result.get("status");
			// 如果status不为空
			if (status != null) {
				// 获取状态值
				String state = (String) status.get("state");
				// 根据不同状态返回相应内容
				if ("completed".equals(state)) {
					return "";
				}
				else if ("processing".equals(state)) {
					return "";
				}
				else if ("failed".equals(state)) {
					return "";
				}
				// 如果状态为"working"
				else if ("working".equals(state)) {
					// 获取消息部分
					Map<String, Object> message = (Map<String, Object>) status.get("message");
					// 如果消息不为空且包含parts键
					if (message != null && message.containsKey("parts")) {
						// 获取parts部分
						List<Object> parts = (List<Object>) message.get("parts");
						// 如果parts不为空且不为空列表
						if (parts != null && !parts.isEmpty()) {
							// 获取最后一个part
							Map<String, Object> lastPart = (Map<String, Object>) parts.get(parts.size() - 1);
							// 如果最后一个part不为空
							if (lastPart != null) {
								// 获取文本内容
								String text = (String) lastPart.get("text");
								// 如果文本不为空，返回文本
								if (text != null) {
									return text;
								}
							}
						}
					}
					return "";
				}
				// 其他状态
				else {
					return "Agent State: " + state;
				}
			}
			return "";
		}

		// 如果结果的kind为"artifact-update"
		if ("artifact-update".equals(result.get("kind"))) {
			// 获取artifact部分
			Map<String, Object> artifact = (Map<String, Object>) result.get("artifact");
			// 如果artifact不为空且包含parts键
			if (artifact != null && artifact.containsKey("parts")) {
				// 获取parts部分
				List<Object> parts = (List<Object>) artifact.get("parts");
				// 如果parts不为空且不为空列表
				if (parts != null && !parts.isEmpty()) {
					// 创建响应构建器
					StringBuilder responseBuilder = new StringBuilder();
					// 遍历每个part
					for (Object part : parts) {
						// 如果part是Map类型
						if (part instanceof Map) {
							// 获取文本内容
							String text = (String) ((Map<String, Object>) part).get("text");
							// 如果文本不为空，添加到响应构建器中
							if (text != null) {
								responseBuilder.append(text);
							}
						}
					}
					// 构建响应字符串
					String response = responseBuilder.toString();
					// 如果响应不为空，返回响应
					if (!response.isEmpty()) {
						return response;
					}
				}
			}
			return "";
		}
		// 如果结果包含artifacts键
		if (result.containsKey("artifacts")) {
			// 获取artifacts部分
			List<Object> artifacts = (List<Object>) result.get("artifacts");
			// 如果artifacts不为空且不为空列表
			if (artifacts != null && !artifacts.isEmpty()) {
				// 创建响应构建器
				StringBuilder responseBuilder = new StringBuilder();
				// 遍历每个artifact
				for (Object artifact : artifacts) {
					// 如果artifact是Map类型
					if (artifact instanceof Map) {
						// 获取parts部分
						List<Object> parts = (List<Object>) ((Map<String, Object>) artifact).get("parts");
						// 如果parts不为空
						if (parts != null) {
							// 遍历每个part
							for (Object part : parts) {
								// 如果part是Map类型
								if (part instanceof Map) {
									// 获取文本内容
									String text = (String) ((Map<String, Object>) part).get("text");
									// 如果文本不为空，添加到响应构建器中
									if (text != null) {
										responseBuilder.append(text);
									}
								}
							}
						}
					}
				}
				// 构建响应字符串
				String response = responseBuilder.toString();
				// 如果响应不为空，返回响应
				if (!response.isEmpty()) {
					return response;
				}
			}
		}
		// 如果结果包含parts键
		if (result.containsKey("parts")) {
			// 获取parts部分
			List<Object> parts = (List<Object>) result.get("parts");
			// 如果parts不为空且不为空列表
			if (parts != null && !parts.isEmpty()) {
				// 获取最后一个part
				Map<String, Object> lastPart = (Map<String, Object>) parts.get(parts.size() - 1);
				// 如果最后一个part不为空
				if (lastPart != null) {
					// 获取文本内容
					String text = (String) lastPart.get("text");
					// 如果文本不为空，返回文本
					if (text != null) {
						return text;
					}
				}
			}
		}
		// 如果结果包含message键
		if (result.containsKey("message")) {
			// 获取message部分
			Map<String, Object> message = (Map<String, Object>) result.get("message");
			// 如果message不为空且包含parts键
			if (message != null && message.containsKey("parts")) {
				// 获取parts部分
				List<Object> parts = (List<Object>) message.get("parts");
				// 如果parts不为空且不为空列表
				if (parts != null && !parts.isEmpty()) {
					// 获取最后一个part
					Map<String, Object> lastPart = (Map<String, Object>) parts.get(parts.size() - 1);
					// 如果最后一个part不为空
					if (lastPart != null) {
						// 获取文本内容
						String text = (String) lastPart.get("text");
						// 如果文本不为空，返回文本
						if (text != null) {
							return text;
						}
					}
				}
			}
		}
		// 如果没有找到有效的文本内容，抛出异常
		throw new IllegalStateException("No valid text content found in result: " + result);
	}

	/**
	 * Build the JSON-RPC request payload to send to the A2A server.
	 * @param state Parent state
	 * @return JSON string payload (e.g., JSON-RPC params)
	 */
	// 构建发送给A2A服务器的JSON-RPC请求载荷
	private String buildSendMessageRequest(OverAllState state, RunnableConfig config) {
		// 获取有效的指令文本
		Object textValue = getEffectiveInstruction(state);
		// 将指令文本转换为字符串
		String text = String.valueOf(textValue);

		// 生成唯一ID
		String id = UUID.randomUUID().toString();
		// 生成消息ID并移除其中的短横线
		String messageId = UUID.randomUUID().toString().replace("-", "");

		// 创建消息部分内容，类型为"text"，内容为text
		Map<String, Object> part = Map.of("kind", "text", "text", text);

		// 创建消息对象
		Map<String, Object> message = new HashMap<>();
		// 设置消息类型为"message"
		message.put("kind", "message");
		// 设置消息ID
		message.put("messageId", messageId);
		// 设置消息部分，这里是一个包含part的列表
		message.put("parts", List.of(part));
		// 设置消息角色为"user"
		message.put("role", "user");

		// 创建参数对象
		Map<String, Object> params = new HashMap<>();
		// 将消息对象放入参数中
		params.put("message", message);

		// 创建元数据对象
		Map<String, Object> metadata = new HashMap<>();
		// 如果配置中有线程ID，则将其添加到元数据中
		config.threadId().ifPresent(threadId -> metadata.put("threadId", threadId));
		// FIXME, the key 'userId' should be configurable
		// 如果配置中有用户ID，则将其添加到元数据中
		config.metadata("userId").ifPresent(userId -> metadata.put("userId", userId));
		// 将元数据放入参数中
		params.put("metadata", metadata);

		// 创建根对象
		Map<String, Object> root = new HashMap<>();
		// 设置请求ID
		root.put("id", id);
		// 设置JSON-RPC版本
		root.put("jsonrpc", "2.0");
		// 设置方法名为"message/send"
		root.put("method", "message/send");
		// 设置参数
		root.put("params", params);

		// 尝试将根对象转换为JSON字符串
		try {
			return objectMapper.writeValueAsString(root);
		}
		// 如果转换失败，抛出非法状态异常
		catch (Exception e) {
			throw new IllegalStateException("Failed to build JSON-RPC payload", e);
		}
	}

	/**
	 * Build the JSON-RPC streaming request payload (method: message/stream).
	 * @param state Parent state
	 * @return JSON string payload for streaming
	 */
	// 构建JSON-RPC流式请求载荷（方法：message/stream）
	private String buildSendStreamingMessageRequest(OverAllState state, RunnableConfig config) {
		// 获取有效的指令文本
		Object textValue = getEffectiveInstruction(state);
		// 将指令文本转换为字符串
		String text = String.valueOf(textValue);

		// 生成唯一ID
		String id = UUID.randomUUID().toString();
		// 生成消息ID并移除其中的短横线
		String messageId = UUID.randomUUID().toString().replace("-", "");

		// 创建消息部分内容，类型为"text"，内容为text
		Map<String, Object> part = Map.of("kind", "text", "text", text);

		// 创建消息对象
		Map<String, Object> message = new HashMap<>();
		// 设置消息类型为"message"
		message.put("kind", "message");
		// 设置消息ID
		message.put("messageId", messageId);
		// 设置消息部分，这里是一个包含part的列表
		message.put("parts", List.of(part));
		// 设置消息角色为"user"
		message.put("role", "user");

		// 创建参数对象
		Map<String, Object> params = new HashMap<>();
		// 将消息对象放入参数中
		params.put("message", message);

		// 创建元数据对象
		Map<String, Object> metadata = new HashMap<>();
		// 如果配置中有线程ID，则将其添加到元数据中
		config.threadId().ifPresent(threadId -> metadata.put("threadId", threadId));
		// FIXME, the key 'userId' should be configurable
		// 如果配置中有用户ID，则将其添加到元数据中
		config.metadata("userId").ifPresent(userId -> metadata.put("userId", userId));
		// 将元数据放入参数中
		params.put("metadata", metadata);

		// 创建根对象
		Map<String, Object> root = new HashMap<>();
		// 设置请求ID
		root.put("id", id);
		// 设置JSON-RPC版本
		root.put("jsonrpc", "2.0");
		// 设置方法名为"message/stream"
		root.put("method", "message/stream");
		// 设置参数
		root.put("params", params);

		// 尝试将根对象转换为JSON字符串
		try {
			return objectMapper.writeValueAsString(root);
		}
		// 如果转换失败，抛出非法状态异常
		catch (Exception e) {
			throw new IllegalStateException("Failed to build JSON-RPC streaming payload", e);
		}
	}

	// 获取有效指令的方法
	private String getEffectiveInstruction(OverAllState state) {
		// 如果指令不为空，则使用PromptTemplate渲染指令
		if (StringUtils.hasLength(this.instruction)) {
			// 创建PromptTemplate构建器并设置模板
			PromptTemplate template = PromptTemplate.builder().template(this.instruction).build();
			// 渲染模板并返回结果
			return template.render(state.data());
		// 如果不共享状态或者共享状态但消息为空，则抛出异常
		} else if (!shareState || (shareState && state.value("messages").isEmpty())) {
			// 抛出非法状态异常
			throw new IllegalStateException("Instruction is empty and shareState is false");
		}
		// 返回空字符串
		return "";
	}

	/**
	 * Send the request to the remote A2A server and return the non-streaming response.
	 * @param agentCard Agent card (source for server URL/metadata)
	 * @param requestPayload JSON string payload built by buildSendMessageRequest
	 * @return Response body as string
	 */
	// 发送请求到远程A2A服务器并返回非流式响应
	private String sendMessageToServer(AgentCardWrapper agentCard, String requestPayload) throws Exception {
		// 解析AgentCard中的基础URL
		String baseUrl = resolveAgentBaseUrl(agentCard);
		// 打印基础URL
		System.out.println(baseUrl);
		// 打印请求载荷
		System.out.println(requestPayload);
		// 如果基础URL为空或空白，则抛出非法状态异常
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new IllegalStateException("AgentCard.url is empty");
		}

		// 尝试创建默认的可关闭HTTP客户端
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			// 创建HTTP POST请求
			HttpPost post = new HttpPost(baseUrl);
			// 设置内容类型头部为"application/json"
			post.setHeader("Content-Type", "application/json");
			// 设置请求实体为JSON字符串载荷
			post.setEntity(new StringEntity(requestPayload, ContentType.APPLICATION_JSON));

			// 尝试执行HTTP请求
			try (CloseableHttpResponse response = httpClient.execute(post)) {
				// 获取响应状态码
				int statusCode = response.getStatusLine().getStatusCode();
				// 如果状态码不是200，则抛出非法状态异常
				if (statusCode != 200) {
					throw new IllegalStateException("HTTP request failed, status: " + statusCode);
				}
				// 获取响应实体
				HttpEntity entity = response.getEntity();
				// 如果实体为空，则抛出非法状态异常
				if (entity == null) {
					throw new IllegalStateException("Empty HTTP entity");
				}
				// 将实体内容转换为UTF-8编码的字符串并返回
				return EntityUtils.toString(entity, "UTF-8");
			}
		}
	}

	/**
	 * Resolve base URL from the AgentCard.
	 */
	// 从AgentCard中解析基础URL
	private String resolveAgentBaseUrl(AgentCardWrapper agentCard) {
		// 返回AgentCard的URL
		return agentCard.url();
	}
}
