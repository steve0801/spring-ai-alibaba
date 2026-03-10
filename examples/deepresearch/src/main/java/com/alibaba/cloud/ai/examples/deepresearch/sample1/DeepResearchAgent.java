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
package com.alibaba.cloud.ai.examples.deepresearch.sample1;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.FilesystemInterceptor;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.LargeResultEvictionInterceptor;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.PatchToolCallsInterceptor;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.SubAgentInterceptor;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.SubAgentSpec;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.contextediting.ContextEditingInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.todolist.TodoListInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static com.alibaba.cloud.ai.examples.deepresearch.sample1.DeepResearchAgent.Prompts.researchInstructions;
import static com.alibaba.cloud.ai.examples.deepresearch.sample1.DeepResearchAgent.Prompts.subCritiquePrompt;
import static com.alibaba.cloud.ai.examples.deepresearch.sample1.DeepResearchAgent.Prompts.subResearchPrompt;

public class DeepResearchAgent {
	private static final String BASE_AGENT_PROMPT =
			"In order to complete the objective that the user asks of you, " +
					"you have access to a number of standard tools.";

	private String systemPrompt;
	private ChatModel chatModel;

	// Interceptors
	private LargeResultEvictionInterceptor largeResultEvictionInterceptor;
	private FilesystemInterceptor filesystemInterceptor;
	private TodoListInterceptor todoListInterceptor;
	private PatchToolCallsInterceptor patchToolCallsInterceptor;
	private ContextEditingInterceptor contextEditingInterceptor;
	private ToolRetryInterceptor toolRetryInterceptor;

	// Hooks
	private SummarizationHook summarizationHook;
	private HumanInTheLoopHook humanInTheLoopHook;
	private ToolCallLimitHook toolCallLimitHook;
	private ShellToolAgentHook shellToolAgent;

//	private ShellTool shellTool = ShellTool.builder().build();

	public DeepResearchAgent() {
		// Create DashScopeApi instance using the API key from environment variable
		DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey("your-api-key-here").build();
		// Create DashScope ChatModel instance
		this.chatModel = DashScopeChatModel.builder().dashScopeApi(dashScopeApi).build();

		this.systemPrompt = researchInstructions + "\n\n" + BASE_AGENT_PROMPT;

		// Initialize interceptors
		this.largeResultEvictionInterceptor = LargeResultEvictionInterceptor
				.builder()
				.excludeFilesystemTools()
				.toolTokenLimitBeforeEvict(5000)
				.build();

		this.filesystemInterceptor = FilesystemInterceptor.builder()
				.readOnly(false)
				.build();

		this.todoListInterceptor = TodoListInterceptor.builder().build();

		this.patchToolCallsInterceptor = PatchToolCallsInterceptor.builder().build();

		this.toolRetryInterceptor = ToolRetryInterceptor.builder()
				.maxRetries(1).onFailure(ToolRetryInterceptor.OnFailureBehavior.RETURN_MESSAGE)
				.build();

		// Initialize hooks
		this.summarizationHook = SummarizationHook.builder()
				.model(chatModel) // should use another model for summarization
				.maxTokensBeforeSummary(120000)
				.messagesToKeep(6)
				.build();

		this.humanInTheLoopHook = HumanInTheLoopHook.builder()
				.approvalOn("search_web", "Please approve the search_web tool.")
				.build();

		this.toolCallLimitHook = ToolCallLimitHook.builder()
				.runLimit(25)
				.build();

		this.shellToolAgent = ShellToolAgentHook.builder().build();

		this.contextEditingInterceptor = ContextEditingInterceptor.builder()
				.trigger(10000)
				.clearAtLeast(6000)
				.keep(4)
				.excludeTools("write_todos")
				.build();

	}

	private static SubAgentSpec createCritiqueAgent(String subCritiquePrompt) {
		return SubAgentSpec.builder()
				.name("critique-agent")
				.description("Used to critique the final report. Provide information about " +
						"how you want the report to be critiqued.")
				.systemPrompt(subCritiquePrompt)
				.enableLoopingLog(true)
				.build();
	}

