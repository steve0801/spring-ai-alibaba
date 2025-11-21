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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Grep search tool for fast content search.
 * Searches file contents using regular expressions.
 * Supports full regex syntax and filters files by pattern with the include parameter.
 */
public class GrepSearchTool implements BiFunction<GrepSearchTool.Request, ToolContext, String> {

	private final Path rootPath;
	private final boolean useRipgrep;
	private final long maxFileSizeBytes;

	public GrepSearchTool(String rootPath) {
		this(rootPath, true, 10);
	}

	public GrepSearchTool(String rootPath, boolean useRipgrep, int maxFileSizeMb) {
		this.rootPath = Paths.get(rootPath).toAbsolutePath().normalize();
		this.useRipgrep = useRipgrep;
		this.maxFileSizeBytes = maxFileSizeMb * 1024L * 1024L;
	}

	public record Request(
			// 定义pattern属性，标记为必需，用于在文件内容中搜索的正则表达式模式
			@JsonProperty(required = true)
			// 为pattern属性添加描述信息，说明其用途
			@JsonPropertyDescription("The regular expression pattern to search for in file contents")
			String pattern,

			// 定义path属性，设置默认值为"/"，指定搜索目录
			@JsonProperty(defaultValue = "/")
			// 为path属性添加描述信息，说明其用途和默认行为
			@JsonPropertyDescription("The directory to search in. If not specified, searches from root.")
			String path,

			// 定义include属性，用于过滤文件的模式
			@JsonProperty
			// 为include属性添加描述信息，说明其用途和示例
			@JsonPropertyDescription("File pattern to filter (e.g., \"*.js\", \"*.{ts,tsx}\")")
			String include,

			// 定义outputMode属性，设置默认值为"files_with_matches"，指定输出格式
			@JsonProperty(defaultValue = "files_with_matches")
			// 为outputMode属性添加描述信息，说明可用的选项
			@JsonPropertyDescription("Output format: files_with_matches (default), content, or count")
			String outputMode
	) {
		// 请求记录类的构造器代码块
		public Request {
			// 如果path为null或空字符串，则将其设置为默认值"/"
			if (path == null || path.isEmpty()) {
				path = "/";
			}
			// 如果outputMode为null或空字符串，则将其设置为默认值"files_with_matches"
			if (outputMode == null || outputMode.isEmpty()) {
				outputMode = "files_with_matches";
			}
		}
	}

	// 实现BiFunction接口的apply方法，用于执行内容搜索操作
	@Override
	public String apply(Request request, ToolContext toolContext) {
		// 验证正则表达式模式的有效性
		try {
			Pattern.compile(request.pattern());
		} catch (PatternSyntaxException e) {
			// 如果正则表达式无效，返回错误信息
			return "Invalid regex pattern: " + e.getMessage();
		}

		// 如果提供了include模式，则验证其有效性
		if (request.include() != null && !isValidIncludePattern(request.include())) {
			// 如果include模式无效，返回错误信息
			return "Invalid include pattern";
		}

		// 初始化结果映射
		Map<String, List<MatchInfo>> results = null;
		// 如果启用了ripgrep，则首先尝试使用ripgrep进行搜索
		if (useRipgrep) {
			try {
				// 调用ripgrepSearch方法执行搜索
				results = ripgrepSearch(request.pattern(), request.path(), request.include());
			} catch (Exception e) {
				// 如果ripgrep搜索失败，则回退到Java搜索
			}
		}

		// 如果ripgrep搜索失败或被禁用，则使用Java搜索
		if (results == null) {
			// 调用javaSearch方法执行搜索
			results = javaSearch(request.pattern(), request.path(), request.include());
		}

		// 如果没有找到匹配项，返回相应提示
		if (results.isEmpty()) {
			return "No matches found";
		}

		// 格式化搜索结果并返回
		return formatResults(results, request.outputMode());
	}

