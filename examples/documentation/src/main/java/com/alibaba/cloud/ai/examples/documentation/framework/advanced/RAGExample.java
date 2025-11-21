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
package com.alibaba.cloud.ai.examples.documentation.framework.advanced;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 检索增强生成（RAG）示例
 *
 * 演示如何使用RAG技术为LLM提供外部知识，包括：
 * 1. 构建知识库
 * 2. 两步RAG
 * 3. Agentic RAG
 * 4. 混合RAG
 *
 * 参考文档: advanced_doc/rag.md
 */
public class RAGExample {

	private final ChatModel chatModel;
	private final VectorStore vectorStore;

	public RAGExample(ChatModel chatModel, VectorStore vectorStore) {
		this.chatModel = chatModel;
		this.vectorStore = vectorStore;
	}

	/**
	 * Main方法：运行所有示例
	 *
	 * 注意：需要配置ChatModel和VectorStore实例才能运行
	 */
	public static void main(String[] args) {
		// 创建 DashScope API 实例，用于与DashScope服务通信
		// 从环境变量中获取API密钥
		DashScopeApi dashScopeApi = DashScopeApi.builder()
				.apiKey(System.getenv("AI_DASHSCOPE_API_KEY"))
				.build();

		// 创建 ChatModel 实例，使用DashScope API进行聊天模型操作
		ChatModel chatModel = DashScopeChatModel.builder()
				.dashScopeApi(dashScopeApi)
				.build();

		// TODO: 请配置您的VectorStore实例
		// 例如：VectorStore vectorStore = new YourVectorStoreImplementation();
		// 当前vectorStore未初始化，需要替换为实际的VectorStore实现
		VectorStore vectorStore = null; // 请替换为实际的VectorStore实例

		// 检查chatModel和vectorStore是否正确初始化
		if (chatModel == null || vectorStore == null) {
			// 如果任一实例为空，则输出错误信息并退出程序
			System.err.println("错误：请先配置ChatModel和VectorStore实例");
			System.err.println("请设置 AI_DASHSCOPE_API_KEY 环境变量，并配置VectorStore实例");
			return;
		}

		// 创建示例实例，传入chatModel和vectorStore
		RAGExample example = new RAGExample(chatModel, vectorStore);

		// 调用runAllExamples方法运行所有示例
		example.runAllExamples();
	}

	/**
	 * 示例1：构建知识库
	 *
	 * 从文档加载、分割、嵌入并存储到向量数据库
	 */
	public void example1_buildKnowledgeBase() {
		// 1. 加载文档，指定文档路径
		Resource resource = new FileSystemResource("path/to/document.txt");
		// 使用TextReader读取资源文件内容
		TextReader textReader = new TextReader(resource);
		// 获取文档列表
		List<Document> documents = textReader.get();

		// 2. 分割文档为块，使用TokenTextSplitter进行分词分割
		TokenTextSplitter splitter = new TokenTextSplitter();
		// 对文档应用分割器，得到分割后的文档块
		List<Document> chunks = splitter.apply(documents);

		// 3. 将块添加到向量存储中
		vectorStore.add(chunks);

		// 现在可以使用向量存储进行检索
		// 执行相似度搜索，查找与"查询文本"相关的文档
		List<Document> results = vectorStore.similaritySearch("查询文本");

		// 输出检索结果数量
		System.out.println("知识库构建完成，检索到 " + results.size() + " 个相关文档");
	}

