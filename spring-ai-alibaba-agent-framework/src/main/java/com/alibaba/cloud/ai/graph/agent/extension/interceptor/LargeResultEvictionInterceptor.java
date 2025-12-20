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
package com.alibaba.cloud.ai.graph.agent.extension.interceptor;

import com.alibaba.cloud.ai.graph.agent.extension.file.FilesystemBackend;
import com.alibaba.cloud.ai.graph.agent.extension.file.LocalFilesystemBackend;
import com.alibaba.cloud.ai.graph.agent.extension.file.WriteResult;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;

import java.util.*;

/**
 * Tool interceptor that automatically evicts large tool results to the filesystem.
 *
 * This interceptor monitors tool call responses and when they exceed a configurable
 * token limit, it automatically saves the full result to a file and returns a truncated
 * message with a pointer to the file location.
 *
 * Key Features (from Python's FilesystemMiddleware.wrap_tool_call):
 * - Automatic detection of large results (>20000 tokens by default)
 * - Eviction to /large_tool_results/ directory
 * - Content sample (first 10 lines) included in response
 * - Exclusion of filesystem tools (they handle their own results)
 * - Tool call ID sanitization for safe file paths
 *
 * Example:
 * <pre>
 * LargeResultEvictionInterceptor interceptor = LargeResultEvictionInterceptor.builder()
 *     .toolTokenLimitBeforeEvict(20000)
 *     .excludeTools(Set.of("ls", "read_file", "write_file"))
 *     .build();
 * </pre>
 */
public class LargeResultEvictionInterceptor extends ToolInterceptor {

	// Constants from Python implementation
	private static final int MAX_LINE_LENGTH = 2000;
	private static final int LINE_NUMBER_WIDTH = 6;
	private static final int DEFAULT_TOOL_TOKEN_LIMIT = 20000;
	private static final int SAMPLE_LINES_COUNT = 10;
	private static final int SAMPLE_LINE_MAX_LENGTH = 1000;
	// 大结果存储目录路径
	private static final String LARGE_RESULTS_DIR = System.getProperty("user.dir") + "/large_tool_results/";

	// 工具结果过大时的提示消息模板
	private static final String TOO_LARGE_TOOL_MSG = """
			Tool result too large, the result of this tool call %s was saved in the filesystem at this path: %s
			You can read the result from the filesystem by using the read_file tool, but make sure to only read part of the result at a time.
			You can do this by specifying an offset and limit in the read_file tool call.
			For example, to read the first 100 lines, you can use the read_file tool with offset=0 and limit=100.
			
			Here are the first 10 lines of the result:
			%s
			""";

	// 工具调用结果令牌限制阈值
	private final Integer toolTokenLimitBeforeEvict;
	// 被排除不进行结果驱逐的工具集合
	private final Set<String> excludedTools;
	// 文件系统后端，用于存储大结果
	private final FilesystemBackend backend;

	private LargeResultEvictionInterceptor(Builder builder) {
		// 初始化工具令牌限制
		this.toolTokenLimitBeforeEvict = builder.toolTokenLimitBeforeEvict;
		// 初始化被排除的工具集合
		this.excludedTools = builder.excludedTools != null
			? new HashSet<>(builder.excludedTools)
			: new HashSet<>();
		// 初始化文件系统后端
		this.backend = builder.backend;
	}

	// 构建器工厂方法
	public static Builder builder() {
		return new Builder();
	}

	// 获取拦截器名称
	@Override
	public String getName() {
		return "LargeResultEviction";
	}

	// 工具调用拦截处理方法
	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		// 执行工具调用
		ToolCallResponse response = handler.call(request);

		// 检查是否应该驱逐结果
		if (!shouldEvictResult(request.getToolName(), response.getResult())) {
			return response;
		}

