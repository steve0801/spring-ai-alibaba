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
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ParallelAgent demonstrating the refactored architecture with different
 * merge strategies. These tests verify the parallel execution capabilities and the
 * strategy pattern implementation.
 */
class ParallelAgentTest {

	@Mock
	// 模拟ChatClient对象，用于测试中替代真实的聊天客户端
	private ChatClient chatClient;

	@Mock
	// 模拟ToolCallbackResolver对象，用于测试中替代真实的工具回调解析器
	private ToolCallbackResolver toolCallbackResolver;

	@BeforeEach
	// 在每个测试方法执行前运行的初始化方法
	void setUp() {
		// 初始化Mockito注解，启用对@Mock注解对象的模拟
		MockitoAnnotations.openMocks(this);
	}

	@Test
	// 测试数据处理并行代理的功能
	void testDataProcessingParallelAgent() throws Exception {
		// 创建使用默认合并策略的数据处理并行代理
		ParallelAgent parallelAgent = createDataProcessingParallelAgent();

		// 验证构建的代理
		assertNotNull(parallelAgent);
		assertEquals("dataProcessingPipeline", parallelAgent.name());
		assertEquals("Processes data through multiple parallel operations", parallelAgent.description());
		assertEquals(3, parallelAgent.subAgents().size());
		assertEquals(3, parallelAgent.maxConcurrency());
		assertTrue(parallelAgent.mergeStrategy() instanceof ParallelAgent.DefaultMergeStrategy);
	}

	@Test
	// 测试报告生成并行代理的功能
	void testReportGenerationParallelAgent() throws Exception {
		// 创建使用列表合并策略的报告生成并行代理
		ParallelAgent parallelAgent = createReportGenerationParallelAgent();

		// 验证构建的代理
		assertNotNull(parallelAgent);
		assertEquals("reportGenerator", parallelAgent.name());
		assertEquals("Generates comprehensive reports in parallel", parallelAgent.description());
		assertEquals(3, parallelAgent.subAgents().size());
		assertEquals(5, parallelAgent.maxConcurrency());
		assertTrue(parallelAgent.mergeStrategy() instanceof ParallelAgent.ListMergeStrategy);
	}

	@Test
	// 测试内容创建并行代理的功能
	void testContentCreationParallelAgent() throws Exception {
		// 创建使用连接合并策略的内容创建并行代理
		ParallelAgent parallelAgent = createContentCreationParallelAgent();

		// 验证构建的代理
		assertNotNull(parallelAgent);
		assertEquals("contentCreator", parallelAgent.name());
		assertEquals("Creates content through parallel writing", parallelAgent.description());
		assertEquals(3, parallelAgent.subAgents().size());
		assertNull(parallelAgent.maxConcurrency()); // 未设置并发限制
		assertTrue(parallelAgent.mergeStrategy() instanceof ParallelAgent.ConcatenationMergeStrategy);
	}

	@Test
	// 测试并行代理构建器的流畅接口
	void testParallelAgentBuilderFluentInterface() throws Exception {

		// 创建用于并行执行的子代理
		ReactAgent agent1 = ReactAgent.builder()
			.name("dataAnalyzer")
			.description("Analyzes data")
			.outputKey("analysis_result")
			.chatClient(chatClient)
			.resolver(toolCallbackResolver)
			.build();

		ReactAgent agent2 = ReactAgent.builder()
			.name("dataValidator")
			.description("Validates data")
			.outputKey("validation_result")
			.chatClient(chatClient)
			.resolver(toolCallbackResolver)
			.build();

		// 使用并行代理测试流畅接口
		ParallelAgent parallelAgent = ParallelAgent.builder()
			.name("parallelProcessor")
			.description("Processes data in parallel")
			.mergeOutputKey("parallel_result")
			.subAgents(List.of(agent1, agent2))
			.mergeStrategy(new ParallelAgent.ListMergeStrategy())
			.maxConcurrency(5)
			.build();

		assertNotNull(parallelAgent);
		assertEquals("parallelProcessor", parallelAgent.name());
		assertEquals("Processes data in parallel", parallelAgent.description());
		assertEquals(2, parallelAgent.subAgents().size());
		assertNotNull(parallelAgent.mergeStrategy());
		assertEquals(5, parallelAgent.maxConcurrency());
	}

