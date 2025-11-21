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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Manages shell sessions and command execution.
 * Provides persistent shell execution capabilities with state preservation.
 */
public class ShellSessionManager {

	private static final Logger log = LoggerFactory.getLogger(ShellSessionManager.class);
	private static final String DONE_MARKER_PREFIX = "__LC_SHELL_DONE__";
	private static final String SESSION_INSTANCE_CONTEXT_KEY = "_SHELL_SESSION_";
	private static final String SESSION_PATH_CONTEXT_KEY = "_SHELL_PATH_";

	private final Path workspaceRoot;
	private final boolean useTemporaryWorkspace;
	private final List<String> startupCommands;
	private final List<String> shutdownCommands;
	private final long commandTimeout;
	private final long startupTimeout;
	private final long terminationTimeout;
	private final int maxOutputLines;
	private final Long maxOutputBytes;
	private final List<String> shellCommand;
	private final Map<String, String> environment;
	private final List<RedactionRule> redactionRules;

	private ShellSessionManager(Builder builder) {
		// 初始化工作空间根路径
		this.workspaceRoot = builder.workspaceRoot;
		// 判断是否使用临时工作空间
		this.useTemporaryWorkspace = builder.workspaceRoot == null;
		// 初始化启动命令列表
		this.startupCommands = new ArrayList<>(builder.startupCommands);
		// 初始化关闭命令列表
		this.shutdownCommands = new ArrayList<>(builder.shutdownCommands);
		// 设置命令执行超时时间
		this.commandTimeout = builder.commandTimeout;
		// 设置启动超时时间
		this.startupTimeout = builder.startupTimeout;
		// 设置终止超时时间
		this.terminationTimeout = builder.terminationTimeout;
		// 设置最大输出行数
		this.maxOutputLines = builder.maxOutputLines;
		// 设置最大输出字节数
		this.maxOutputBytes = builder.maxOutputBytes;
		// 初始化shell命令列表
		this.shellCommand = new ArrayList<>(builder.shellCommand);
		// 初始化环境变量映射
		this.environment = new HashMap<>(builder.environment);
		// 初始化数据脱敏规则列表
		this.redactionRules = new ArrayList<>(builder.redactionRules);
	}

	// 创建Builder实例的静态方法
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Initialize shell session.
	 */
	public void initialize(RunnableConfig config) {
		try {
			// 获取工作空间路径
			Path workspace = workspaceRoot;
			// 如果使用临时工作空间
			if (useTemporaryWorkspace) {
				// 创建临时目录
				Path tempDir = Files.createTempDirectory("shell_tool_");
				// 将临时目录路径存入配置上下文
				config.context().put(SESSION_PATH_CONTEXT_KEY, tempDir);

				// 更新工作空间为临时目录
				workspace = tempDir;
			} else {
				// 确保工作空间目录存在
				Files.createDirectories(workspace);
			}

			// 创建ShellSession实例
			ShellSession session = new ShellSession(workspace, shellCommand, environment);
			// 启动shell会话
			session.start();
			// 将会话实例存入配置上下文
			config.context().put(SESSION_INSTANCE_CONTEXT_KEY, session);

			// 记录日志信息
			log.info("Started shell session in workspace: {}", workspace);

			// 执行启动命令
			for (String command : startupCommands) {
				// 执行单个启动命令
				CommandResult result = session.execute(command, startupTimeout, maxOutputLines, maxOutputBytes);
				// 检查命令是否超时或执行失败
				if (result.isTimedOut() || (result.getExitCode() != null && result.getExitCode() != 0)) {
					// 抛出运行时异常
					throw new RuntimeException("Startup command failed: " + command + ", exit code: " + result.getExitCode());
				}
			}
		} catch (Exception e) {
			// 清理会话资源
			cleanup(config);
			// 抛出运行时异常
			throw new RuntimeException("Failed to initialize shell session", e);
		}
	}

