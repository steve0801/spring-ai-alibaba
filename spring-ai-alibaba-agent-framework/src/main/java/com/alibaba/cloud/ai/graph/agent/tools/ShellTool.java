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
package com.alibaba.cloud.ai.graph.agent.tools;

import com.alibaba.cloud.ai.graph.RunnableConfig;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants.AGENT_CONFIG_CONTEXT_KEY;

/**
 * A tool for executing shell commands.
 *
 * This tool allows for the execution of shell commands within a managed session.
 * The session's lifecycle and configuration are handled by the {@link ShellSessionManager}.
 *
 * Example of creating a callback for this tool:
 * <pre>
 * ToolCallback shellToolCallback = ShellTool.createShellToolCallback("/tmp/agent-workspace");
 * </pre>
 */
public class ShellTool implements BiFunction<ShellTool.Request, ToolContext, String> {

	private static final Logger log = LoggerFactory.getLogger(ShellTool.class);

	public static final String DEFAULT_TOOL_DESCRIPTION =
			// 定义ShellTool的默认描述信息，说明如何正确使用该工具
			"Execute a shell command inside a persistent session. Before running a command, "
					+ "confirm the working directory is correct (e.g., inspect with `ls` or `pwd`) and ensure "
					+ "any parent directories exist. Prefer absolute paths and quote paths containing spaces, "
					+ "such as `cd \"/path/with spaces\"`. Chain multiple commands with `&&` or `;` instead of "
					+ "embedding newlines. Avoid unnecessary `cd` usage unless explicitly required so the "
					+ "session remains stable. Outputs may be truncated when they become very large, and long "
					+ "running commands will be terminated once their configured timeout elapses.";


	// Shell会话管理器实例，用于管理shell会话的生命周期
	private final ShellSessionManager sessionManager;

	/**
	 * Constructs a new ShellTool.
	 *
	 * @param sessionManager The manager for the shell session. Must not be null.
	 */
	// ShellTool构造函数，需要传入ShellSessionManager实例
	public ShellTool(ShellSessionManager sessionManager) {
		// 检查sessionManager是否为null，如果是则抛出异常
		if (sessionManager == null) {
			throw new IllegalArgumentException("ShellSessionManager cannot be null");
		}
		// 初始化sessionManager成员变量
		this.sessionManager = sessionManager;
	}

	/**
	 * Defines the parameters for a shell tool request.
	 *
	 * @param command The shell command to execute. Can be null if only restarting the session.
	 * @param restart If true, the shell session will be restarted before executing any command.
	 */
	// 定义shell工具请求的参数记录类
	public record Request(
			// 定义command属性，表示要执行的shell命令
			@JsonProperty("command")
			// 为command属性添加描述信息
			@JsonPropertyDescription("The command to execute in the shell.")
			String command,

			// 定义restart属性，默认值为false，表示是否重启shell会话
			@JsonProperty(value = "restart", defaultValue = "false")
			// 为restart属性添加描述信息
			@JsonPropertyDescription("Restart the shell session before executing the command.")
			Boolean restart
	) {}

	// 实现BiFunction接口的apply方法，用于执行shell命令
	@Override
	public String apply(Request request, ToolContext toolContext) {
		try {
			// 从工具上下文中获取RunnableConfig实例
			RunnableConfig config = (RunnableConfig) toolContext.getContext().get(AGENT_CONFIG_CONTEXT_KEY);
			// 处理重启请求
			if (Boolean.TRUE.equals(request.restart())) {
				// 记录重启会话的日志
				log.info("Restarting shell session as requested.");
				// 重启shell会话
				sessionManager.restartSession(config);
				// 如果命令为空，则返回重启成功的消息
				if (request.command() == null || request.command().trim().isEmpty()) {
					return "Shell session restarted successfully.";
				}
			}

			// 执行命令
			String command = request.command();
			// 检查命令是否为空
			if (command == null || command.trim().isEmpty()) {
				// 如果命令为空，返回错误信息
				return "Error: Command cannot be empty.";
			}

			// 记录执行命令的日志
			log.info("Executing shell command: {}", command);
			// 执行shell命令并获取结果
			ShellSessionManager.CommandResult result = sessionManager.executeCommand(command, config);

			// 格式化输出结果
			return formatResult(result);

		} catch (Exception e) {
			// 记录命令执行失败的日志
			log.error("Shell command execution failed unexpectedly.", e);
			// 返回错误信息
			return "Error: " + e.getMessage();
		}
	}

