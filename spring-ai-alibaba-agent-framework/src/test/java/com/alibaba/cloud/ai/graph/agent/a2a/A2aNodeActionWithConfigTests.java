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

import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.async.AsyncGenerator;

import org.springframework.ai.chat.metadata.EmptyUsage;

import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class A2aNodeActionWithConfigTests {

	private static final Method TO_FLUX = initToFluxMethod();

	private final A2aNodeActionWithConfig action = new A2aNodeActionWithConfig(createAgentCardWrapper(), "", false,
			"messages", "instruction", true);

	@Test
	// 测试toFlux方法是否能正确发射异步数据
	void toFluxEmitsAsyncData() throws Exception {
		// 创建一个异步生成器，用于生成NodeOutput数据
		AsyncGenerator<NodeOutput> generator = new AsyncGenerator<>() {
			// 使用原子整数作为步骤计数器
			private final AtomicInteger index = new AtomicInteger();

			// 实现next方法，定义数据生成逻辑
			@Override
			public Data<NodeOutput> next() {
				// 获取并递增步骤计数器
				int step = index.getAndIncrement();
				// 第一步：返回一个异步完成的NodeOutput数据
				if (step == 0) {
					return Data.of(
							CompletableFuture.supplyAsync(() -> NodeOutput.of("node-1", "", new OverAllState(), new EmptyUsage())));
				}
				// 第二步：返回完成状态的数据，包含结果映射
				if (step == 1) {
					return Data.done(Map.of("result", "ok"));
				}
				// 其他步骤：返回完成状态的数据
				return Data.done();
			}
		};

		// 调用invokeToFlux方法，将生成器转换为Flux流
		Flux<GraphResponse<NodeOutput>> flux = invokeToFlux(generator);

		// 收集Flux流中的所有响应，在1秒超时内完成
		List<GraphResponse<NodeOutput>> responses = flux.collectList().block(Duration.ofSeconds(1));

		// 验证响应列表不为空
		assertNotNull(responses);
		// 验证响应列表大小为2
		assertEquals(2, responses.size());

		// 获取第一个响应
		GraphResponse<NodeOutput> first = responses.get(0);
		// 验证第一个响应不是完成状态
		assertFalse(first.isDone());
		// 获取输出数据
		NodeOutput output = first.getOutput().getNow(null);
		// 验证输出数据不为空
		assertNotNull(output);
		// 验证节点名称为"node-1"
		assertEquals("node-1", output.node());

		// 获取第二个响应
		GraphResponse<NodeOutput> second = responses.get(1);
		// 验证第二个响应是完成状态
		assertTrue(second.isDone());
		// 获取结果值
		Map<?, ?> resultValue = (Map<?, ?>) second.resultValue().orElseThrow();
		// 验证结果值中的"result"字段为"ok"
		assertEquals("ok", resultValue.get("result"));
	}

	@Test
	// 测试toFlux方法是否能正确传播错误
	void toFluxPropagatesErrors() throws Exception {
		// 创建一个异步生成器，用于测试错误传播
		AsyncGenerator<NodeOutput> generator = new AsyncGenerator<>() {
			// 使用原子整数作为步骤计数器
			private final AtomicInteger index = new AtomicInteger();

			// 实现next方法，定义数据生成逻辑（包含错误）
			@Override
			public Data<NodeOutput> next() {
				// 获取并递增步骤计数器
				int step = index.getAndIncrement();
				// 第一步：返回一个会抛出异常的异步任务
				if (step == 0) {
					return Data.of(CompletableFuture.supplyAsync(() -> {
						throw new IllegalStateException("boom");
					}));
				}
				// 其他步骤：返回完成状态的数据
				return Data.done();
			}
		};

		// 调用invokeToFlux方法，将生成器转换为Flux流
		Flux<GraphResponse<NodeOutput>> flux = invokeToFlux(generator);

		// 验证会抛出IllegalStateException异常
		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> flux.collectList().block(Duration.ofSeconds(1)));
		// 验证异常消息为"boom"
		assertEquals("boom", exception.getMessage());
	}

	@SuppressWarnings("unchecked")
	// 通过反射调用A2aNodeActionWithConfig的toFlux私有方法
	private Flux<GraphResponse<NodeOutput>> invokeToFlux(AsyncGenerator<NodeOutput> generator) throws Exception {
		// 调用TO_FLUX方法，传入action实例和生成器参数
		return (Flux<GraphResponse<NodeOutput>>) TO_FLUX.invoke(this.action, generator);
	}

	// 初始化toFlux方法的反射访问
	private static Method initToFluxMethod() {
		try {
			// 获取A2aNodeActionWithConfig类的toFlux方法
			Method method = A2aNodeActionWithConfig.class.getDeclaredMethod("toFlux", AsyncGenerator.class);
			// 设置方法可访问（绕过private修饰符）
			method.setAccessible(true);
			return method;
		}
		// 如果找不到方法，抛出IllegalStateException异常
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(ex);
		}
	}

	// 创建AgentCardWrapper实例的辅助方法
	private static AgentCardWrapper createAgentCardWrapper() {
		// 创建AgentCard的mock对象
		AgentCard agentCard = mock(AgentCard.class);
		// 设置mock对象的name方法返回"test-agent"
		when(agentCard.name()).thenReturn("test-agent");
		// 返回新的AgentCardWrapper实例
		return new AgentCardWrapper(agentCard);
	}

}
