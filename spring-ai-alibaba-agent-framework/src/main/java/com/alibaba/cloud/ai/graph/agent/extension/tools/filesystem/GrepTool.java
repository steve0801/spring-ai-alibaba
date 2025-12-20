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
package com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Tool for searching text patterns in files.
 */
public class GrepTool implements BiFunction<GrepTool.GrepRequest, ToolContext, String> {

	public static final String DESCRIPTION = """
			Search for a pattern in files.
			
			Usage:
			- The pattern parameter is the text to search for (literal string, not regex)
			- The path parameter filters which directory to search in
			- The glob parameter accepts a glob pattern to filter which files to search
			
			Examples:
			- Search all files: `grep(pattern="TODO")`
			- The search is case-sensitive by default.
			""";

	// 默认构造函数
	public GrepTool() {
	}

	// 实现BiFunction接口的apply方法，执行文件内容搜索操作
	@Override
	public String apply(GrepRequest request, ToolContext toolContext) {
		try {
			// 确定搜索路径，如果请求中指定了路径则使用指定路径，否则使用当前工作目录
			Path searchPath = request.path != null ?
					Paths.get(request.path) :
					Paths.get(System.getProperty("user.dir"));

			// 创建结果列表
			List<String> results = new ArrayList<>();
			// 创建glob模式匹配器，如果请求中指定了glob模式则创建匹配器，否则为null
			PathMatcher globMatcher = request.glob != null ?
					FileSystems.getDefault().getPathMatcher("glob:" + request.glob) : null;

			// 遍历搜索路径下的所有文件
			Files.walk(searchPath)
					// 过滤出普通文件
					.filter(Files::isRegularFile)
					// 根据glob模式过滤文件
					.filter(path -> globMatcher == null || globMatcher.matches(path.getFileName()))
					// 对每个文件执行搜索操作
					.forEach(path -> {
						try {
							// 读取文件所有行
							List<String> lines = Files.readAllLines(path);
							// 遍历每一行
							for (int i = 0; i < lines.size(); i++) {
								// 检查当前行是否包含搜索模式
								if (lines.get(i).contains(request.pattern)) {
									// 根据输出模式生成结果
									String result = switch (request.outputMode) {
										// 只返回包含匹配的文件名
										case "files_with_matches" -> path.toString();
										// 返回匹配的文件名、行号和内容
										case "content" -> path + ":" + (i + 1) + ": " + lines.get(i);
										// 返回匹配的文件名和匹配标识
										case "count" -> path + ": matched";
										// 默认返回文件名
										default -> path.toString();
									};
									// 将结果添加到结果列表
									results.add(result);
									// 如果是文件名模式，只需要添加一次文件名即可
									if ("files_with_matches".equals(request.outputMode)) {
										break; // Only need file name once
									}
								}
							}
						}
						catch (IOException e) {
							// 跳过无法读取的文件
						}
					});

			// 如果没有找到匹配项，返回提示信息
			if (results.isEmpty()) {
				return "No matches found for pattern: " + request.pattern;
			}

			// 将结果用换行符连接后返回
			return String.join("\n", results);
		}
		catch (IOException e) {
			// 处理IO异常，返回错误信息
			return "Error searching files: " + e.getMessage();
		}
	}

	// 创建GrepToolCallback的工厂方法
	public static ToolCallback createGrepToolCallback(String description) {
		return FunctionToolCallback.builder("grep", new GrepTool())
				.description(description)
				.inputType(GrepRequest.class)
				.build();
	}

	/**
	 * Request structure for grep search.
	 */
	// grep搜索请求的数据结构
	public static class GrepRequest {

		// 搜索模式属性，必需
		@JsonProperty(required = true)
		@JsonPropertyDescription("The text pattern to search for")
		public String pattern;

		// 搜索路径属性，可选
		@JsonProperty(value = "path")
		@JsonPropertyDescription("The directory path to search in (default: base path)")
		public String path;

		// glob模式属性，可选
		@JsonProperty(value = "glob")
		@JsonPropertyDescription("File pattern to filter which files to search (e.g., '*.java')")
		public String glob;

		// 输出模式属性，默认为"files_with_matches"
		@JsonProperty(value = "output_mode")
		@JsonPropertyDescription("Output format: 'files_with_matches', 'content', or 'count' (default: 'files_with_matches')")
		public String outputMode = "files_with_matches";

		// 默认构造函数
		public GrepRequest() {
		}

		// 带参数的构造函数
		public GrepRequest(String pattern, String path, String glob, String outputMode) {
			this.pattern = pattern;
			this.path = path;
			this.glob = glob;
			this.outputMode = outputMode;
		}
	}
}