	private static SubAgentSpec createResearchAgent(List<ToolCallback> toolsFromMcp, String subResearchPrompt) {
		return SubAgentSpec.builder()
				.name("research-agent")
				.description("Used to research in-depth questions. Only give one topic at a time. " +
						"Break down large topics into components and call multiple research agents " +
						"in parallel for each sub-question.")
				.systemPrompt(subResearchPrompt)
				.tools(toolsFromMcp)
				.enableLoopingLog(true)
				.build();
	}

	public ReactAgent getResearchAgent(List<ToolCallback> toolsFromMcp) {
		// Build the ReactAgent with all interceptors
		return ReactAgent.builder()
				.name("DeepResearchAgent")
				.model(chatModel)
				.tools(toolsFromMcp)
				.systemPrompt(systemPrompt)
				.enableLogging(true)
				.interceptors(todoListInterceptor,
						filesystemInterceptor,
						largeResultEvictionInterceptor,
						patchToolCallsInterceptor,
//						contextEditingInterceptor,
						toolRetryInterceptor,
						subAgentAsInterceptors(toolsFromMcp))
				.hooks(humanInTheLoopHook, summarizationHook, toolCallLimitHook)
				.saver(new MemorySaver())
				.build();

	}

	private Interceptor subAgentAsInterceptors(List<ToolCallback> toolsFromMcp) {
		SubAgentSpec researchAgent = createResearchAgent(toolsFromMcp, subResearchPrompt);
		SubAgentSpec critiqueAgent = createCritiqueAgent(subCritiquePrompt);

		SubAgentInterceptor.Builder subAgentBuilder = SubAgentInterceptor.builder()
				.defaultModel(chatModel)
				.defaultInterceptors(
						todoListInterceptor,
						filesystemInterceptor,
//						contextEditingInterceptor,
						patchToolCallsInterceptor,
						largeResultEvictionInterceptor
				)
				.defaultHooks(summarizationHook, toolCallLimitHook)
				.addSubAgent(researchAgent)
				.includeGeneralPurpose(true)
				.addSubAgent(critiqueAgent);
		return subAgentBuilder.build();
	}

	public static class Prompts {
		// Main research instructions
		//public static String researchInstructions = """
		//		You are an expert researcher. Your job is to conduct thorough research and write a polished report.
		//
		//		**Workflow:**
		//		1. First, write the original user question to `question.txt` for reference
		//		2. Use the research-agent to conduct deep research on sub-topics
		//		   - Break down complex topics into specific sub-questions
		//		   - Call multiple research agents in parallel for independent sub-questions
		//		3. When you have enough information, write the final report to `final_report.md`
		//		4. Call the critique-agent to get feedback on the report
		//		5. Iterate: Do more research and edit `final_report.md` based on critique
		//		6. Repeat steps 4-5 until satisfied with the quality
		//
		//		**Report Format Requirements:**
		//		- CRITICAL: Write in the SAME language as the user's question!
		//		- Use clear Markdown with proper structure (# for title, ## for sections, ### for subsections)
		//		- Include specific facts and insights from research
		//		- Reference sources using [Title](URL) format
		//		- Provide balanced, thorough analysis
		//		- Be comprehensive - users expect detailed, in-depth answers
		//		- End with a "### Sources" section listing all references
		//
		//		**Citation Rules:**
		//		- Assign each unique URL a single citation number [1], [2], etc.
		//		- Number sources sequentially without gaps in the final list
		//		- Each source should be a separate list item
		//		- Format: [1] Source Title: URL
		//
		//		Structure your report appropriately for the question type:
		//		- Comparison: intro → overview A → overview B → comparison → conclusion
		//		- List: Simple numbered/bulleted list or separate sections per item
		//		- Overview/Summary: intro → concept 1 → concept 2 → ... → conclusion
		//		- Analysis: thesis → evidence → analysis → conclusion
		//		""";

