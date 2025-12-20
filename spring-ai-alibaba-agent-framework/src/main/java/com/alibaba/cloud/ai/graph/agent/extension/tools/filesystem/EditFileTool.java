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
 * Tool for editing files using string replacement.
 */
public class EditFileTool implements BiFunction<EditFileTool.EditFileRequest, ToolContext, String> {

	public static final String DESCRIPTION = """
			Performs exact string replacements in files.
			
			Usage:
			- You must use your `read_file` tool at least once before editing.
			- When editing text from read_file output, preserve exact indentation
			- ALWAYS prefer editing existing files. NEVER write new files unless explicitly required.
			- The edit will FAIL if `old_string` is not unique in the file.
			- After editing, verify the changes by using the read_file tool.
			""";

	// 默认构造函数
	public EditFileTool() {
	}

	// 实现BiFunction接口的apply方法，执行文件编辑操作
	@Override
	public String apply(EditFileRequest request, ToolContext toolContext) {
		try {
			// 根据文件路径创建Path对象
			Path path = Paths.get(request.filePath);

			// 检查文件是否存在
			if (!Files.exists(path)) {
				return "Error: File not found: " + request.filePath;
			}

			// 读取文件内容
			String content = Files.readString(path);

			// 检查要替换的旧字符串是否存在
			if (!content.contains(request.oldString)) {
				return "Error: String not found in file: " + request.oldString;
			}

			// 统计旧字符串出现次数
			int count = 0;
			String temp = content;
			int index = 0;
			while ((index = temp.indexOf(request.oldString, index)) != -1) {
				count++;
				index += request.oldString.length();
			}

			// 如果不是替换全部且出现次数大于1，则返回错误
			if (!request.replaceAll && count > 1) {
				return "Error: String appears " + count + " times in file. " +
						"Please provide more context or use replace_all=true";
			}

			// 执行字符串替换操作
			String newContent;
			if (request.replaceAll) {
				// 如果是替换全部，使用replace方法
				newContent = content.replace(request.oldString, request.newString);
			}
			else {
				// 替换第一个匹配项，使用字面量字符串匹配
				// 注意：replaceFirst()将第一个参数视为正则表达式，可能导致特殊字符问题
				// 我们使用indexOf() + substring()进行字面量匹配
				int replaceIndex = content.indexOf(request.oldString);
				if (replaceIndex != -1) {
					newContent = content.substring(0, replaceIndex) + request.newString
							+ content.substring(replaceIndex + request.oldString.length());
				}
				else {
					// 不应到达此处，因为我们已经检查了存在性
					newContent = content;
				}
			}

			// 将修改后的内容写回文件
			Files.writeString(path, newContent);

			// 返回成功替换的消息
			return "Successfully replaced " + (request.replaceAll ? count : 1) + " occurrence(s) in " + request.filePath;
		}
		catch (IOException e) {
			// 处理IO异常，返回错误信息
			return "Error editing file: " + e.getMessage();
		}
	}

	// 创建EditFileToolCallback的工厂方法
	public static ToolCallback createEditFileToolCallback(String description) {
		return FunctionToolCallback.builder("edit_file", new EditFileTool())
				.description(description)
				.inputType(EditFileRequest.class)
				.build();
	}

	/**
	 * Request structure for editing a file.
	 */
	// 编辑文件请求的数据结构
	public static class EditFileRequest {

		// 文件路径属性，必需
		@JsonProperty(required = true, value = "file_path")
		@JsonPropertyDescription("The absolute path of the file to edit")
		public String filePath;

		// 要替换的旧字符串属性，必需
		@JsonProperty(required = true, value = "old_string")
		@JsonPropertyDescription("The exact string to find and replace")
		public String oldString;

		// 新字符串属性，必需
		@JsonProperty(required = true, value = "new_string")
		@JsonPropertyDescription("The new string to replace with")
		public String newString;

		// 是否替换全部属性，默认为false
		@JsonProperty(value = "replace_all")
		@JsonPropertyDescription("If true, replace all occurrences; if false, only replace if unique (default: false)")
		public boolean replaceAll = false;

		// 默认构造函数
		public EditFileRequest() {
		}

		// 带参数的构造函数
		public EditFileRequest(String filePath, String oldString, String newString, boolean replaceAll) {
			this.filePath = filePath;
			this.oldString = oldString;
			this.newString = newString;
			this.replaceAll = replaceAll;
		}
	}
}
