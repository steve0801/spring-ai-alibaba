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
package com.alibaba.cloud.ai.examples.chatbot;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool;
import com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem.ReadFileTool;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiFunction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@Configuration
public class ChatbotAgent {

	private static final String INSTRUCTION = """
			You are a helpful assistant named SAA.
			You have access to tools that can help you execute shell commands, run Python code, and view text files.
			Use these tools to assist users with their tasks.
			""";
	// 定义AI助手的指令说明，说明助手名为SAA，可以执行shell命令、运行Python代码和查看文本文件

	@Bean
	// 声明这是一个Spring Bean，用于创建ReactAgent实例
	public ReactAgent chatbotReactAgent(ChatModel chatModel,
			// 接收ChatModel依赖和其他工具回调依赖
			ToolCallback executeShellCommand,
			ToolCallback executePythonCode,
			ToolCallback viewTextFile) {
		return ReactAgent.builder()
				// 使用ReactAgent构建器创建实例
				.name("SAA")
				// 设置agent名称为SAA
				.model(chatModel)
				// 设置使用的聊天模型
				.instruction(INSTRUCTION)
				// 设置agent的行为指令
				.enableLogging(true)
				// 启用日志记录
				.tools(
						// 注册可用的工具
						executeShellCommand,
						// shell命令执行工具
						executePythonCode,
						// Python代码执行工具
						viewTextFile
						// 文本文件查看工具
				)
				.build();
				// 构建并返回ReactAgent实例
	}

	// Tool: execute_shell_command
	// 工具：执行shell命令
	@Bean
	// 声明这是一个Spring Bean，用于创建shell命令执行工具
	public ToolCallback executeShellCommand() {
		// Use ShellTool with a temporary workspace directory
		// 使用临时工作目录创建ShellTool
		String workspaceRoot = System.getProperty("java.io.tmpdir") + File.separator + "agent-workspace";
		// 构造工作空间根路径，使用系统临时目录下的agent-workspace文件夹
		return ShellTool.builder(workspaceRoot)
				// 使用指定工作目录创建ShellTool构建器
				.withName("execute_shell_command")
				// 设置工具名称
				.withDescription("Execute a shell command inside a persistent session. Before running a command, " +
						"confirm the working directory is correct (e.g., inspect with `ls` or `pwd`) and ensure " +
						"any parent directories exist. Prefer absolute paths and quote paths containing spaces, " +
						"such as `cd \"/path/with spaces\"`. Chain multiple commands with `&&` or `;` instead of " +
						"embedding newlines. Avoid unnecessary `cd` usage unless explicitly required so the " +
						"session remains stable. Outputs may be truncated when they become very large, and long " +
						"running commands will be terminated once their configured timeout elapses.")
				// 设置工具详细描述，包括使用注意事项和最佳实践
				.build();
				// 构建并返回ToolCallback实例
	}

	// Tool: execute_python_code
	// 工具：执行Python代码
	@Bean
	// 声明这是一个Spring Bean，用于创建Python代码执行工具
	public ToolCallback executePythonCode() {
		return FunctionToolCallback.builder("execute_python_code", new PythonTool())
				// 使用FunctionToolCallback构建器，传入工具名称和PythonTool实例
				.description(PythonTool.DESCRIPTION)
				// 使用PythonTool类中定义的描述信息
				.inputType(PythonTool.PythonRequest.class)
				// 指定输入参数类型为PythonRequest
				.build();
				// 构建并返回ToolCallback实例
	}

	// Tool: view_text_file
	// 工具：查看文本文件
	@Bean
	// 声明这是一个Spring Bean，用于创建文本文件查看工具
	public ToolCallback viewTextFile() {
		// Create a custom wrapper to match the original tool name
		// 创建自定义包装器以匹配原始工具名称
		ReadFileTool readFileTool = new ReadFileTool();
		// 创建ReadFileTool实例
		return FunctionToolCallback.builder("view_text_file", readFileTool)
				// 使用FunctionToolCallback构建器，传入工具名称和ReadFileTool实例
				.description("View the contents of a text file. The file_path parameter must be an absolute path. " +
						"You can specify offset and limit to read specific portions of the file. " +
						"By default, reads up to 500 lines starting from the beginning of the file.")
				// 设置工具描述信息，说明如何使用该工具查看文件内容
				.inputType(ReadFileTool.ReadFileRequest.class)
				// 指定输入参数类型为ReadFileRequest
				.build();
				// 构建并返回ToolCallback实例
	}


}

