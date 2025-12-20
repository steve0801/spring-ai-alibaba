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
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Tool for finding files matching a glob pattern.
 */
public class GlobTool implements BiFunction<String, ToolContext, String> {

	public static final String DESCRIPTION = """
			Find files matching a glob pattern.
			
			Usage:
			- Supports standard glob patterns: `*` (any characters), `**` (any directories), `?` (single character)
			- Returns a list of absolute file paths that match the pattern
			
			Examples:
			- `**/*.java` - Find all Java files
			- `*.txt` - Find all text files in root
			- `/src/**/*.xml` - Find all XML files under /src
			""";

	// 默认构造函数
	public GlobTool() {
	}

	// 实现BiFunction接口的apply方法，根据glob模式查找匹配的文件
	@Override
	public String apply(
			@ToolParam(description = "The glob pattern to match files") String pattern,
			ToolContext toolContext) {
		try {
			// 获取当前工作目录作为基础路径
			Path basePathObj = Paths.get(System.getProperty("user.dir"));
			// 创建路径匹配器，用于匹配glob模式
			PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

			// 创建存储匹配文件的列表
			List<String> matchedFiles = new ArrayList<>();

			// 遍历基础路径下的所有文件
			Files.walk(basePathObj)
					// 过滤出普通文件
					.filter(Files::isRegularFile)
					// 过滤出匹配glob模式的文件
					.filter(path -> {
						// 计算相对于基础路径的相对路径
						Path relativePath = basePathObj.relativize(path);
						// 检查相对路径或绝对路径是否匹配模式
						return matcher.matches(relativePath) || matcher.matches(path);
					})
					// 将匹配的文件路径添加到列表中
					.forEach(path -> matchedFiles.add(path.toString()));

			// 如果没有找到匹配的文件，返回提示信息
			if (matchedFiles.isEmpty()) {
				return "No files found matching pattern: " + pattern;
			}

			// 将匹配的文件路径用换行符连接后返回
			return String.join("\n", matchedFiles);
		}
		catch (IOException e) {
			// 处理IO异常，返回错误信息
			return "Error searching for files: " + e.getMessage();
		}
	}

	// 创建GlobToolCallback的工厂方法
	public static ToolCallback createGlobToolCallback(String description) {
		return FunctionToolCallback.builder("glob", new GlobTool())
				.description(description)
				.inputType(String.class)
				.build();
	}
}