	/**
	 * 示例2：两步RAG
	 *
	 * 检索步骤总是在生成步骤之前执行
	 */
	public void example2_twoStepRAG() {
		// 两步RAG：检索 -> 生成
		// 定义用户问题
		String userQuestion = "Spring AI Alibaba支持哪些模型？";

		// Step 1: Retrieve relevant documents
		// 从向量存储中检索与用户问题相关的文档
		List<Document> relevantDocs = vectorStore.similaritySearch(userQuestion);

		// Step 2: Build context from documents
		// 构建上下文字符串，将所有相关文档内容连接起来
		String context = relevantDocs.stream()
				.map(Document::getText)
				.collect(Collectors.joining("\n\n"));

		// Step 3: Generate answer with context
		// 创建ChatClient实例用于生成回答
		ChatClient chatClient = ChatClient.builder(chatModel).build();
		// 发送包含上下文和问题的提示，获取模型的回答
		String answer = chatClient.prompt()
				.user(u -> u.text("基于以下上下文回答问题：\n\n上下文：\n" + context + "\n\n问题：" + userQuestion))
				.call()
				.content();

		// 输出生成的答案
		System.out.println("答案: " + answer);

		// 检索到的文档作为上下文添加到提示中
		// ChatModel 使用增强的上下文生成答案

		// 输出示例执行完成信息
		System.out.println("两步RAG示例执行完成");
	}

	/**
	 * 示例3：Agentic RAG
	 *
	 * Agent决定何时以及如何检索信息
	 */
	public void example3_agenticRAG() throws Exception {
		// 创建文档检索工具
		class DocumentSearchTool {
			// 定义搜索方法，接收请求参数并返回响应结果
			public Response search(Request request) {
				// 从向量存储检索相关文档
				List<Document> docs = vectorStore.similaritySearch(request.query());

				// 合并文档内容
				String combinedContent = docs.stream()
						.map(Document::getText)
						.collect(Collectors.joining("\n\n"));

				// 返回合并后的内容作为响应
				return new Response(combinedContent);
			}

			// 定义请求记录类，包含查询字符串
			public record Request(String query) { }

			// 定义响应记录类，包含内容字符串
			public record Response(String content) { }
		}

		// 创建DocumentSearchTool实例
		DocumentSearchTool searchTool = new DocumentSearchTool();

		// 创建工具回调
		ToolCallback searchCallback = FunctionToolCallback.builder("search_documents",
						(Function<DocumentSearchTool.Request, DocumentSearchTool.Response>)
								request -> searchTool.search(request))
				.description("搜索文档以查找相关信息")
				.inputType(DocumentSearchTool.Request.class)
				.build();

		// 创建带有检索工具的Agent
		ReactAgent ragAgent = ReactAgent.builder()
				.name("rag_agent")
				.model(chatModel)
				.instruction("你是一个智能助手。当需要查找信息时，使用search_documents工具。" +
						"基于检索到的信息回答用户的问题，并引用相关片段。")
				.tools(searchCallback)
				.build();

		// Agent会自动决定何时调用检索工具
		// 调用agent处理特定问题
		ragAgent.invoke("Spring AI Alibaba支持哪些向量数据库？");

		// 输出示例执行完成信息
		System.out.println("Agentic RAG示例执行完成");
	}

