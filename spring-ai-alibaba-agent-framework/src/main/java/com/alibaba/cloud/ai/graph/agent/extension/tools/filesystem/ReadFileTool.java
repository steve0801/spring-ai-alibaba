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
import java.util.List;
import java.util.function.BiFunction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Tool for reading file contents with pagination support.
 */
public class ReadFileTool implements BiFunction<ReadFileTool.ReadFileRequest, ToolContext, String> {

	public static final String DESCRIPTION = """
Reads a file from the filesystem. You can access any file directly by using this tool.
Assume this tool is able to read all files on the machine. If the User provides a path to a file assume that path is valid. It is okay to read a file that does not exist; an error will be returned.

Usage:
- The file_path parameter must be an absolute path, not a relative path
- By default, it reads up to 500 lines starting from the beginning of the file
- **IMPORTANT for large files and codebase exploration**: Use pagination with offset and limit parameters to avoid context overflow
  - First scan: read_file(path, limit=100) to see file structure
  - Read more sections: read_file(path, offset=100, limit=200) for next 200 lines
  - Only omit limit (read full file) when necessary for editing
- Specify offset and limit: read_file(path, offset=0, limit=100) reads first 100 lines
- Any lines longer than 2000 characters will be truncated
- Results are returned using cat -n format, with line numbers starting at 1
- You have the capability to call multiple tools in a single response. It is always better to speculatively read multiple files as a batch that are potentially useful.
- If you read a file that exists but has empty contents you will receive a system reminder warning in place of file contents.
			- You should almost ALWAYS use the list_files tool before using this tool to verify the file path.
			""";

	public ReadFileTool() {
	}

	// 实现BiFunction接口的apply方法，用于读取文件内容
	@Override
	public String apply(ReadFileRequest request, ToolContext toolContext) {
		try {
			// 根据文件路径创建Path对象
			Path path = Paths.get(request.filePath);
			// 读取文件所有行到列表中
			List<String> allLines = Files.readAllLines(path);

			// 应用分页逻辑
			// 确定起始行号，如果请求中指定了offset则使用指定值，否则从第0行开始
			int start = request.offset != null ? request.offset : 0;
			// 确定读取行数限制，如果请求中指定了limit则使用指定值，否则默认读取500行
			int limit = request.limit != null ? request.limit : 500;
			// 计算结束行号，取起始行号+限制行数与总行数中的较小值
			int end = Math.min(start + limit, allLines.size());

			// 检查起始行号是否超出文件长度
			if (start >= allLines.size()) {
				return "Error: Offset " + start + " is beyond file length " + allLines.size();
			}

			// 获取指定范围的行列表
			List<String> lines = allLines.subList(start, end);

			// 添加行号（采用cat -n格式）
			StringBuilder result = new StringBuilder();
			// 遍历选中的行
			for (int i = 0; i < lines.size(); i++) {
				// 格式化行号和内容，行号从起始行号+1开始
				result.append(String.format("%6d\t%s\n", start + i + 1, lines.get(i)));
			}

			// 返回格式化后的结果
			return result.toString();
		}
		catch (IOException e) {
			// 处理IO异常，返回错误信息
			return "Error reading file: " + e.getMessage();
		}
	}

	// 创建ReadFileToolCallback的工厂方法
	public static ToolCallback createReadFileToolCallback(String description) {
		return FunctionToolCallback.builder("read_file", new ReadFileTool())
				.description(description)
				.inputType(ReadFileRequest.class)
				.build();
	}

	/**
	 * Request structure for reading a file.
	 */
	// 读取文件请求的数据结构
	public static class ReadFileRequest {

		// 文件路径属性，必需
		@JsonProperty(required = true, value = "file_path")
		@JsonPropertyDescription("The absolute path of the file to read")
		public String filePath;

		// 偏移量属性，可选
		@JsonProperty(value = "offset")
		@JsonPropertyDescription("Line offset to start reading from (default: 0)")
		public Integer offset;

		// 限制行数属性，可选
		@JsonProperty(value = "limit")
		@JsonPropertyDescription("Maximum number of lines to read (default: 500)")
		public Integer limit;

		// 默认构造函数
		public ReadFileRequest() {
		}

		// 带参数的构造函数
		public ReadFileRequest(String filePath, Integer offset, Integer limit) {
			this.filePath = filePath;
			this.offset = offset;
			this.limit = limit;
		}
	}
}