		public static String researchInstructions = """
		你是一位专家研究员。你的工作是进行深入研究并撰写一份精炼的报告。
		
		**工作流程：**
		1. 首先，将原始用户问题写入 `question.txt` 作为参考
		2. 使用 research-agent 对子主题进行深入研究
		   - 将复杂主题分解为具体的子问题
		   - 为独立的子问题并行调用多个研究代理
		3. 当你获得足够信息时，将最终报告写入 `final_report.md`
		4. 调用 critique-agent 获取对报告的反馈
		5. 迭代：根据批评意见进行更多研究并编辑 `final_report.md`
		6. 重复步骤 4-5 直到对质量满意
		
		**报告格式要求：**
		- 关键：使用与用户问题**相同**的语言！
		- 使用清晰的 Markdown 并采用适当的结构（# 用于标题，## 用于章节，### 用于小节）
		- 包含研究中的具体事实和见解
		- 使用 [Title](URL) 格式引用来源
		- 提供平衡、全面的分析
		- 内容要全面 - 用户期望详细、深入的答案
		- 以"### 来源"章节结束，列出所有参考文献
		
		**引用规则：**
		- 为每个唯一的 URL 分配一个单独的引用编号 [1]、[2] 等。
		- 按顺序编号来源，在最终列表中不要有遗漏
		- 每个来源应为单独的列表项
		- 格式：[1] 来源标题：URL
		
		根据问题类型适当组织你的报告：
		- 比较：引言 → A概览 → B概览 → 比较 → 结论
		- 列表：简单的编号/项目符号列表或每项的独立章节
		- 概述/摘要：引言 → 概念1 → 概念2 → ... → 结论
		- 分析：论题 → 证据 → 分析 → 结论
		""";


		// Sub-agent prompt for research
		//public static String subResearchPrompt = """
		//		You are a dedicated researcher. Your job is to conduct research based on the user's questions.
		//
		//		Conduct thorough research and then reply to the user with a detailed answer to their question.
		//
		//		IMPORTANT: Only your FINAL answer will be passed on to the user. They will have NO knowledge
		//		of anything except your final message, so your final report should be comprehensive and self-contained!
		//		""";
		public static String subResearchPrompt = """
		你是一名专业的研究员。你的工作是基于用户的问题进行研究。
		
		进行深入的研究，然后给用户提供详细的问题答案。
		
		重要：只有你的最终答案会被传递给用户。他们除了你的最后一条消息外，
		不会知道任何其他内容，所以你的最终报告应该是全面且自成一体的！
		""";


		// Sub-agent prompt for critique
		//public static String subCritiquePrompt = """
		//		You are a dedicated editor. You are being tasked to critique a report.
		//
		//		You can find the report at `final_report.md`.
		//		You can find the question/topic for this report at `question.txt`.
		//
		//		The user may ask for specific areas to critique the report in.
		//		Respond with a detailed critique of the report. Focus on areas that could be improved.
		//
		//		You can use the search tool to search for information, if that will help you critique the report.
		//
		//		Do not write to the `final_report.md` yourself.
		//
		//		Things to check:
		//		- Each section is appropriately named and structured
		//		- The report is written in essay/textbook style - text heavy, not just bullet points
		//		- The report is comprehensive without missing important details
		//		- The article covers key areas ensuring overall understanding
		//		- The article deeply analyzes causes, impacts, and trends with valuable insights
		//		- The article closely follows the research topic and directly answers questions
		//		- The article has clear structure, fluent language, and is easy to understand
		//		""";
		public static String subCritiquePrompt = """
		你是一名专业的编辑。你的任务是批判一份报告。
		
		你可以在 `final_report.md` 中找到报告。
		你可以在 `question.txt` 中找到这个报告的问题/主题。
		
		用户可能会要求你针对特定方面批判报告。
		对报告进行详细的批判。重点关注可以改进的地方。
		
		如果有助于批判报告，你可以使用搜索工具搜索信息。
		
		不要自己写入 `final_report.md`。
		
		检查要点：
		- 每个部分都有合适的命名和结构
		- 报告以论文/教科书风格书写 - 以文字为主，不只是要点
		- 报告内容全面，没有遗漏重要细节
		- 文章涵盖了关键领域，确保整体理解
		- 文章深入分析原因、影响和趋势，提供有价值的见解
		- 文章紧密围绕研究主题，直接回答问题
		- 文章结构清晰，语言流畅，易于理解
		""";

	}
}
