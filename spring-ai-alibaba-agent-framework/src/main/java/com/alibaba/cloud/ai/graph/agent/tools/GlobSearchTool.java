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

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Glob search tool for fast file pattern matching.
 * Supports glob patterns like **&#47;*.js or src/**&#47;*.ts.
 * Returns matching file paths sorted by modification time.
 */
public class GlobSearchTool implements BiFunction<GlobSearchTool.Request, ToolContext, String> {

	private final Path rootPath;

	public GlobSearchTool(String rootPath) {
		this.rootPath = Paths.get(rootPath).toAbsolutePath().normalize();
	}

	public record Request(
			// 定义pattern属性，标记为必需，用于匹配文件的glob模式
			@JsonProperty(required = true)
			// 为pattern属性添加描述信息，说明其用途
			@JsonPropertyDescription("The glob pattern to match files against")
			String pattern,

			// 定义path属性，设置默认值为"/"，指定搜索目录
			@JsonProperty(defaultValue = "/")
			// 为path属性添加描述信息，说明其用途和默认行为
			@JsonPropertyDescription("The directory to search in. If not specified, searches from root.")
			String path
	) {
		// 请求记录类的构造器代码块
		public Request {
			// 如果path为null或空字符串，则将其设置为默认值"/"
			if (path == null || path.isEmpty()) {
				path = "/";
			}
		}
	}

	// 实现BiFunction接口的apply方法，用于执行文件搜索操作
	@Override
	public String apply(Request request, ToolContext toolContext) {
		try {
			// 验证并解析请求中的路径参数，获取基础搜索路径
			Path basePath = validateAndResolvePath(request.path());

			// 检查基础路径是否存在且为目录，如果不是则返回未找到文件
			if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
				return "No files found";
			}

			// 使用PathMatcher进行glob模式匹配
			PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + request.pattern());
			// 创建用于存储匹配文件信息的列表
			List<FileInfo> matchingFiles = new ArrayList<>();

			// 遍历文件树，查找匹配的文件
			Files.walkFileTree(basePath, new SimpleFileVisitor<>() {
				// 访问文件时的回调方法
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					// 计算相对于基础路径的相对路径
					Path relativePath = basePath.relativize(file);
					// 检查文件是否匹配glob模式
					if (matcher.matches(relativePath)) {
						try {
							// 构造虚拟路径，统一使用正斜杠
							String virtualPath = "/" + rootPath.relativize(file).toString().replace("\\", "/");
							// 获取文件最后修改时间
							Instant modifiedTime = attrs.lastModifiedTime().toInstant();
							// 将匹配的文件信息添加到列表中
							matchingFiles.add(new FileInfo(virtualPath, modifiedTime));
						} catch (Exception e) {
							// 跳过无法处理的文件
						}
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

			// 如果没有找到匹配的文件，返回相应提示
			if (matchingFiles.isEmpty()) {
				return "No files found";
			}

			// 按修改时间排序（最近的在前）
			matchingFiles.sort(Comparator.comparing(FileInfo::modifiedTime).reversed());

			// 将匹配文件的路径连接成字符串返回
			return matchingFiles.stream()
					.map(FileInfo::path)
					.collect(Collectors.joining("\n"));

		} catch (Exception e) {
			// 发生异常时返回未找到文件
			return "No files found";
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

	// 定义文件信息记录类，包含路径和修改时间
	private record FileInfo(String path, Instant modifiedTime) {}

	// 创建Builder实例的静态方法
	public static Builder builder(String rootPath) {
		return new Builder(rootPath);
	}

	// 内部Builder类，用于构建GlobSearchTool的ToolCallback实例
	public static class Builder {

		// 存储根路径
		private final String rootPath;

		// 工具名称，默认为"glob_search"
		private String name = "glob_search";

		// 工具描述信息
		private String description = "Fast file pattern matching tool that works with any codebase size. "
				+ "Supports glob patterns like **/*.js or src/**/*.ts. "
				+ "Returns matching file paths sorted by modification time. "
				+ "Use this tool when you need to find files by name patterns.";

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

		// 构建ToolCallback实例的方法
		public ToolCallback build() {
			return FunctionToolCallback.builder(name, new GlobSearchTool(rootPath))
				.description(description)
				.inputType(Request.class)
				.build();
		}

	}

}
