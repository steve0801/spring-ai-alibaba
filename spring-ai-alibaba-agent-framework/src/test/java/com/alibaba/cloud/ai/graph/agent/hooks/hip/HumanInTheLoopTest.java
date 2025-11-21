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
package com.alibaba.cloud.ai.graph.agent.hooks.hip;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;

import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static com.alibaba.cloud.ai.graph.agent.tools.PoetTool.createPoetToolCallback;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledIfEnvironmentVariable(named = "AI_DASHSCOPE_API_KEY", matches = ".+")
public class HumanInTheLoopTest {

	private ChatModel chatModel;

	@BeforeEach
	// 在每个测试方法执行前运行的初始化方法
	void setUp() {
		// 使用环境变量中的API密钥创建DashScopeApi实例
		DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey(System.getenv("AI_DASHSCOPE_API_KEY")).build();

		// 创建DashScope ChatModel实例
		this.chatModel = DashScopeChatModel.builder().dashScopeApi(dashScopeApi).build();
	}

	@Test
	// 测试拒绝人类反馈的场景
	public void testRejected() throws Exception {
		// 创建ReactAgent实例
		ReactAgent agent = createAgent();

		// 打印图表示例
		printGraphRepresentation(agent);

		// 设置线程ID
		String threadId = "test-thread-123";
		// 创建RunnableConfig配置
		RunnableConfig runnableConfig = RunnableConfig.builder().threadId(threadId).build();

		// 验证RunnableConfig配置正确
		Assertions.assertNotNull(runnableConfig, "RunnableConfig should not be null");
		Assertions.assertTrue(runnableConfig.threadId().isPresent(), "Thread ID should be present");
		Assertions.assertEquals(threadId, runnableConfig.threadId().get(), "Thread ID should match");

		// 执行第一次调用，应该触发中断等待人类审批
		InterruptionMetadata interruptionMetadata = performFirstInvocation(agent, runnableConfig);

		// 构建拒绝反馈的元数据
		InterruptionMetadata feedbackMetadata = buildRejectionFeedback(interruptionMetadata);

		// 执行第二次调用，使用拒绝反馈恢复执行
		performSecondInvocation(agent, threadId, feedbackMetadata);

	}

	@Test
	// 测试批准人类反馈的场景
	public void testApproved() throws Exception {
		// 创建ReactAgent实例
		ReactAgent agent = createAgent();

		// 打印图表示例
		printGraphRepresentation(agent);

		// 设置线程ID
		String threadId = "test-thread-approved";
		// 创建RunnableConfig配置
		RunnableConfig runnableConfig = RunnableConfig.builder().threadId(threadId).build();

		// 验证RunnableConfig配置正确
		Assertions.assertNotNull(runnableConfig, "RunnableConfig should not be null");
		Assertions.assertTrue(runnableConfig.threadId().isPresent(), "Thread ID should be present");
		Assertions.assertEquals(threadId, runnableConfig.threadId().get(), "Thread ID should match");

		// 执行第一次调用，应该触发中断等待人类审批
		InterruptionMetadata interruptionMetadata = performFirstInvocation(agent, runnableConfig);

		// 构建批准反馈的元数据
		InterruptionMetadata feedbackMetadata = buildApprovalFeedback(interruptionMetadata);

		// 执行第二次调用，使用批准反馈恢复执行
		performSecondInvocation(agent, threadId, feedbackMetadata);

	}

	@Test
	// 测试编辑人类反馈的场景
	public void testEdited() throws Exception {
		// 创建ReactAgent实例
		ReactAgent agent = createAgent();

		// 打印图表示例
		printGraphRepresentation(agent);

		// 设置线程ID
		String threadId = "test-thread-edited";
		// 创建RunnableConfig配置
		RunnableConfig runnableConfig = RunnableConfig.builder().threadId(threadId).build();

		// 验证RunnableConfig配置正确
		Assertions.assertNotNull(runnableConfig, "RunnableConfig should not be null");
		Assertions.assertTrue(runnableConfig.threadId().isPresent(), "Thread ID should be present");
		Assertions.assertEquals(threadId, runnableConfig.threadId().get(), "Thread ID should match");

		// 执行第一次调用，应该触发中断等待人类审批
		InterruptionMetadata interruptionMetadata = performFirstInvocation(agent, runnableConfig);

		// 构建编辑反馈的元数据
		InterruptionMetadata feedbackMetadata = buildEditedFeedback(interruptionMetadata);

		// 执行第二次调用，使用编辑反馈恢复执行
		performSecondInvocation(agent, threadId, feedbackMetadata);

	}

