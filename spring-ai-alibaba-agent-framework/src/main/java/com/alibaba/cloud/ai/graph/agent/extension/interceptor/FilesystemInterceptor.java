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
import com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem.*;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Filesystem interceptor that provides file system management capabilities to agents.
 *
 * This interceptor adds filesystem tools to the agent: ls, read_file, write_file,
 * edit_file, glob, and grep. It enhances the system prompt to guide agents on using
 * these filesystem operations effectively.
 *
 * Key Features:
 * - Pluggable backend system for file storage (local, state-based, composite)
 * - Path validation and security (prevents directory traversal)
 * - Custom tool descriptions support
 * - File metadata tracking (creation/modification timestamps)
 *
 * Note: Large result eviction has been moved to {@link LargeResultEvictionInterceptor}.
 * To enable automatic eviction of large tool results, use both interceptors together.
 *
 * The interceptor automatically:
 * - Injects filesystem tools (ls, read_file, write_file, edit_file, glob, grep)
 * - Provides guidance on proper file path usage (absolute paths required)
 * - Helps agents explore and modify file systems systematically
 *
 * Example:
 * <pre>
 * FilesystemInterceptor interceptor = FilesystemInterceptor.builder()
 *     .readOnly(false)
 *     .build();
 * </pre>
 *
 * For automatic large result eviction:
 * <pre>
 * FilesystemInterceptor fsInterceptor = FilesystemInterceptor.builder()
 *     .build();
 *
 * LargeResultEvictionInterceptor evictionInterceptor =
 *     LargeResultEvictionInterceptor.builder()
 *         .toolTokenLimitBeforeEvict(20000)
 *         .excludeFilesystemTools()
 *         .build();
 *
 * // Use both interceptors in your agent
 * </pre>
 */
public class FilesystemInterceptor extends ModelInterceptor {

	// Constants
	private static final String EMPTY_CONTENT_WARNING = "System reminder: File exists but has empty contents";
	private static final int DEFAULT_READ_OFFSET = 0;
	private static final int DEFAULT_READ_LIMIT = 500;

	private static final String DEFAULT_SYSTEM_PROMPT = """
			## Filesystem Tools `ls`, `read_file`, `write_file`, `edit_file`, `glob`, `grep`
			
			You have access to a filesystem which you can interact with using these tools.
			All file paths must start with a /.
			Avoid using the root path because you might not have permission to read/write there.
			
			- ls: list files in a directory (requires absolute path)
			- read_file: read a file from the filesystem
			- write_file: write to a file in the filesystem
			- edit_file: edit a file in the filesystem
			- glob: find files matching a pattern (e.g., "**/*.py")
			- grep: search for text within files
			""";

	private final List<ToolCallback> tools;
	private final String systemPrompt;
	private final boolean readOnly;
	private final Map<String, String> customToolDescriptions;
	// 用于检测目录遍历的正则表达式模式
	private static final Pattern TRAVERSAL_PATTERN = Pattern.compile("\\.\\.|~");

	private FilesystemInterceptor(Builder builder) {
		// 初始化只读模式标志
		this.readOnly = builder.readOnly;
		// 初始化系统提示信息，如果没有自定义则使用默认提示
		this.systemPrompt = builder.systemPrompt != null ? builder.systemPrompt : DEFAULT_SYSTEM_PROMPT;
		// 初始化自定义工具描述映射
		this.customToolDescriptions = builder.customToolDescriptions != null
			? new HashMap<>(builder.customToolDescriptions)
			: new HashMap<>();

		// 使用工厂方法创建文件系统工具，支持自定义或默认描述
		List<ToolCallback> toolList = new ArrayList<>();
		// 添加列出文件工具
		toolList.add(ListFilesTool.createListFilesToolCallback(
			customToolDescriptions.getOrDefault("ls", ListFilesTool.DESCRIPTION)
		));
		// 添加读取文件工具
		toolList.add(ReadFileTool.createReadFileToolCallback(
			customToolDescriptions.getOrDefault("read_file", ReadFileTool.DESCRIPTION)
		));

		// 如果不是只读模式，添加写入和编辑文件工具
		if (!readOnly) {
			// 添加写入文件工具
			toolList.add(WriteFileTool.createWriteFileToolCallback(
				customToolDescriptions.getOrDefault("write_file", WriteFileTool.DESCRIPTION)
			));
			// 添加编辑文件工具
			toolList.add(EditFileTool.createEditFileToolCallback(
				customToolDescriptions.getOrDefault("edit_file", EditFileTool.DESCRIPTION)
			));
		}

		// 添加全局搜索工具
		toolList.add(GlobTool.createGlobToolCallback(
			customToolDescriptions.getOrDefault("glob", GlobTool.DESCRIPTION)
		));
		// 添加文本搜索工具
		toolList.add(GrepTool.createGrepToolCallback(
			customToolDescriptions.getOrDefault("grep", GrepTool.DESCRIPTION)
		));

		// 将工具列表设为不可变
		this.tools = Collections.unmodifiableList(toolList);
	}

