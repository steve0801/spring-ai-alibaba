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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Tool for listing files in a directory.
 */
public class ListFilesTool implements BiFunction<String, ToolContext, String> {

	public static final String DESCRIPTION = """
			Lists all files in the filesystem, filtering by directory.
			
			Usage:
			- The path parameter must be an absolute path, not a relative path
			- The list_files tool will return a list of all files in the specified directory.
			- This is very useful for exploring the file system and finding the right file to read or edit.
			- You should almost ALWAYS use this tool before using the Read or Edit tools.
			""";

	// 默认构造函数
	public ListFilesTool() {
	}

	// 实现BiFunction接口的apply方法，用于列出指定目录下的文件
	@Override
	public String apply(
			@ToolParam(description = "The directory path to list files from") String path,
			ToolContext toolContext) {
		// 从参数中解析路径
		File dir = new File(path);
		// 检查目录是否存在且确实是一个目录
		if (!dir.exists() || !dir.isDirectory()) {
			return "Error: Directory not found: " + path;
		}

		// 获取目录下的所有文件
		File[] files = dir.listFiles();
		// 检查是否能读取目录内容
		if (files == null) {
			return "Error: Cannot read directory: " + path;
		}

		// 创建文件路径列表
		List<String> filePaths = new ArrayList<>();
		// 遍历所有文件，添加它们的绝对路径到列表中
		for (File file : files) {
			filePaths.add(file.getAbsolutePath());
		}

		// 将所有文件路径用换行符连接后返回
		return String.join("\n", filePaths);
	}

	// 创建ListFilesToolCallback的工厂方法
	public static ToolCallback createListFilesToolCallback(String description) {
		return FunctionToolCallback.builder("ls", new ListFilesTool())
				.description(description)
				.inputType(String.class)
				.build();
	}
}