	// 创建ReactAgent的私有方法
	private ReactAgent createAgent() throws GraphStateException {
		return ReactAgent.builder()
				.name("single_agent")
				.model(chatModel)
				.saver(new MemorySaver())
				.tools(List.of(createPoetToolCallback()))
				.hooks(List.of(HumanInTheLoopHook.builder().approvalOn("poem", ToolConfig.builder().description("Please confirm tool execution.").build()).build()))
				.outputKey("article")
				.build();
	}

	// 打印图表示例的私有方法
	private void printGraphRepresentation(ReactAgent agent) {
		GraphRepresentation representation = agent.getGraph().getGraph(GraphRepresentation.Type.PLANTUML);
		System.out.println(representation.content());
	}

	// 执行第一次调用的私有方法
	private InterruptionMetadata performFirstInvocation(ReactAgent agent, RunnableConfig runnableConfig) throws Exception {
		// 第一次调用 - 应该触发人类审批的中断
		System.out.println("\n=== First Invocation: Expecting Interruption ===");
		Optional<NodeOutput> result = agent.invokeAndGetOutput("帮我写一篇100字左右散文。", runnableConfig);

		// 验证第一次调用导致中断
		Assertions.assertTrue(result.isPresent(), "First invocation should return a result");
		Assertions.assertInstanceOf(InterruptionMetadata.class, result.get(),
			"First invocation should return InterruptionMetadata for human approval");

		// 获取中断元数据
		InterruptionMetadata interruptionMetadata = (InterruptionMetadata) result.get();

		// 验证中断元数据包含预期信息
		Assertions.assertNotNull(interruptionMetadata.node(), "Interruption should have node id");
		Assertions.assertNotNull(interruptionMetadata.state(), "Interruption should have state");

		// 验证状态包含预期数据
		Assertions.assertNotNull(interruptionMetadata.state().data(),
			"Interruption state should have data");
		Assertions.assertFalse(interruptionMetadata.state().data().isEmpty(),
			"Interruption state data should not be empty");

		// 验证工具反馈存在
		List<InterruptionMetadata.ToolFeedback> toolFeedbacks = interruptionMetadata.toolFeedbacks();
		Assertions.assertNotNull(toolFeedbacks, "Tool feedbacks should not be null");
		Assertions.assertFalse(toolFeedbacks.isEmpty(), "Tool feedbacks should not be empty");
		Assertions.assertEquals(1, toolFeedbacks.size(),
			"Should have exactly one tool feedback for the 'poem' tool");

		// 验证工具反馈详情
		InterruptionMetadata.ToolFeedback firstFeedback = toolFeedbacks.get(0);
		Assertions.assertNotNull(firstFeedback.getId(), "Tool feedback should have an id");
		Assertions.assertFalse(firstFeedback.getId().isEmpty(), "Tool feedback id should not be empty");
		Assertions.assertEquals("poem", firstFeedback.getName(), "Tool name should be 'poem'");
		Assertions.assertNotNull(firstFeedback.getArguments(), "Tool feedback should have arguments");
		Assertions.assertNotNull(firstFeedback.getDescription(), "Tool feedback should have description");

		// 返回中断元数据
		return interruptionMetadata;
	}

	// 构建拒绝反馈的私有方法
	private InterruptionMetadata buildRejectionFeedback(InterruptionMetadata interruptionMetadata) {
		// 使用拒绝反馈构建新的元数据
		InterruptionMetadata.Builder newBuilder = InterruptionMetadata.builder()
			.nodeId(interruptionMetadata.node())
			.state(interruptionMetadata.state());

		// 为每个工具反馈设置拒绝结果
		interruptionMetadata.toolFeedbacks().forEach(toolFeedback -> {
			InterruptionMetadata.ToolFeedback rejectedFeedback = InterruptionMetadata.ToolFeedback
				.builder(toolFeedback)
				.result(InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED)
				.description("不用使用这个工具，你自己完成写作。")
				.build();
			newBuilder.addToolFeedback(rejectedFeedback);
		});

		// 构建并返回新的中断元数据
		return newBuilder.build();
	}