	/**
	 * 示例4：多源RAG
	 *
	 * Agent可以从多个来源检索信息
	 */
	public void example4_multiSourceRAG() throws Exception {
		// 创建多个检索工具
		// 定义Web搜索工具类
		class WebSearchTool {
			// 定义搜索方法，模拟网络搜索
			public Response search(Request request) {
				// 返回模拟的网络搜索结果
				return new Response("从网络搜索到的信息: " + request.query());
			}

			// 定义请求记录类
			public record Request(String query) { }

			// 定义响应记录类
			public record Response(String content) { }
		}

		// 定义数据库查询工具类
		class DatabaseQueryTool {
			// 定义查询方法，模拟数据库查询
			public Response query(Request request) {
				// 返回模拟的数据库查询结果
				return new Response("从数据库查询到的信息: " + request.query());
			}

			// 定义请求记录类
			public record Request(String query) { }

			// 定义响应记录类
			public record Response(String content) { }
		}

		// 定义文档搜索工具类
		class DocumentSearchTool {
			// 定义搜索方法，从向量存储中检索文档
			public Response search(Request request) {
				// 从向量存储中检索相关文档
				List<Document> docs = vectorStore.similaritySearch(request.query());
				// 将文档内容合并成一个字符串
				String content = docs.stream()
						.map(Document::getText)
						.collect(Collectors.joining("\n\n"));
				// 返回合并后的内容
				return new Response(content);
			}

			// 定义请求记录类
			public record Request(String query) { }

			// 定义响应记录类
			public record Response(String content) { }
		}

		// 创建各个工具的实例
		WebSearchTool webSearchTool = new WebSearchTool();
		DatabaseQueryTool dbQueryTool = new DatabaseQueryTool();
		DocumentSearchTool docSearchTool = new DocumentSearchTool();

		// 创建Web搜索工具回调
		ToolCallback webSearchCallback = FunctionToolCallback.builder("web_search",
						(Function<WebSearchTool.Request, WebSearchTool.Response>)
								req -> webSearchTool.search(req))
				.description("搜索互联网以获取最新信息")
				.inputType(WebSearchTool.Request.class)
				.build();

		// 创建数据库查询工具回调
		ToolCallback databaseQueryCallback = FunctionToolCallback.builder("database_query",
						(Function<DatabaseQueryTool.Request, DatabaseQueryTool.Response>)
								req -> dbQueryTool.query(req))
				.description("查询内部数据库")
				.inputType(DatabaseQueryTool.Request.class)
				.build();

		// 创建文档搜索工具回调
		ToolCallback documentSearchCallback = FunctionToolCallback.builder("document_search",
						(Function<DocumentSearchTool.Request, DocumentSearchTool.Response>)
								req -> docSearchTool.search(req))
				.description("搜索文档库")
				.inputType(DocumentSearchTool.Request.class)
				.build();

		// Agent可以访问多个检索源
		// 创建多源RAG代理，可访问多种信息源
		ReactAgent multiSourceAgent = ReactAgent.builder()
				.name("multi_source_rag_agent")
				.model(chatModel)
				.instruction("你可以访问多个信息源：" +
						"1. web_search - 用于最新的互联网信息\n" +
						"2. database_query - 用于内部数据\n" +
						"3. document_search - 用于文档库\n" +
						"根据问题选择最合适的工具。")
				.tools(webSearchCallback, databaseQueryCallback, documentSearchCallback)
				.build();

		// 调用代理处理复合型问题
		multiSourceAgent.invoke("比较我们的产品文档中的功能和最新的市场趋势");

		// 输出示例执行完成信息
		System.out.println("多工具Agentic RAG示例执行完成");
	}

