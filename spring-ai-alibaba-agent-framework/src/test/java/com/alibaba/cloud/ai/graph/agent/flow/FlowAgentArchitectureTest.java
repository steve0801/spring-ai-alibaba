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
package com.alibaba.cloud.ai.graph.agent.flow;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.flow.builder.FlowAgentBuilder;
import com.alibaba.cloud.ai.graph.agent.flow.builder.FlowGraphBuilder;
import com.alibaba.cloud.ai.graph.agent.flow.enums.FlowAgentEnum;
import com.alibaba.cloud.ai.graph.agent.flow.strategy.FlowGraphBuildingStrategy;
import com.alibaba.cloud.ai.graph.agent.flow.strategy.FlowGraphBuildingStrategyRegistry;
import com.alibaba.cloud.ai.graph.agent.flow.strategy.SequentialGraphBuildingStrategy;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the refactored FlowAgent architecture demonstrating the improved design
 * patterns. These tests verify that the new architecture provides extensibility,
 * consistency, and type safety.
 */
class FlowAgentArchitectureTest {

	@Mock
	// 模拟ChatClient对象，用于测试中替代真实的聊天客户端
	private ChatClient chatClient;

	@Mock
	// 模拟ChatModel对象，用于测试中替代真实的聊天模型
	private ChatModel chatModel;

	@Mock
	// 模拟ToolCallbackResolver对象，用于测试中替代真实的工具回调解析器
	private ToolCallbackResolver resolver;

	@BeforeEach
	// 在每个测试方法执行前运行的初始化方法
	void setUp() {
		// 初始化Mockito注解，启用对@Mock注解对象的模拟
		MockitoAnnotations.openMocks(this);
	}

	@Test
	// 测试SequentialAgent的构建器模式实现
	void testSequentialAgentBuilderPattern() throws Exception {
		// 创建一个子代理，用于测试SequentialAgent的功能
		ReactAgent subAgent = createMockReactAgent("dataProcessor", "processed_data");

		// 测试SequentialAgent使用统一的构建器模式
		SequentialAgent agent = SequentialAgent.builder()
			.name("sequentialWorkflow")
			.description("A sequential workflow")
			.subAgents(List.of(subAgent))
			.build();

		// 验证代理属性是否正确设置
		assertNotNull(agent);
		assertEquals("sequentialWorkflow", agent.name());
		assertEquals("A sequential workflow", agent.description());
		assertEquals(1, agent.subAgents().size());
	}

	@Test
	// 测试LlmRoutingAgent的构建器模式实现
	void testLlmRoutingAgentBuilderPattern() throws Exception {
		// 创建子代理，用于测试LlmRoutingAgent的功能
		ReactAgent agent1 = createMockReactAgent("analysisAgent", "analysis_result");
		ReactAgent agent2 = createMockReactAgent("reportAgent", "report_result");

		// 测试LlmRoutingAgent使用统一的构建器模式，并支持LLM特定功能
		LlmRoutingAgent agent = LlmRoutingAgent.builder()
			.name("intelligentRouter")
			.description("Routes tasks intelligently")
			.subAgents(List.of(agent1, agent2))
			.model(chatModel) // LLM特定配置
			.build();

		// 验证代理属性是否正确设置
		assertNotNull(agent);
		assertEquals("intelligentRouter", agent.name());
		assertEquals("Routes tasks intelligently", agent.description());
		assertEquals(2, agent.subAgents().size());
	}

	@Test
	// 测试ParallelAgent的构建器模式实现
	void testParallelAgentBuilderPattern() throws Exception {
		// 创建子代理，用于测试ParallelAgent的功能
		ReactAgent agent1 = createMockReactAgent("dataAnalyzer", "analysis_result");
		ReactAgent agent2 = createMockReactAgent("dataValidator", "validation_result");
		ReactAgent agent3 = createMockReactAgent("dataCleaner", "cleaning_result");

		// 测试ParallelAgent使用统一的构建器模式，并支持并行特定功能
		ParallelAgent agent = ParallelAgent.builder()
			.name("dataProcessingPipeline")
			.description("Processes data in parallel")
			.mergeOutputKey("processing_result")
			.subAgents(List.of(agent1, agent2, agent3))
			.mergeStrategy(new ParallelAgent.DefaultMergeStrategy())
			.maxConcurrency(3)
			.build();

		// 验证代理属性是否正确设置
		assertNotNull(agent);
		assertEquals("dataProcessingPipeline", agent.name());
		assertEquals("Processes data in parallel", agent.description());
		assertEquals(3, agent.subAgents().size());
		assertEquals(3, agent.maxConcurrency());
		assertTrue(agent.mergeStrategy() instanceof ParallelAgent.DefaultMergeStrategy);
	}