	// 使用ripgrep工具进行搜索的方法
	private Map<String, List<MatchInfo>> ripgrepSearch(String pattern, String basePath, String include) {
		try {
			// 验证并解析基础路径
			Path baseFullPath = validateAndResolvePath(basePath);

			// 检查路径是否存在
			if (!Files.exists(baseFullPath)) {
				// 如果路径不存在，返回空映射
				return Collections.emptyMap();
			}

			// 构建ripgrep命令
			List<String> cmd = new ArrayList<>();
			// 添加ripgrep命令
			cmd.add("rg");
			// 添加--json参数以获取JSON格式输出
			cmd.add("--json");

			// 如果提供了include模式，则添加--glob参数
			if (include != null && !include.isEmpty()) {
				cmd.add("--glob");
				cmd.add(include);
			}

			// 添加分隔符
			cmd.add("--");
			// 添加搜索模式
			cmd.add(pattern);
			// 添加搜索路径
			cmd.add(baseFullPath.toString());

			// 创建进程构建器
			ProcessBuilder pb = new ProcessBuilder(cmd);
			// 重定向错误流到输出流
			pb.redirectErrorStream(true);
			// 启动进程
			Process process = pb.start();

			// 创建结果映射
			Map<String, List<MatchInfo>> results = new HashMap<>();

			// 读取进程输出
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				// 逐行读取输出
				while ((line = reader.readLine()) != null) {
					try {
						// 解析ripgrep的JSON输出（简化解析）
						if (line.contains("\"type\":\"match\"")) {
							// 这是一个简化的方法；在生产环境中应使用适当的JSON解析器
							results.putIfAbsent("ripgrep_result", new ArrayList<>());
						}
					} catch (Exception e) {
						// 跳过格式错误的行
					}
				}
			}

			// 等待进程完成，超时时间为30秒
			boolean finished = process.waitFor(30, TimeUnit.SECONDS);
			// 如果进程未在规定时间内完成，则强制销毁
			if (!finished) {
				process.destroyForcibly();
				return null; // 触发回退机制
			}

			// 如果ripgrep执行失败（退出码不是0或1），则触发回退
			if (process.exitValue() != 0 && process.exitValue() != 1) {
				return null;
			}

			// 为了简化，总是回退到Java搜索
			// 在生产环境中，应实现适当的ripgrep输出JSON解析
			return null;

		} catch (Exception e) {
			return null; // 触发回退到Java搜索
		}
	}

	// 使用Java实现的搜索方法
	private Map<String, List<MatchInfo>> javaSearch(String patternStr, String basePath, String include) {
		try {
			// 验证并解析基础路径
			Path baseFullPath = validateAndResolvePath(basePath);

			// 检查路径是否存在
			if (!Files.exists(baseFullPath)) {
				// 如果路径不存在，返回空映射
				return Collections.emptyMap();
			}

			// 编译正则表达式模式
			Pattern pattern = Pattern.compile(patternStr);
			// 创建结果映射，使用LinkedHashMap保持插入顺序
			Map<String, List<MatchInfo>> results = new LinkedHashMap<>();

			// 遍历文件树
			Files.walkFileTree(baseFullPath, new SimpleFileVisitor<Path>() {
				// 访问文件时的回调方法
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					// 检查文件是否匹配include模式
					if (include != null && !matchIncludePattern(file.getFileName().toString(), include)) {
						// 如果不匹配，则继续遍历其他文件
						return FileVisitResult.CONTINUE;
					}

					// 跳过过大的文件
					if (attrs.size() > maxFileSizeBytes) {
						// 如果文件过大，则继续遍历其他文件
						return FileVisitResult.CONTINUE;
					}

					try {
						// 读取文件内容
						String content = Files.readString(file, StandardCharsets.UTF_8);
						// 按行分割内容
						String[] lines = content.split("\r?\n");

						// 遍历每一行
						for (int i = 0; i < lines.length; i++) {
							// 创建匹配器
							Matcher matcher = pattern.matcher(lines[i]);
							// 检查是否匹配
							if (matcher.find()) {
								// 构造虚拟路径，统一使用正斜杠
								String virtualPath = "/" + rootPath.relativize(file).toString().replace("\\", "/");
								// 将匹配信息添加到结果映射中
								results.computeIfAbsent(virtualPath, k -> new ArrayList<>())
										.add(new MatchInfo(i + 1, lines[i]));
							}
						}
					} catch (IOException e) {
						// 跳过无法读取或二进制文件
					}

					// 继续遍历其他文件
					return FileVisitResult.CONTINUE;
				}

				// 文件访问失败时的回调方法
				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					// 跳过无法访问的文件
					return FileVisitResult.CONTINUE;
				}
			});

			// 返回搜索结果
			return results;

		} catch (Exception e) {
			// 发生异常时返回空映射
			return Collections.emptyMap();
		}
	}

	// 验证并解析路径的方法
	private Path validateAndResolvePath(String path) throws IOException {
		// 标准化路径，确保以"/"开头
		if (!path.startsWith("/")) {
			path = "/" + path;
		}

		// 检查路径遍历攻击
		if (path.contains("..") || path.contains("~")) {
			throw new IOException("Path traversal not allowed");
		}

		// 将虚拟路径转换为文件系统路径
		String relative = path.substring(1); // 移除开头的 "/"
		Path fullPath = rootPath.resolve(relative).normalize();

		// 确保路径在根目录范围内
		if (!fullPath.startsWith(rootPath)) {
			throw new IOException("Path outside root directory: " + path);
		}

		// 返回解析后的完整路径
		return fullPath;
	}

	// 验证include模式有效性的方法
	private boolean isValidIncludePattern(String pattern) {
		// 如果模式为null或空字符串，则无效
		if (pattern == null || pattern.isEmpty()) {
			return false;
		}

		// 检查是否包含无效字符
		return !pattern.contains("\0") && !pattern.contains("\n") && !pattern.contains("\r");
	}

	// 匹配include模式的方法
	private boolean matchIncludePattern(String filename, String pattern) {
		// 简单的glob匹配 - 将glob转换为正则表达式
		// 这是一个简化版本；在生产环境中应使用适当的glob库
		String regex = pattern
				.replace(".", "\\.")
				.replace("*", ".*")
				.replace("?", ".");

		// 处理大括号扩展，如 *.{js,ts}
		if (pattern.contains("{") && pattern.contains("}")) {
			regex = regex.replaceAll("\\{([^}]+)\\}", "($1)").replace(",", "|");
		}

		try {
			// 使用正则表达式匹配文件名
			return filename.matches(regex);
		} catch (PatternSyntaxException e) {
			// 如果正则表达式语法错误，则返回false
			return false;
		}
	}

	// 格式化搜索结果的方法
	private String formatResults(Map<String, List<MatchInfo>> results, String outputMode) {
		// 根据输出模式返回不同的格式化结果
		return switch (outputMode) {
			// files_with_matches模式：返回匹配文件的路径列表
			case "files_with_matches" -> results.keySet().stream()
					.sorted()
					.collect(Collectors.joining("\n"));

			// content模式：返回匹配的文件路径、行号和行内容
			case "content" -> results.entrySet().stream()
					.sorted(Map.Entry.comparingByKey())
					.flatMap(entry -> entry.getValue().stream()
							.map(info -> String.format("%s:%d:%s", entry.getKey(), info.lineNumber(), info.lineText())))
					.collect(Collectors.joining("\n"));

			// count模式：返回每个文件的匹配计数
			case "count" -> results.entrySet().stream()
					.sorted(Map.Entry.comparingByKey())
					.map(entry -> String.format("%s:%d", entry.getKey(), entry.getValue().size()))
					.collect(Collectors.joining("\n"));

			// 默认模式：返回匹配文件的路径列表
			default -> results.keySet().stream()
					.sorted()
					.collect(Collectors.joining("\n"));
		};
	}

	// 定义匹配信息记录类，包含行号和行文本
	private record MatchInfo(int lineNumber, String lineText) {}

	// 创建Builder实例的静态方法
	public static Builder builder(String rootPath) {
		return new Builder(rootPath);
	}

	// 内部Builder类，用于构建GrepSearchTool的ToolCallback实例
	public static class Builder {

		// 存储根路径
		private final String rootPath;

		// 工具名称，默认为"grep_search"
		private String name = "grep_search";

		// 工具描述信息
		private String description = "Fast content search tool that works with any codebase size. "
				+ "Searches file contents using regular expressions. "
				+ "Supports full regex syntax and filters files by pattern with the include parameter. "
				+ "Use this tool when you need to search for specific content within files.";

		// 是否使用ripgrep，默认为true
		private boolean useRipgrep = true;

		// 最大文件大小（MB），默认为10MB
		private int maxFileSizeMb = 10;

		// Builder构造函数
		public Builder(String rootPath) {
			this.rootPath = rootPath;
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

		// 设置是否使用ripgrep的方法
		public Builder withUseRipgrep(boolean useRipgrep) {
			this.useRipgrep = useRipgrep;
			return this;
		}

		// 设置最大文件大小的方法
		public Builder withMaxFileSizeMb(int maxFileSizeMb) {
			this.maxFileSizeMb = maxFileSizeMb;
			return this;
		}

		// 构建ToolCallback实例的方法
		public ToolCallback build() {
			return FunctionToolCallback
				.builder(name, new GrepSearchTool(rootPath, useRipgrep, maxFileSizeMb))
				.description(description)
				.inputType(Request.class)
				.build();
		}

	}

}