	@Test
	// 测试并行代理的验证功能
	void testParallelAgentValidation() {
		// 测试验证 - 需要至少2个子代理
		Exception exception = assertThrows(IllegalArgumentException.class, () -> {
			ParallelAgent.builder()
				.name("testAgent")
				.subAgents(List.of()) // 空列表
				.build();
		});
		assertTrue(exception.getMessage().contains("Sub-agents must be provided"));

		// 测试验证 - 最多10个子代理
		ReactAgent[] agents = new ReactAgent[11];
		for (int i = 0; i < 11; i++) {
			try {
				agents[i] = createMockAgent("agent" + i, "output" + i);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		exception = assertThrows(IllegalArgumentException.class, () -> {
			ParallelAgent.builder().name("testAgent").subAgents(List.of(agents)).build();
		});
		assertTrue(exception.getMessage().contains("maximum 10 sub-agents"));
	}

	@Test
	// 测试唯一输出键验证功能
	void testUniqueOutputKeyValidation() throws Exception {
		MockitoAnnotations.openMocks(this);

		ReactAgent agent1 = createMockAgent("agent1", "same_output");
		ReactAgent agent2 = createMockAgent("agent2", "same_output"); // 相同的输出键

		Exception exception = assertThrows(IllegalArgumentException.class, () -> {
			ParallelAgent.builder().name("testAgent").subAgents(List.of(agent1, agent2)).build();
		});
		assertTrue(exception.getMessage().contains("Duplicate output keys"));
	}

	@Test
	// 测试合并策略功能
	void testMergeStrategies() {
		// 测试默认合并策略
		ParallelAgent.DefaultMergeStrategy defaultStrategy = new ParallelAgent.DefaultMergeStrategy();
		HashMap<String, Object> results = new HashMap<>();
		results.put("key1", "value1");
		results.put("key2", "value2");

		Object merged = defaultStrategy.merge(results, null);
		assertTrue(merged instanceof HashMap);
		@SuppressWarnings("unchecked")
		HashMap<String, Object> mergedMap = (HashMap<String, Object>) merged;
		assertEquals("value1", mergedMap.get("key1"));
		assertEquals("value2", mergedMap.get("key2"));

		// 测试列表合并策略
		ParallelAgent.ListMergeStrategy listStrategy = new ParallelAgent.ListMergeStrategy();
		Object listResult = listStrategy.merge(results, null);
		assertTrue(listResult instanceof List);
		List<?> resultList = (List<?>) listResult;
		assertEquals(2, resultList.size());
		assertTrue(resultList.contains("value1"));
		assertTrue(resultList.contains("value2"));

		// 测试连接合并策略
		ParallelAgent.ConcatenationMergeStrategy concatStrategy = new ParallelAgent.ConcatenationMergeStrategy(" | ");
		Object concatResult = concatStrategy.merge(results, null);
		assertTrue(concatResult instanceof String);
		String concatString = (String) concatResult;
		assertTrue(concatString.contains("value1"));
		assertTrue(concatString.contains("value2"));
		assertTrue(concatString.contains(" | "));
	}

	// 创建模拟代理的辅助方法
	private ReactAgent createMockAgent(String name, String outputKey) throws Exception {
		return ReactAgent.builder()
			.name(name)
			.description("Mock agent")
			.outputKey(outputKey)
			.chatClient(chatClient)
			.resolver(toolCallbackResolver)
			.build();
	}

	/**
	 * Factory method for creating data processing ParallelAgent with default merge
	 * strategy.
	 */
	// 创建数据处理并行代理的工厂方法，使用默认合并策略
	private ParallelAgent createDataProcessingParallelAgent() throws Exception {
		// 创建用于数据处理不同方面的子代理
		ReactAgent dataAnalyzer = ReactAgent.builder()
			.name("dataAnalyzer")
			.description("Analyzes data patterns and trends")
			.outputKey("analysis_result")
			.chatClient(chatClient)
			.resolver(toolCallbackResolver)
			.build();

		ReactAgent dataValidator = ReactAgent.builder()
			.name("dataValidator")
			.description("Validates data quality and integrity")
			.outputKey("validation_result")
			.chatClient(chatClient)
			.resolver(toolCallbackResolver)
			.build();

		ReactAgent dataCleaner = ReactAgent.builder()
			.name("dataCleaner")
			.description("Cleans and preprocesses data")
			.outputKey("cleaning_result")
			.chatClient(chatClient)
			.resolver(toolCallbackResolver)
			.build();

		// 使用改进的构建器和默认合并策略创建并行代理
		return ParallelAgent.builder()
			.name("dataProcessingPipeline")
			.description("Processes data through multiple parallel operations")
			.mergeOutputKey("processing_result")
			.subAgents(List.of(dataAnalyzer, dataValidator, dataCleaner))
			.mergeStrategy(new ParallelAgent.DefaultMergeStrategy()) // 返回Map
			.maxConcurrency(3) // 限制为3个并发操作
			.build();
	}

	/**
	 * Factory method for creating report generation ParallelAgent with list merge
	 * strategy.
	 */
	// 创建报告生成并行代理的工厂方法，使用列表合并策略
	private ParallelAgent createReportGenerationParallelAgent() throws Exception {
		// 创建用于不同报告部分的子代理
		ReactAgent summaryGenerator = ReactAgent.builder()
			.name("summaryGenerator")
			.description("Generates executive summary")
			.outputKey("summary_section")
			.chatClient(chatClient)
			.resolver(toolCallbackResolver)
			.build();

		ReactAgent detailsGenerator = ReactAgent.builder()
			.name("detailsGenerator")
			.description("Generates detailed analysis")
			.outputKey("details_section")
			.chatClient(chatClient)
			.resolver(toolCallbackResolver)
			.build();

		ReactAgent chartsGenerator = ReactAgent.builder()
			.name("chartsGenerator")
			.description("Generates charts and visualizations")
			.outputKey("charts_section")
			.chatClient(chatClient)
			.resolver(toolCallbackResolver)
			.build();

		// 使用列表合并策略创建并行代理
		return ParallelAgent.builder()
			.name("reportGenerator")
			.description("Generates comprehensive reports in parallel")
			.mergeOutputKey("complete_report")
			.subAgents(List.of(summaryGenerator, detailsGenerator, chartsGenerator))
			.mergeStrategy(new ParallelAgent.ListMergeStrategy()) // 返回List
			.maxConcurrency(5)
			.build();
	}

	/**
	 * Factory method for creating content creation ParallelAgent with concatenation merge
	 * strategy.
	 */
	// 创建内容创建并行代理的工厂方法，使用连接合并策略
	private ParallelAgent createContentCreationParallelAgent() throws Exception {
		// 创建用于不同内容部分的子代理
		ReactAgent introWriter = ReactAgent.builder()
			.name("introWriter")
			.description("Writes introduction content")
			.outputKey("intro_content")
			.chatClient(chatClient)
			.resolver(toolCallbackResolver)
			.build();

		ReactAgent bodyWriter = ReactAgent.builder()
			.name("bodyWriter")
			.description("Writes main body content")
			.outputKey("body_content")
			.chatClient(chatClient)
			.resolver(toolCallbackResolver)
			.build();

		ReactAgent conclusionWriter = ReactAgent.builder()
			.name("conclusionWriter")
			.description("Writes conclusion content")
			.outputKey("conclusion_content")
			.chatClient(chatClient)
			.resolver(toolCallbackResolver)
			.build();

		// 使用连接合并策略创建并行代理
		return ParallelAgent.builder()
			.name("contentCreator")
			.description("Creates content through parallel writing")
			.mergeOutputKey("final_content")
			.subAgents(List.of(introWriter, bodyWriter, conclusionWriter))
			.mergeStrategy(new ParallelAgent.ConcatenationMergeStrategy("\n\n")) // 连接
			.build();
	}

}