		// 处理大结果并返回修改后的响应
		return processLargeResult(response, request.getToolCallId());
	}

	/**
	 * Determine if a tool result should be evicted to filesystem.
	 *
	 * @param toolName The name of the tool
	 * @param result The tool result content
	 * @return true if the result should be evicted
	 */
	private boolean shouldEvictResult(String toolName, String result) {
		// 如果功能被禁用则不驱逐
		if (toolTokenLimitBeforeEvict == null) {
			return false;
		}

		// 不驱逐被排除的工具（例如文件系统工具）
		if (excludedTools.contains(toolName)) {
			return false;
		}

		// 如果结果为null或空则不驱逐
		if (result == null || result.isEmpty()) {
			return false;
		}

		// 检查内容是否超过令牌限制（近似：4个字符约等于1个令牌）
		return result.length() > 4 * toolTokenLimitBeforeEvict;
	}

	/**
	 * Process large tool result by evicting to filesystem.
	 *
	 * @param response Original tool response
	 * @param toolCallId Tool call ID
	 * @return Modified response with pointer to file
	 */
	private ToolCallResponse processLargeResult(ToolCallResponse response, String toolCallId) {
		// 获取响应内容
		String content = response.getResult();

		// 清理工具调用ID以确保文件路径安全
		String sanitizedId = sanitizeToolCallId(toolCallId);
		// 构造文件路径
		String filePath = LARGE_RESULTS_DIR + sanitizedId;

		// 通过后端将内容写入文件系统
		if (backend != null) {
			WriteResult writeResult = backend.write(filePath, content);
			if (writeResult.getError() != null) {
				// 记录警告但继续执行驱逐消息
				System.err.println("Warning: Failed to write large result to filesystem: " + writeResult.getError());
			}
		} else {
			// 后端未配置时记录警告
			System.err.println("Warning: Backend not configured, large result not persisted to filesystem");
		}

		// 提取前N行作为样本
		String contentSample = extractContentSample(content);

		// 创建指向文件的修改消息
		String evictedMessage = String.format(TOO_LARGE_TOOL_MSG,
			toolCallId, filePath, contentSample);

		// 返回修改后的响应
		return ToolCallResponse.builder()
			.content(evictedMessage)
			.toolName(response.getToolName())
			.toolCallId(response.getToolCallId())
			.status("evicted_to_filesystem")
			.build();
	}

	/**
	 * Sanitize tool call ID for use in file paths.
	 * Removes non-alphanumeric characters except underscore and hyphen.
	 * Equivalent to Python's sanitize_tool_call_id.
	 *
	 * @param toolCallId Original tool call ID
	 * @return Sanitized ID safe for file paths
	 */
	private static String sanitizeToolCallId(String toolCallId) {
		// 如果工具调用ID为null，返回默认值
		if (toolCallId == null) {
			return "unknown";
		}
		// 使用正则表达式清理ID，只保留字母数字字符、下划线和连字符
		return toolCallId.replaceAll("[^a-zA-Z0-9_-]", "_");
	}

	/**
	 * Extract a sample of content (first N lines, truncated).
	 * Equivalent to Python's format_content_with_line_numbers.
	 *
	 * @param content Full content
	 * @return Formatted sample with line numbers
	 */
	private static String extractContentSample(String content) {
		// 按行分割内容
		String[] lines = content.split("\n");
		// 创建样本行列表
		List<String> sampleLines = new ArrayList<>();

		// 提取前SAMPLE_LINES_COUNT行或所有行（取较小值）
		for (int i = 0; i < Math.min(SAMPLE_LINES_COUNT, lines.length); i++) {
			String line = lines[i];
			// 截断过长的行
			if (line.length() > SAMPLE_LINE_MAX_LENGTH) {
				line = line.substring(0, SAMPLE_LINE_MAX_LENGTH) + "... (truncated)";
			}
			sampleLines.add(line);
		}

		// 格式化带行号的样本内容
		return formatContentWithLineNumbers(sampleLines, 1);
	}

	/**
	 * Format content with line numbers.
	 *
	 * @param lines Lines to format
	 * @param startLine Starting line number (1-based)
	 * @return Formatted content with line numbers
	 */
	private static String formatContentWithLineNumbers(List<String> lines, int startLine) {
		// 创建结果字符串构建器
		StringBuilder result = new StringBuilder();
		// 遍历所有行
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			// 截断显示过长的行
			if (line.length() > MAX_LINE_LENGTH) {
				line = line.substring(0, MAX_LINE_LENGTH) + "... (line truncated)";
			}
			// 添加带行号的行
			result.append(String.format("%6d\t%s\n", startLine + i, line));
		}
		// 返回格式化结果
		return result.toString();
	}

	/**
	 * Builder for LargeResultEvictionInterceptor.
	 */
	public static class Builder {
		// 工具令牌限制，默认为20000
		private Integer toolTokenLimitBeforeEvict = DEFAULT_TOOL_TOKEN_LIMIT;
		// 被排除的工具集合
		private Set<String> excludedTools;
		// 文件系统后端
		private FilesystemBackend backend;

		/**
		 * Set token limit before evicting tool results to filesystem.
		 * When a tool result exceeds this limit (in tokens, approximated as chars/4),
		 * it will be automatically saved to /large_tool_results/ and the agent
		 * will receive a pointer to the file instead of the full content.
		 *
		 * Set to null to disable automatic eviction.
		 * Default: 20000 tokens
		 *
		 * Equivalent to Python's tool_token_limit_before_evict parameter.
		 */
		public Builder toolTokenLimitBeforeEvict(Integer toolTokenLimitBeforeEvict) {
			// 设置工具令牌限制
			this.toolTokenLimitBeforeEvict = toolTokenLimitBeforeEvict;
			return this;
		}

		/**
		 * Set tools to exclude from eviction.
		 * These tools will never have their results evicted, even if they exceed
		 * the token limit. Useful for filesystem tools that already manage large
		 * content efficiently.
		 *
		 * Default: Empty set (no exclusions)
		 *
		 * Example:
		 * <pre>
		 * builder.excludeTools(Set.of("ls", "read_file", "write_file",
		 *                             "edit_file", "glob", "grep"));
		 * </pre>
		 */
		public Builder excludeTools(Set<String> excludedTools) {
			// 设置被排除的工具
			this.excludedTools = excludedTools;
			return this;
		}

		/**
		 * Add a single tool to the exclusion list.
		 * Convenience method for excluding tools one at a time.
		 *
		 * @param toolName Name of tool to exclude
		 */
		public Builder excludeTool(String toolName) {
			// 如果排除工具集合为空，则初始化
			if (this.excludedTools == null) {
				this.excludedTools = new HashSet<>();
			}
			// 添加工具到排除列表
			this.excludedTools.add(toolName);
			return this;
		}

		/**
		 * Set custom backend for file storage operations.
		 * The backend is used to write large results to persistent storage.
		 *
		 * NOTE: Backend implementation is optional. If not provided, eviction
		 * will still work by replacing the result text, but the full content
		 * won't be persisted to storage.
		 */
		public Builder backend(FilesystemBackend backend) {
			// 设置自定义后端
			this.backend = backend;
			return this;
		}

		/**
		 * Convenience method to automatically exclude standard filesystem tools.
		 * Excludes: ls, read_file, write_file, edit_file, glob, grep
		 */
		public Builder excludeFilesystemTools() {
			// 排除标准文件系统工具
			return excludeTools(Set.of("ls", "read_file", "write_file",
				"edit_file", "glob", "grep"));
		}

		// 构建LargeResultEvictionInterceptor实例
		public LargeResultEvictionInterceptor build() {
			// 如果未提供后端且启用了驱逐功能，则自动创建默认的LocalFilesystemBackend
			if (this.backend == null && this.toolTokenLimitBeforeEvict != null) {
				this.backend = new LocalFilesystemBackend(null, false, 10);
			}
			return new LargeResultEvictionInterceptor(this);
		}
	}
}