	// 构建批准反馈的私有方法
	private InterruptionMetadata buildApprovalFeedback(InterruptionMetadata interruptionMetadata) {
		// 使用批准反馈构建新的元数据
		InterruptionMetadata.Builder newBuilder = InterruptionMetadata.builder()
			.nodeId(interruptionMetadata.node())
			.state(interruptionMetadata.state());

		// 为每个工具反馈设置批准结果
		interruptionMetadata.toolFeedbacks().forEach(toolFeedback -> {
			InterruptionMetadata.ToolFeedback approvedFeedback = InterruptionMetadata.ToolFeedback
				.builder(toolFeedback)
				.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED)
				.build();
			newBuilder.addToolFeedback(approvedFeedback);
		});

		// 构建并返回新的中断元数据
		return newBuilder.build();
	}

	// 构建编辑反馈的私有方法
	private InterruptionMetadata buildEditedFeedback(InterruptionMetadata interruptionMetadata) {
		// 使用编辑反馈构建新的元数据
		InterruptionMetadata.Builder newBuilder = InterruptionMetadata.builder()
			.nodeId(interruptionMetadata.node())
			.state(interruptionMetadata.state());

		// 为每个工具反馈设置编辑结果和修改后的参数
		interruptionMetadata.toolFeedbacks().forEach(toolFeedback -> {
			InterruptionMetadata.ToolFeedback editedFeedback = InterruptionMetadata.ToolFeedback
				.builder(toolFeedback)
				.arguments(toolFeedback.getArguments().replace("。\"", "。By Spring AI Alibaba\""))
				.result(InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED)
				.build();
			newBuilder.addToolFeedback(editedFeedback);
		});

		// 构建并返回新的中断元数据
		return newBuilder.build();
	}

	// 执行第二次调用的私有方法
	private void performSecondInvocation(ReactAgent agent, String threadId, InterruptionMetadata feedbackMetadata) throws Exception {
		// 使用人类反馈恢复执行
		System.out.println("\n=== Second Invocation: Resuming with REJECTED Feedback ===");
		// 创建恢复执行的配置，添加人类反馈元数据
		RunnableConfig resumeRunnableConfig = RunnableConfig.builder().threadId(threadId)
				.addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedbackMetadata)
				.build();

		try {
			// 第二次调用 - 应该恢复并完成执行
			Optional<NodeOutput> result = agent.invokeAndGetOutput("", resumeRunnableConfig);

			// 验证第二次调用成功完成
			Assertions.assertTrue(result.isPresent(), "Second invocation should return a result");
			NodeOutput finalOutput = result.get();
			Assertions.assertNotNull(finalOutput, "Final result should not be null");

			// 验证结果不是另一个中断（执行应该完成）
			Assertions.assertNotEquals(InterruptionMetadata.class, finalOutput.getClass(),
				"Final result should not be an InterruptionMetadata - execution should complete");

			System.out.println("Final result type: " + finalOutput.getClass().getSimpleName());
			System.out.println("Final result node: " + finalOutput.node());
			System.out.println("Final result state data keys: " + finalOutput.state().data().keySet());

			// 验证最终状态包含预期数据
			Assertions.assertNotNull(finalOutput.state(), "Final output should have state");
			Assertions.assertNotNull(finalOutput.state().data(), "Final output state should have data");
			Assertions.assertFalse(finalOutput.state().data().isEmpty(),
				"Final output state data should not be empty");

		} catch (java.util.concurrent.CompletionException e) {
			// 处理ReactAgent执行失败的情况
			System.err.println("ReactAgent execution failed: " + e.getMessage());
			e.printStackTrace();
			fail("ReactAgent execution failed: " + e.getMessage());
		}
	}

}
