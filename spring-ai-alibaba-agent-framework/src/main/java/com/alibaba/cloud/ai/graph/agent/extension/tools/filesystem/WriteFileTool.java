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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiFunction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Tool for writing content to a new file.
 */
public class WriteFileTool implements BiFunction<WriteFileTool.WriteFileRequest, ToolContext, String> {

	public static final String DESCRIPTION = """
			Writes to a new file in the filesystem.
			
			Usage:
			- The file_path parameter must be an absolute path, not a relative path
			- The content parameter must be a string
			- The write_file tool will create a new file.
			- When writing to a file, the content will completely replace the existing content.
			""";

	// 默认构造函数
	public WriteFileTool() {
	}

	// 实现BiFunction接口的apply方法，用于向文件写入内容
	@Override
	public String apply(WriteFileRequest request, ToolContext toolContext) {
		try {
			// 根据文件路径创建Path对象
			Path path = Paths.get(request.filePath);

			// 检查文件是否已存在
			if (Files.exists(path)) {
				return "Error: File already exists: " + request.filePath + ". Use edit_file to modify existing files.";
			}

			// 如果需要，创建父目录
			Path parent = path.getParent();
			if (parent != null && !Files.exists(parent)) {
				Files.createDirectories(parent);
			}

			// 将内容写入文件
			Files.writeString(path, request.content);

			return "Successfully created file: " + request.filePath;
		}
		catch (IOException e) {
			// 处理IO异常，返回错误信息
			return "Error writing file: " + e.getMessage();
		}
	}

	// 创建WriteFileToolCallback的工厂方法
	public static ToolCallback createWriteFileToolCallback(String description) {
		return FunctionToolCallback.builder("write_file", new WriteFileTool())
				.description(description)
				.inputType(WriteFileRequest.class)
				.build();
	}

	/**
	 * Request structure for writing a file.
	 */
	// 写入文件请求的数据结构
	public static class WriteFileRequest {

		// 文件路径属性，必需
		@JsonProperty(required = true, value = "file_path")
		@JsonPropertyDescription("The absolute path of the file to create")
		public String filePath;

		// 文件内容属性，必需
		@JsonProperty(required = true)
		@JsonPropertyDescription("The content to write to the file")
		public String content;

		// 默认构造函数
		public WriteFileRequest() {
		}

		// 带参数的构造函数
		public WriteFileRequest(String filePath, String content) {
			this.filePath = filePath;
			this.content = content;
		}
	}
}