	/**
	 * Clean up shell session.
	 */
	public void cleanup(RunnableConfig config) {
		try {
			// 从配置上下文中获取ShellSession实例
			ShellSession session = (ShellSession) config.context().get(SESSION_INSTANCE_CONTEXT_KEY);
			// 如果会话实例存在
			if (session != null) {
				// 执行关闭命令
				for (String command : shutdownCommands) {
					try {
						// 执行单个关闭命令
						session.execute(command, commandTimeout, maxOutputLines, maxOutputBytes);
					} catch (Exception e) {
						// 记录警告日志
						log.warn("Shutdown command failed: {}", command, e);
					}
				}
			}
		} finally {
			// 执行实际清理操作
			doCleanup(config);
		}
	}

	// 执行实际清理操作的方法
	private void doCleanup(RunnableConfig config) {
		// 从配置上下文中获取ShellSession实例
		ShellSession session = (ShellSession) config.context().get(SESSION_INSTANCE_CONTEXT_KEY);
		// 如果会话实例存在
		if (session != null) {
			// 停止shell会话
			session.stop(terminationTimeout);
			// 从配置上下文中移除会话实例
			config.context().remove(SESSION_INSTANCE_CONTEXT_KEY);
		}

		// 从配置上下文中获取临时目录路径
		Path tempDir = (Path) config.context().get(SESSION_PATH_CONTEXT_KEY);
		// 如果临时目录路径存在
		if (tempDir != null) {
			try {
				// 删除临时目录
				deleteDirectory(tempDir);
			} catch (IOException e) {
				// 记录警告日志
				log.warn("Failed to delete temporary directory: {}", tempDir, e);
			}
			// 从配置上下文中移除临时目录路径
			config.context().remove(SESSION_PATH_CONTEXT_KEY);
		}
	}

	/**
	 * Execute a command in the current shell session.
	 */
	public CommandResult executeCommand(String command, RunnableConfig config) {
		// 从配置上下文中获取ShellSession实例
		ShellSession session = (ShellSession) config.context().get(SESSION_INSTANCE_CONTEXT_KEY);
		// 如果会话未初始化
		if (session == null) {
			// 抛出非法状态异常
			throw new IllegalStateException("Shell session not initialized. Call initialize() first.");
		}

		// 记录执行命令的日志
		log.info("Executing shell command: {}", command);
		// 执行命令
		CommandResult result = session.execute(command, commandTimeout, maxOutputLines, maxOutputBytes);

		// 应用数据脱敏并跟踪匹配项
		String output = result.getOutput();
		// 创建匹配项映射
		Map<String, List<String>> allMatches = new HashMap<>();

		// 遍历所有脱敏规则
		for (RedactionRule rule : redactionRules) {
			// 应用脱敏规则并获取结果
			RedactionResult redactionResult = rule.applyWithMatches(output);
			// 获取脱敏后的内容
			output = redactionResult.getRedactedContent();
			// 如果有匹配项
			if (!redactionResult.getMatches().isEmpty()) {
				// 将匹配项添加到映射中
				allMatches.computeIfAbsent(rule.getPiiType(), k -> new ArrayList<>())
					.addAll(redactionResult.getMatches());
			}
		}

		// 返回包含脱敏结果的命令执行结果
		return new CommandResult(output, result.getExitCode(), result.isTimedOut(),
			result.isTruncatedByLines(), result.isTruncatedByBytes(),
			result.getTotalLines(), result.getTotalBytes(), allMatches);
	}

	/**
	 * Restart the shell session.
	 */
	public void restartSession(RunnableConfig config) {
		// 从配置上下文中获取ShellSession实例
		ShellSession session = (ShellSession) config.context().get(SESSION_INSTANCE_CONTEXT_KEY);
		// 如果会话未初始化
		if (session == null) {
			// 抛出非法状态异常
			throw new IllegalStateException("Shell session not initialized.");
		}

		// 记录重启会话的日志
		log.info("Restarting shell session");
		// 重启shell会话
		session.restart();

		// 重新执行启动命令
		for (String command : startupCommands) {
			// 执行单个启动命令
			session.execute(command, startupTimeout, maxOutputLines, maxOutputBytes);
		}
	}

	// 获取最大输出行数的方法
	public int getMaxOutputLines() {
		return maxOutputLines;
	}