	// 构建器工厂方法
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Validate and normalize file path for security.
	 * Prevents directory traversal attacks by checking for ".." and "~".
	 *
	 * @param path The path to validate
	 * @param allowedPrefixes Optional list of allowed path prefixes
	 * @return Normalized canonical path
	 * @throws IllegalArgumentException if path is invalid
	 */
	public static String validatePath(String path, List<String> allowedPrefixes) {
		// 检查路径是否包含目录遍历字符
		if (TRAVERSAL_PATTERN.matcher(path).find()) {
			throw new IllegalArgumentException("Path traversal not allowed: " + path);
		}

		// 规范化路径
		String normalized = path.replace("\\", "/");
		normalized = Paths.get(normalized).normalize().toString().replace("\\", "/");

		// 确保路径以"/"开头
		if (!normalized.startsWith("/")) {
			normalized = "/" + normalized;
		}

		// 如果指定了允许的前缀，检查路径是否符合要求
		if (allowedPrefixes != null && !allowedPrefixes.isEmpty()) {
			boolean hasValidPrefix = false;
			// 遍历所有允许的前缀
			for (String prefix : allowedPrefixes) {
				if (normalized.startsWith(prefix)) {
					hasValidPrefix = true;
					break;
				}
			}
			// 如果没有匹配的前缀，抛出异常
			if (!hasValidPrefix) {
				throw new IllegalArgumentException(
					"Path must start with one of " + allowedPrefixes + ": " + path
				);
			}
		}

		// 返回规范化的路径
		return normalized;
	}

	// 获取工具列表
	@Override
	public List<ToolCallback> getTools() {
		return tools;
	}

	// 获取拦截器名称
	@Override
	public String getName() {
		return "Filesystem";
	}

	// 模型拦截处理方法
	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		SystemMessage enhancedSystemMessage;

		// 如果原始请求没有系统消息，则使用文件系统提示
		if (request.getSystemMessage() == null) {
			enhancedSystemMessage = new SystemMessage(this.systemPrompt);
		} else {
			// 否则将文件系统提示追加到现有系统消息后
			enhancedSystemMessage = new SystemMessage(request.getSystemMessage().getText() + "\n\n" + systemPrompt);
		}

		// 创建增强的请求
		ModelRequest enhancedRequest = ModelRequest.builder(request)
				.systemMessage(enhancedSystemMessage)
				.build();

		// 使用增强的请求调用处理器
		return handler.call(enhancedRequest);
	}

	/**
	 * Builder for FilesystemInterceptor with comprehensive configuration options.
	 *
	 * Note: Token limit and large result eviction features have been moved to
	 * {@link LargeResultEvictionInterceptor}. Use both interceptors together if needed.
	 */
	public static class Builder {
		private String systemPrompt;
		private boolean readOnly = false;
		private Map<String, String> customToolDescriptions;
		private FilesystemBackend backend;

		/**
		 * Set custom system prompt to guide filesystem usage.
		 * Set to null to disable system prompt injection.
		 */
		public Builder systemPrompt(String systemPrompt) {
			// 设置自定义系统提示
			this.systemPrompt = systemPrompt;
			return this;
		}

		/**
		 * Set whether the filesystem should be read-only.
		 * When true, write_file and edit_file tools are not provided.
		 * Default: false
		 */
		public Builder readOnly(boolean readOnly) {
			// 设置只读模式
			this.readOnly = readOnly;
			return this;
		}

		/**
		 * Set custom tool descriptions to override defaults.
		 * Map keys should be tool names: "ls", "read_file", "write_file",
		 * "edit_file", "glob", "grep".
		 *
		 * Example:
		 * <pre>
		 * Map&lt;String, String&gt; customDescs = Map.of(
		 *     "read_file", "Custom read file description",
		 *     "write_file", "Custom write file description"
		 * );
		 * builder.customToolDescriptions(customDescs);
		 * </pre>
		 *
		 */
		public Builder customToolDescriptions(Map<String, String> customToolDescriptions) {
			// 设置自定义工具描述
			this.customToolDescriptions = customToolDescriptions;
			return this;
		}

		/**
		 * Add a single custom tool description.
		 * Convenience method for adding one description at a time.
		 *
		 * @param toolName Name of the tool ("ls", "read_file", etc.)
		 * @param description Custom description for the tool
		 */
		public Builder addCustomToolDescription(String toolName, String description) {
			// 如果自定义工具描述映射为空，则初始化
			if (this.customToolDescriptions == null) {
				this.customToolDescriptions = new HashMap<>();
			}
			// 添加单个工具的自定义描述
			this.customToolDescriptions.put(toolName, description);
			return this;
		}

		// 构建FilesystemInterceptor实例
		public FilesystemInterceptor build() {
			return new FilesystemInterceptor(this);
		}
	}

}