	// 格式化命令执行结果的方法
	private String formatResult(ShellSessionManager.CommandResult result) {
		// 创建StringBuilder实例用于构建输出字符串
		StringBuilder outputBuilder = new StringBuilder();

		// 处理超时情况
		if (result.isTimedOut()) {
			// 添加超时错误信息
			outputBuilder.append("Error: Command timed out.");
			// 返回超时信息
			return outputBuilder.toString();
		}

		// 追加标准输出/错误信息
		String output = result.getOutput();
		// 如果输出为空则显示"<no output>"，否则显示实际输出
		outputBuilder.append(output.isEmpty() ? "<no output>" : output);

		// 添加行数截断信息
		if (result.isTruncatedByLines()) {
			// 格式化并添加行数截断信息
			outputBuilder.append(String.format("\n\n... Output truncated at %d lines (observed %d).",
					sessionManager.getMaxOutputLines(), result.getTotalLines()));
		}
		// 添加字节数截断信息
		if (result.isTruncatedByBytes() && sessionManager.getMaxOutputBytes() != null) {
			// 格式化并添加字节数截断信息
			outputBuilder.append(String.format("\n\n... Output truncated at %d bytes (observed %d).",
					sessionManager.getMaxOutputBytes(), result.getTotalBytes()));
		}

		// 为非零退出码添加退出码信息
		if (result.getExitCode() != null && result.getExitCode() != 0) {
			// 添加退出码信息
			outputBuilder.append("\n\nExit code: ").append(result.getExitCode());
		}

		// 返回格式化后的结果字符串
		return outputBuilder.toString();
	}

	// 获取ShellSessionManager实例的方法
	public ShellSessionManager getSessionManager() {
		return sessionManager;
	}

	// 创建Builder实例的静态方法
	public static Builder builder(String workspaceRoot) {
		return new Builder(workspaceRoot);
	}

	// 内部Builder类，用于构建ShellTool的ToolCallback实例
	public static class Builder {

		// 工作空间根路径
		private final String workspaceRoot;

		// 工具名称，默认为"shell"
		private String name = "shell";

		// 工具描述信息，默认使用DEFAULT_TOOL_DESCRIPTION
		private String description = DEFAULT_TOOL_DESCRIPTION;

		// 启动命令列表
		private List<String> startupCommands;

		// 关闭命令列表
		private List<String> shutdownCommands;

		// 命令执行超时时间，默认为60000毫秒（1分钟）
		private long commandTimeout = 60000;

		// 最大输出行数，默认为1000行
		private int maxOutputLines = 1000;

		// shell命令列表
		private List<String> shellCommand;

		// 环境变量映射
		private Map<String, String> environment;

		// Builder构造函数
		public Builder(String workspaceRoot) {
			this.workspaceRoot = workspaceRoot;
		}

		// 设置工具名称的方法
		public Builder withName(String name) {
			this.name = name;
			return this;
		}

		// 设置工具描述的方法
		public Builder withDescription(String description) {
			this.description = description;
			return this;
		}

		// 设置启动命令列表的方法
		public Builder withStartupCommands(List<String> startupCommands) {
			this.startupCommands = startupCommands;
			return this;
		}

		// 设置关闭命令列表的方法
		public Builder withShutdownCommands(List<String> shutdownCommands) {
			this.shutdownCommands = shutdownCommands;
			return this;
		}

		// 设置命令执行超时时间的方法
		public Builder withCommandTimeout(long commandTimeout) {
			this.commandTimeout = commandTimeout;
			return this;
		}

		// 设置最大输出行数的方法
		public Builder withMaxOutputLines(int maxOutputLines) {
			this.maxOutputLines = maxOutputLines;
			return this;
		}

		// 设置shell命令列表的方法
		public Builder withShellCommand(List<String> shellCommand) {
			this.shellCommand = shellCommand;
			return this;
		}

		// 设置环境变量的方法
		public Builder withEnvironment(Map<String, String> environment) {
			this.environment = environment;
			return this;
		}

		// 构建ToolCallback实例的方法
		public ToolCallback build() {
			// 创建ShellSessionManager的Builder实例
			ShellSessionManager.Builder sessionManagerBuilder = ShellSessionManager.builder()
				// 设置工作空间根路径
				.workspaceRoot(Path.of(workspaceRoot))
				// 设置命令执行超时时间
				.commandTimeout(commandTimeout)
				// 设置最大输出行数
				.maxOutputLines(maxOutputLines);

			// 如果启动命令列表不为空，则设置启动命令
			if (startupCommands != null) {
				sessionManagerBuilder.setStartupCommand(startupCommands);
			}
			// 如果关闭命令列表不为空，则设置关闭命令
			if (shutdownCommands != null) {
				sessionManagerBuilder.setShutdownCommand(shutdownCommands);
			}
			// 如果shell命令列表不为空，则设置shell命令
			if (shellCommand != null) {
				sessionManagerBuilder.shellCommand(shellCommand);
			}
			// 如果环境变量映射不为空，则设置环境变量
			if (environment != null) {
				sessionManagerBuilder.environment(environment);
			}

			// 构建ShellSessionManager实例
			ShellSessionManager sessionManager = sessionManagerBuilder.build();
			// 创建ShellTool实例
			ShellTool shellTool = new ShellTool(sessionManager);

			// 构建并返回FunctionToolCallback实例
			return FunctionToolCallback.builder(name, shellTool)
				.description(description)
				.inputType(Request.class)
				.build();
		}

	}

}