	/**
	 * 示例5：混合RAG
	 *
	 * 结合查询增强、检索验证和答案验证
	 */
	public void example5_hybridRAG() {
		// 定义混合RAG系统类
		class HybridRAGSystem {
			// 声明chatModel和vectorStore成员变量
			private final ChatModel chatModel;
			private final VectorStore vectorStore;

			// 构造函数，初始化chatModel和vectorStore
			public HybridRAGSystem(ChatModel chatModel, VectorStore vectorStore) {
				this.chatModel = chatModel;
				this.vectorStore = vectorStore;
			}

			// 回答方法，接受用户问题并返回答案
			public String answer(String userQuestion) {
				// 1. 查询增强，对原始问题进行优化
				String enhancedQuery = enhanceQuery(userQuestion);

				// 设置最大尝试次数
				int maxAttempts = 3;
				// 循环尝试生成满意答案
				for (int attempt = 0; attempt < maxAttempts; attempt++) {
					// 2. 检索文档
					List<Document> docs = vectorStore.similaritySearch(enhancedQuery);

					// 3. 检索验证，检查检索结果是否足够好
					if (!isRetrievalSufficient(docs)) {
						// 如果不够好，则优化查询并继续循环
						enhancedQuery = refineQuery(enhancedQuery, docs);
						continue;
					}

					// 4. 生成答案
					String answer = generateAnswer(userQuestion, docs);

					// 5. 答案验证，验证生成的答案质量
					ValidationResult validation = validateAnswer(answer, docs);
					// 如果答案有效则直接返回
					if (validation.isValid()) {
						return answer;
					}

					// 6. 根据验证结果决定下一步
					// 如果应该重试，则优化查询
					if (validation.shouldRetry()) {
						enhancedQuery = refineBasedOnValidation(enhancedQuery, validation);
					}
					// 否则返回当前最佳答案
					else {
						return answer; // 返回当前最佳答案
					}
				}

				// 如果所有尝试都失败，则返回默认消息
				return "无法生成满意的答案";
			}

			// 查询增强方法，暂时返回原查询
			private String enhanceQuery(String query) {
				return query; // 实现查询增强逻辑
			}

			// 判断检索是否充分的方法
			private boolean isRetrievalSufficient(List<Document> docs) {
				// 检查文档列表不为空且相关性得分大于0.7
				return !docs.isEmpty() && calculateRelevanceScore(docs) > 0.7;
			}

			// 计算相关性得分的方法，暂时返回固定值
			private double calculateRelevanceScore(List<Document> docs) {
				return 0.8; // 实现相关性评分逻辑
			}

			// 优化查询的方法，暂时返回原查询
			private String refineQuery(String query, List<Document> docs) {
				return query; // 实现查询优化逻辑
			}

			// 生成答案的方法
			private String generateAnswer(String question, List<Document> docs) {
				// 构建上下文字符串
				String context = docs.stream()
						.map(Document::getText)
						.collect(Collectors.joining("\n\n"));

				// 创建ChatClient实例
				ChatClient client = ChatClient.builder(chatModel).build();
				// 发送提示并获取回答
				return client.prompt()
						.system("基于以下上下文回答问题：\n" + context)
						.user(question)
						.call()
						.content();
			}

			// 验证答案的方法，暂时返回固定结果
			private ValidationResult validateAnswer(String answer, List<Document> docs) {
				// 实现答案验证逻辑
				return new ValidationResult(true, false);
			}

			// 基于验证结果优化查询的方法，暂时返回原查询
			private String refineBasedOnValidation(String query, ValidationResult validation) {
				return query; // 基于验证结果优化查询
			}

			// 定义验证结果类
			class ValidationResult {
				// 声明有效性标志和是否应重试标志
				private boolean valid;
				private boolean shouldRetry;

				// 构造函数初始化两个标志
				public ValidationResult(boolean valid, boolean shouldRetry) {
					this.valid = valid;
					this.shouldRetry = shouldRetry;
				}

				// 获取有效性状态的方法
				public boolean isValid() {
					return valid;
				}

				// 获取是否应重试状态的方法
				public boolean shouldRetry() {
					return shouldRetry;
				}
			}
		}

		// 创建HybridRAGSystem实例
		HybridRAGSystem hybridRAG = new HybridRAGSystem(chatModel, vectorStore);
		// 调用answer方法获取答案
		String answer = hybridRAG.answer("解释一下Spring AI Alibaba的核心功能");

		// 输出混合RAG的答案
		System.out.println("混合RAG答案: " + answer);
		// 输出示例执行完成信息
		System.out.println("混合RAG示例执行完成");
	}

	/**
	 * 运行所有示例
	 */
	public void runAllExamples() {
		// 输出标题信息
		System.out.println("=== 检索增强生成（RAG）示例 ===\n");

		try {
			// 输出示例1信息
			System.out.println("示例1: 构建知识库");
			// example1_buildKnowledgeBase(); // 需要实际文件路径
			System.out.println();

			// 输出示例2信息并执行
			System.out.println("示例2: 两步RAG");
			example2_twoStepRAG();
			System.out.println();

			// 输出示例3信息并执行
			System.out.println("示例3: Agentic RAG");
			example3_agenticRAG();
			System.out.println();

			// 输出示例4信息并执行
			System.out.println("示例4: 多数据源RAG");
			example4_multiSourceRAG();
			System.out.println();

			// 输出示例5信息并执行
			System.out.println("示例5: 混合RAG");
			example5_hybridRAG();
			System.out.println();

		}
		// 捕获异常并在出现错误时输出错误信息
		catch (Exception e) {
			System.err.println("执行示例时出错: " + e.getMessage());
			e.printStackTrace();
		}
	}
}