	// 获取最大输出字节数的方法
	public Long getMaxOutputBytes() {
		return maxOutputBytes;
	}

	// 删除目录及其内容的方法
	private void deleteDirectory(Path directory) throws IOException {
		// 检查目录是否存在
		if (Files.exists(directory)) {
			// 使用文件流遍历目录
			try (var stream = Files.walk(directory)) {
				// 按逆序排列并删除所有文件和目录
				stream.sorted(Comparator.reverseOrder())
					.forEach(path -> {
						try {
							// 删除文件或目录
							Files.delete(path);
						} catch (IOException e) {
							// 记录警告日志
							log.warn("Failed to delete: {}", path, e);
						}
					});
			}
		}
	}

	/**
	 * Persistent shell session that executes commands sequentially.
	 */
	private class ShellSession {
		// 工作空间路径
		private final Path workspace;
		// shell命令列表
		private final List<String> command;
		// 环境变量映射
		private final Map<String, String> env;
		// 进程实例
		private Process process;
		// 标准输入写入器
		private BufferedWriter stdin;
		// 输出队列
		private BlockingQueue<OutputLine> outputQueue;
		// 终止标志
		private volatile boolean terminated;

		// ShellSession构造函数
		ShellSession(Path workspace, List<String> command, Map<String, String> env) {
			// 初始化工作空间
			this.workspace = workspace;
			// 初始化命令列表
			this.command = command;
			// 初始化环境变量
			this.env = env;
			// 初始化输出队列
			this.outputQueue = new LinkedBlockingQueue<>();
		}

		// 启动shell会话的方法
		void start() throws IOException {
			// 创建进程构建器
			ProcessBuilder pb = new ProcessBuilder(command);
			// 设置工作目录
			pb.directory(workspace.toFile());
			// 设置环境变量
			pb.environment().putAll(env);
			// 保持标准错误流分离以便更好的错误跟踪
			pb.redirectErrorStream(false);

			// 启动进程
			process = pb.start();
			// 创建标准输入写入器
			stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

			// 启动标准输出读取线程
			new Thread(() -> {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
					String line;
					// 逐行读取标准输出
					while ((line = reader.readLine()) != null) {
						// 将输出行添加到队列
						outputQueue.offer(new OutputLine("stdout", line));
					}
				} catch (IOException e) {
					// 记录调试日志
					log.debug("Stdout reader terminated", e);
				} finally {
					// 添加EOF标记
					outputQueue.offer(new OutputLine("stdout", null));
				}
			}, "shell-stdout-reader").start();