	@Test
	// 测试构建器的验证功能
	void testBuilderValidation() {
		// 测试构建器是否正确验证必需字段
		assertThrows(IllegalArgumentException.class, () -> {
			SequentialAgent.builder().description("Missing name").build();
		});

		assertThrows(IllegalArgumentException.class, () -> {
			LlmRoutingAgent.builder()
				.name("router")
				.subAgents(List.of(createMockReactAgent("agent", "output")))
				.build(); // 缺少ChatModel
		});

		assertThrows(IllegalArgumentException.class, () -> {
			ParallelAgent.builder()
				.name("parallel")
				.subAgents(List.of(createMockReactAgent("agent", "output"))) // 需要至少2个代理
				.build();
		});
	}

	@Test
	// 测试策略注册表的可扩展性
	void testStrategyRegistryExtensibility() {
		// 测试策略注册表是否允许运行时扩展
		FlowGraphBuildingStrategyRegistry registry = FlowGraphBuildingStrategyRegistry.getInstance();

		// 验证默认策略是否已注册
		assertTrue(registry.hasStrategy(FlowAgentEnum.SEQUENTIAL.getType()));
		assertTrue(registry.hasStrategy(FlowAgentEnum.ROUTING.getType()));
		assertTrue(registry.hasStrategy(FlowAgentEnum.PARALLEL.getType()));
		assertTrue(registry.hasStrategy(FlowAgentEnum.CONDITIONAL.getType()));

		// 测试获取策略功能
		FlowGraphBuildingStrategy sequentialStrategy = registry.getStrategy(FlowAgentEnum.SEQUENTIAL.getType());
		assertNotNull(sequentialStrategy);
		assertTrue(sequentialStrategy instanceof SequentialGraphBuildingStrategy);

		// 测试获取不存在的策略是否会抛出异常
		assertThrows(IllegalArgumentException.class, () -> {
			registry.getStrategy("NON_EXISTENT_STRATEGY");
		});
	}

	@Test
	// 测试FlowGraphConfig的自定义属性功能
	void testFlowGraphConfigCustomProperties() {
		// 测试FlowGraphConfig是否支持自定义属性以实现可扩展性
		FlowGraphBuilder.FlowGraphConfig config = FlowGraphBuilder.FlowGraphConfig.builder()
			.name("testConfig")
			.customProperty("maxRetries", 3)
			.customProperty("timeout", 5000L)
			.customProperty("enableDebug", true);

		assertEquals(3, config.getCustomProperty("maxRetries"));
		assertEquals(5000L, config.getCustomProperty("timeout"));
		assertEquals(true, config.getCustomProperty("enableDebug"));
		assertNull(config.getCustomProperty("nonExistentProperty"));
	}

	@Test
	// 测试合并策略功能
	void testMergeStrategies() {
		// 测试ParallelAgent的不同合并策略
		ParallelAgent.DefaultMergeStrategy defaultStrategy = new ParallelAgent.DefaultMergeStrategy();
		ParallelAgent.ListMergeStrategy listStrategy = new ParallelAgent.ListMergeStrategy();
		ParallelAgent.ConcatenationMergeStrategy concatStrategy = new ParallelAgent.ConcatenationMergeStrategy(" | ");

		assertNotNull(defaultStrategy);
		assertNotNull(listStrategy);
		assertNotNull(concatStrategy);

		// 测试策略类型
		assertTrue(defaultStrategy instanceof ParallelAgent.MergeStrategy);
		assertTrue(listStrategy instanceof ParallelAgent.MergeStrategy);
		assertTrue(concatStrategy instanceof ParallelAgent.MergeStrategy);
	}

	@Test
	// 测试流畅接口功能
	void testFluentInterface() throws Exception {
		// 测试所有构建器是否支持流畅接口
		ParallelAgent.ParallelAgentBuilder builder = ParallelAgent.builder();

		// 所有方法应该返回相同的构建器实例以支持方法链
		assertSame(builder, builder.name("test"));
		assertSame(builder, builder.description("test description"));
		assertSame(builder, builder.maxConcurrency(5));
		assertSame(builder, builder.mergeStrategy(new ParallelAgent.DefaultMergeStrategy()));
	}

	@Test
	// 测试构建器继承关系
	void testBuilderInheritance() {
		// 测试具体构建器是否正确继承自FlowAgentBuilder
		assertTrue(SequentialAgent.builder() instanceof FlowAgentBuilder);
		assertTrue(LlmRoutingAgent.builder() instanceof FlowAgentBuilder);
		assertTrue(ParallelAgent.builder() instanceof FlowAgentBuilder);
	}

	// 创建模拟ReactAgent的辅助方法
	private ReactAgent createMockReactAgent(String name, String outputKey) throws Exception {
		return ReactAgent.builder()
			.name(name)
			.description("Mock agent: " + name)
			.outputKey(outputKey)
			.chatClient(chatClient)
			.resolver(resolver)
			.build();
	}

}