			// 启动标准错误读取线程
			new Thread(() -> {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
					String line;
					// 逐行读取标准错误
					while ((line = reader.readLine()) != null) {
						// 将错误行添加到队列
						outputQueue.offer(new OutputLine("stderr", line));
					}
				} catch (IOException e) {
					// 记录调试日志
					log.debug("Stderr reader terminated", e);
				} finally {
					// 添加EOF标记
					outputQueue.offer(new OutputLine("stderr", null));
				}
			}, "shell-stderr-reader").start();
		}

		// 重启shell会话的方法
		void restart() {
			// 停止当前会话
			stop(terminationTimeout);
			try {
				// 启动新会话
				start();
			} catch (IOException e) {
				// 抛出运行时异常
				throw new RuntimeException("Failed to restart shell session", e);
			}
		}

		// 停止shell会话的方法
		void stop(long timeoutMs) {
			// 如果进程为空或已终止
			if (process == null || !process.isAlive()) {
				return;
			}

			// 设置终止标志
			terminated = true;
			try {
				// 发送退出命令
				stdin.write("exit\n");
				// 刷新输出缓冲区
				stdin.flush();
			} catch (IOException e) {
				// 记录调试日志
				log.debug("Failed to send exit command", e);
			}

			try {
				// 等待进程在指定时间内结束
				if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
					// 强制销毁进程
					process.destroyForcibly();
				}
			} catch (InterruptedException e) {
				// 中断当前线程
				Thread.currentThread().interrupt();
				// 强制销毁进程
				process.destroyForcibly();
			}

			try {
				// 关闭标准输入
				stdin.close();
			} catch (IOException e) {
				// 记录调试日志
				log.debug("Failed to close stdin", e);
			}
		}

		// 执行命令的方法
		CommandResult execute(String command, long timeoutMs, int maxOutputLines, Long maxOutputBytes) {
			// 如果进程为空或未运行
			if (process == null || !process.isAlive()) {
				// 抛出非法状态异常
				throw new IllegalStateException("Shell session is not running");
			}

			// 生成完成标记
			String marker = DONE_MARKER_PREFIX + UUID.randomUUID().toString().replace("-", "");
			// 计算截止时间
			long deadline = System.currentTimeMillis() + timeoutMs;

			try {
				// 清空输出队列
				outputQueue.clear();

				// 发送命令
				stdin.write(command);
				// 如果命令不以换行符结尾，则添加换行符
				if (!command.endsWith("\n")) {
					stdin.write("\n");
				}
				// 发送打印退出码的命令
				stdin.write(String.format("printf '%s %%s\\n' $?\n", marker));
				// 刷新输出缓冲区
				stdin.flush();

				// 收集输出
				return collectOutput(marker, deadline, maxOutputLines, maxOutputBytes);

			} catch (IOException e) {
				// 抛出运行时异常
				throw new RuntimeException("Failed to execute command", e);
			}
		}

		// 收集命令输出的方法
		private CommandResult collectOutput(String marker, long deadline, int maxOutputLines, Long maxOutputBytes) {
			// 创建行列表
			List<String> lines = new ArrayList<>();
			// 初始化总行数
			int totalLines = 0;
			// 初始化总字节数
			long totalBytes = 0;
			// 初始化行截断标志
			boolean truncatedByLines = false;
			// 初始化字节截断标志
			boolean truncatedByBytes = false;
			// 初始化退出码
			Integer exitCode = null;
			// 初始化超时标志
			boolean timedOut = false;

			// 循环收集输出
			while (true) {
				// 计算剩余时间
				long remaining = deadline - System.currentTimeMillis();
				// 如果时间已到
				if (remaining <= 0) {
					// 设置超时标志
					timedOut = true;
					// 记录警告日志
					log.warn("Command timed out, restarting session");
					// 重启会话
					restart();
					// 退出循环
					break;
				}

				try {
					// 从输出队列中获取一行输出
					OutputLine outputLine = outputQueue.poll(remaining, TimeUnit.MILLISECONDS);
					// 如果获取超时
					if (outputLine == null) {
						// 设置超时标志
						timedOut = true;
						// 重启会话
						restart();
						// 退出循环
						break;
					}

					// 跳过EOF标记
					if (outputLine.content == null) {
						continue;
					}

					// 获取行内容
					String line = outputLine.content;

					// 检查完成标记（仅在标准输出中）
					if ("stdout".equals(outputLine.source) && line.startsWith(marker)) {
						// 分割行内容
						String[] parts = line.split(" ", 2);
						// 如果分割后有内容
						if (parts.length > 1) {
							try {
								// 解析退出码
								exitCode = Integer.parseInt(parts[1].trim());
							} catch (NumberFormatException e) {
								// 忽略解析错误
							}
						}
						// 退出循环
						break;
					}

					// 增加总行数
					totalLines++;

					// 格式化行内容，如有需要添加stderr标签
					String formattedLine = line;
					if ("stderr".equals(outputLine.source)) {
						formattedLine = "[stderr] " + line;
					}

					// 计算总字节数（包括换行符）
					totalBytes += formattedLine.getBytes().length + 1;

					// 检查是否超过最大行数限制
					if (totalLines <= maxOutputLines) {
						// 检查是否超过最大字节数限制
						if (maxOutputBytes == null || totalBytes <= maxOutputBytes) {
							// 添加行到列表
							lines.add(formattedLine);
						} else {
							// 设置字节截断标志
							truncatedByBytes = true;
						}
					} else {
						// 设置行截断标志
						truncatedByLines = true;
					}

				} catch (InterruptedException e) {
					// 中断当前线程
					Thread.currentThread().interrupt();
					// 退出循环
					break;
				}
			}

			// 将行列表连接成字符串
			String output = String.join("\n", lines);
			// 返回命令执行结果
			return new CommandResult(output, exitCode, timedOut, truncatedByLines,
				truncatedByBytes, totalLines, totalBytes);
		}
	}

	/**
	 * Output line with source (stdout/stderr).
	 */
	private static class OutputLine {
		// 输出源（stdout/stderr）
		final String source;
		// 输出内容
		final String content;

		// OutputLine构造函数
		OutputLine(String source, String content) {
			this.source = source;
			this.content = content;
		}
	}

	/**
	 * Result of command execution.
	 */
	public static class CommandResult {
		// 命令输出内容
		private final String output;
		// 退出码
		private final Integer exitCode;
		// 超时标志
		private final boolean timedOut;
		// 行截断标志
		private final boolean truncatedByLines;
		// 字节截断标志
		private final boolean truncatedByBytes;
		// 总行数
		private final int totalLines;
		// 总字节数
		private final long totalBytes;
		// 数据脱敏匹配项
		private final Map<String, List<String>> redactionMatches;

		// CommandResult构造函数（无脱敏匹配项）
		public CommandResult(String output, Integer exitCode, boolean timedOut,
					  boolean truncatedByLines, boolean truncatedByBytes,
					  int totalLines, long totalBytes) {
			this(output, exitCode, timedOut, truncatedByLines, truncatedByBytes,
				 totalLines, totalBytes, new HashMap<>());
		}

		// CommandResult构造函数（带脱敏匹配项）
		public CommandResult(String output, Integer exitCode, boolean timedOut,
					  boolean truncatedByLines, boolean truncatedByBytes,
					  int totalLines, long totalBytes, Map<String, List<String>> redactionMatches) {
			this.output = output;
			this.exitCode = exitCode;
			this.timedOut = timedOut;
			this.truncatedByLines = truncatedByLines;
			this.truncatedByBytes = truncatedByBytes;
			this.totalLines = totalLines;
			this.totalBytes = totalBytes;
			this.redactionMatches = new HashMap<>(redactionMatches);
		}

		// 获取输出内容的方法
		public String getOutput() { return output; }
		// 获取退出码的方法
		public Integer getExitCode() { return exitCode; }
		// 检查是否超时的方法
		public boolean isTimedOut() { return timedOut; }
		// 检查是否因行数截断的方法
		public boolean isTruncatedByLines() { return truncatedByLines; }
		// 检查是否因字节数截断的方法
		public boolean isTruncatedByBytes() { return truncatedByBytes; }
		// 获取总行数的方法
		public int getTotalLines() { return totalLines; }
		// 获取总字节数的方法
		public long getTotalBytes() { return totalBytes; }
		// 获取数据脱敏匹配项的方法
		public Map<String, List<String>> getRedactionMatches() { return new HashMap<>(redactionMatches); }

		// 检查命令是否成功执行的方法
		public boolean isSuccess() {
			return !timedOut && (exitCode == null || exitCode == 0);
		}
	}

	/**
	 * Result of redaction operation with match information.
	 */
	public static class RedactionResult {
		// 脱敏后的内容
		private final String redactedContent;
		// 匹配项列表
		private final List<String> matches;

		// RedactionResult构造函数
		public RedactionResult(String redactedContent, List<String> matches) {
			this.redactedContent = redactedContent;
			this.matches = new ArrayList<>(matches);
		}

		// 获取脱敏后内容的方法
		public String getRedactedContent() { return redactedContent; }
		// 获取匹配项列表的方法
		public List<String> getMatches() { return new ArrayList<>(matches); }
	}

	/**
	 * Redaction rule for sanitizing command output.
	 */
	public interface RedactionRule {
		/**
		 * Apply redaction to content and return redacted content with matches.
		 */
		// 应用脱敏规则并返回脱敏结果和匹配项的方法
		RedactionResult applyWithMatches(String content);

		// 获取此规则检测的PII类型的方法
		String getPiiType();
	}

	/**
	 * Simple pattern-based redaction rule.
	 */
	public static class PatternRedactionRule implements RedactionRule {
		// 正则表达式模式
		private final Pattern pattern;
		// 替换字符串
		private final String replacement;
		// PII类型
		private final String piiType;

		// PatternRedactionRule构造函数
		public PatternRedactionRule(String pattern, String replacement, String piiType) {
			this.pattern = Pattern.compile(pattern);
			this.replacement = replacement;
			this.piiType = piiType;
		}

		// 实现脱敏规则应用的方法
		@Override
		public RedactionResult applyWithMatches(String content) {
			// 创建匹配项列表
			List<String> matches = new ArrayList<>();
			// 创建正则表达式匹配器
			java.util.regex.Matcher matcher = pattern.matcher(content);

			// 查找所有匹配项
			while (matcher.find()) {
				// 将匹配项添加到列表
				matches.add(matcher.group());
			}

			// 执行替换操作
			String redacted = matcher.replaceAll(replacement);
			// 返回脱敏结果
			return new RedactionResult(redacted, matches);
		}

		// 获取PII类型的方法
		@Override
		public String getPiiType() {
			return piiType;
		}
	}

	public static class Builder {
		// 工作空间根路径
		private Path workspaceRoot;
		// 启动命令列表
		private final List<String> startupCommands = new ArrayList<>();
		// 关闭命令列表
		private final List<String> shutdownCommands = new ArrayList<>();
		// 命令执行超时时间（毫秒）
		private long commandTimeout = 30000; // 30 seconds
		// 启动超时时间（毫秒）
		private long startupTimeout = 10000; // 10 seconds
		// 终止超时时间（毫秒）
		private long terminationTimeout = 5000; // 5 seconds
		// 最大输出行数
		private int maxOutputLines = 1000;
		// 最大输出字节数
		private Long maxOutputBytes = null;
		// shell命令列表
		private List<String> shellCommand = Arrays.asList("/bin/bash");
		// 环境变量映射
		private final Map<String, String> environment = new HashMap<>();
		// 数据脱敏规则列表
		private final List<RedactionRule> redactionRules = new ArrayList<>();

		// 设置工作空间根路径（字符串形式）
		public Builder workspaceRoot(String path) {
			this.workspaceRoot = Path.of(path);
			return this;
		}

		// 设置工作空间根路径（Path形式）
		public Builder workspaceRoot(Path path) {
			this.workspaceRoot = path;
			return this;
		}

		// 添加启动命令
		public Builder addStartupCommand(String command) {
			this.startupCommands.add(command);
			return this;
		}

		// 添加关闭命令
		public Builder addShutdownCommand(String command) {
			this.shutdownCommands.add(command);
			return this;
		}

		// 设置启动命令列表
		public Builder setStartupCommand(List<String> commands) {
			this.startupCommands.addAll(commands);
			return this;
		}

		// 设置关闭命令列表
		public Builder setShutdownCommand(List<String> commands) {
			this.shutdownCommands.addAll(commands);
			return this;
		}

		// 设置命令执行超时时间
		public Builder commandTimeout(long millis) {
			this.commandTimeout = millis;
			return this;
		}

		// 设置启动超时时间
		public Builder startupTimeout(long millis) {
			this.startupTimeout = millis;
			return this;
		}

		// 设置终止超时时间
		public Builder terminationTimeout(long millis) {
			this.terminationTimeout = millis;
			return this;
		}

		// 设置最大输出行数
		public Builder maxOutputLines(int lines) {
			this.maxOutputLines = lines;
			return this;
		}

		// 设置最大输出字节数
		public Builder maxOutputBytes(long bytes) {
			this.maxOutputBytes = bytes;
			return this;
		}

		// 设置shell命令列表
		public Builder shellCommand(List<String> command) {
			this.shellCommand = new ArrayList<>(command);
			return this;
		}

		// 设置环境变量
		public Builder environment(Map<String, String> env) {
			this.environment.putAll(env);
			return this;
		}

		// 添加数据脱敏规则
		public Builder addRedactionRule(RedactionRule rule) {
			this.redactionRules.add(rule);
			return this;
		}

		// 构建ShellSessionManager实例
		public ShellSessionManager build() {
			return new ShellSessionManager(this);
		}
	}
}

