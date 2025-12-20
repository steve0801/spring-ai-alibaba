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
package com.alibaba.cloud.ai.graph.agent.extension.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Backend that reads and writes files directly from the filesystem.
 *
 * Files are accessed using their actual filesystem paths. Relative paths are
 * resolved relative to the current working directory. Content is read/written
 * as plain text, and metadata (timestamps) are derived from filesystem stats.
 *
 * Security and search upgrades:
 * - Secure path resolution with root containment when in virtual_mode (sandboxed to cwd)
 * - Prevent symlink-following on file I/O
 * - Ripgrep-powered grep with JSON parsing, plus Java fallback with regex
 *   and optional glob include filtering, while preserving virtual path behavior
 */
public class LocalFilesystemBackend implements FilesystemBackend {
	private static final String EMPTY_CONTENT_WARNING = "System reminder: File exists but has empty contents";
	private static final int MAX_LINE_LENGTH = 10000;
	private static final int LINE_NUMBER_WIDTH = 6;

	private final Path cwd;
	private final boolean virtualMode;
	private final long maxFileSizeBytes;

	/**
	 * Initialize filesystem backend.
	 *
	 * @param rootDir Optional root directory for file operations. If provided,
	 *                all file paths will be resolved relative to this directory.
	 *                If not provided, uses the current working directory.
	 * @param virtualMode When true, treat incoming paths as virtual absolute paths under
	 *                    cwd, disallow traversal (.., ~) and ensure resolved path stays within root.
	 * @param maxFileSizeMb Maximum file size in MB for reading operations
	 */
	public LocalFilesystemBackend(String rootDir, boolean virtualMode, int maxFileSizeMb) {
		this.cwd = rootDir != null ? Paths.get(rootDir).toAbsolutePath().normalize() : Paths.get("").toAbsolutePath();
		this.virtualMode = virtualMode;
		this.maxFileSizeBytes = maxFileSizeMb * 1024L * 1024L;
	}

	public LocalFilesystemBackend(String rootDir) {
		this(rootDir, false, 10);
	}

	/**
	 * Resolve a file path with security checks.
	 *
	 * When virtualMode=True, treat incoming paths as virtual absolute paths under
	 * cwd, disallow traversal (.., ~) and ensure resolved path stays within root.
	 * When virtualMode=False, preserve legacy behavior: absolute paths are allowed
	 * as-is; relative paths resolve under cwd.
	 */
	private Path resolvePath(String key) throws IllegalArgumentException {
		// 如果启用了虚拟模式
		if (virtualMode) {
			// 确保路径以 '/' 开头，构建虚拟路径
			String vpath = key.startsWith("/") ? key : "/" + key;
			// 检查是否包含非法字符（路径遍历）
			if (vpath.contains("..") || vpath.startsWith("~")) {
				throw new IllegalArgumentException("Path traversal not allowed");
			}
			// 将虚拟路径解析为实际路径并规范化
			Path full = cwd.resolve(vpath.substring(1)).normalize();
			// 确保解析后的路径仍在根目录内
			if (!full.startsWith(cwd)) {
				throw new IllegalArgumentException("Path:" + full + " outside root directory: " + cwd);
			}
			// 返回解析后的路径
			return full;
		}

		// 非虚拟模式下直接获取路径
		Path path = Paths.get(key);
		// 如果是绝对路径则直接返回
		if (path.isAbsolute()) {
			return path;
		}
		// 否则相对于当前工作目录解析并返回
		return cwd.resolve(path).normalize();
	}

	@Override
	public List<FileInfo> lsInfo(String path) {
		try {
			// 解析输入路径
			Path dirPath = resolvePath(path);
			// 检查路径是否存在且为目录
			if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
				return Collections.emptyList();
			}

			// 创建结果列表
			List<FileInfo> results = new ArrayList<>();
			// 获取当前工作目录字符串，并确保以'/'结尾
			String cwdStr = cwd.toString();
			if (!cwdStr.endsWith("/")) {
				cwdStr += "/";
			}

			// 列出目录中的所有项目
			try (Stream<Path> paths = Files.list(dirPath)) {
				// 遍历每个子路径
				for (Path childPath : paths.collect(Collectors.toList())) {
					try {
						// 检查是否为普通文件（不跟随符号链接）
						boolean isFile = Files.isRegularFile(childPath, LinkOption.NOFOLLOW_LINKS);
						// 检查是否为目录（不跟随符号链接）
						boolean isDir = Files.isDirectory(childPath, LinkOption.NOFOLLOW_LINKS);

						// 获取绝对路径字符串
						String absPath = childPath.toString();

						// 非虚拟模式处理
						if (!virtualMode) {
							// 处理普通文件
							if (isFile) {
								try {
									// 读取文件基本属性
									BasicFileAttributes attrs = Files.readAttributes(childPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
									// 添加文件信息到结果列表
									results.add(new FileInfo(
										absPath,
										false,
										attrs.size(),
										formatTimestamp(attrs.lastModifiedTime().toInstant())
									));
								} catch (IOException e) {
									// 出现IO异常时添加基本信息
									results.add(new FileInfo(absPath, false, null, null));
								}
							} else if (isDir) {
								// 处理目录
								try {
									// 读取目录基本属性
									BasicFileAttributes attrs = Files.readAttributes(childPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
									// 添加目录信息到结果列表
									results.add(new FileInfo(
										absPath + "/",
										true,
										0L,
										formatTimestamp(attrs.lastModifiedTime().toInstant())
									));
								} catch (IOException e) {
									// 出现IO异常时添加基本信息
									results.add(new FileInfo(absPath + "/", true, null, null));
								}
							}
						} else {
							// 虚拟模式处理
							// 计算相对路径
							String relativePath;
							// 根据不同情况计算相对路径
							if (absPath.startsWith(cwdStr)) {
								relativePath = absPath.substring(cwdStr.length());
							} else if (absPath.startsWith(cwd.toString())) {
								relativePath = absPath.substring(cwd.toString().length());
								if (relativePath.startsWith("/")) {
									relativePath = relativePath.substring(1);
								}
							} else {
								relativePath = absPath;
							}

							// 构建虚拟路径
							String virtPath = "/" + relativePath;

							// 处理普通文件
							if (isFile) {
								try {
									// 读取文件基本属性
									BasicFileAttributes attrs = Files.readAttributes(childPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
									// 添加文件信息到结果列表（使用虚拟路径）
									results.add(new FileInfo(
										virtPath,
										false,
										attrs.size(),
										formatTimestamp(attrs.lastModifiedTime().toInstant())
									));
								} catch (IOException e) {
									// 出现IO异常时添加基本信息
									results.add(new FileInfo(virtPath, false, null, null));
								}
							} else if (isDir) {
								// 处理目录
								try {
									// 读取目录基本属性
									BasicFileAttributes attrs = Files.readAttributes(childPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
									// 添加目录信息到结果列表（使用虚拟路径）
									results.add(new FileInfo(
										virtPath + "/",
										true,
										0L,
										formatTimestamp(attrs.lastModifiedTime().toInstant())
									));
								} catch (IOException e) {
									// 出现IO异常时添加基本信息
									results.add(new FileInfo(virtPath + "/", true, null, null));
								}
							}
						}
					} catch (Exception ignored) {
						// 忽略无法访问的文件
					}
				}
			}

			// 按路径排序以保证顺序一致性
			results.sort(Comparator.comparing(FileInfo::getPath));
			// 返回结果列表
			return results;
		} catch (Exception e) {
			// 发生异常时返回空列表
			return Collections.emptyList();
		}
	}

	@Override
	public String read(String filePath, int offset, int limit) {
		try {
			// 解析文件路径
			Path resolvedPath = resolvePath(filePath);

			// 检查文件是否存在且为普通文件
			if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath, LinkOption.NOFOLLOW_LINKS)) {
				return "Error: File '" + filePath + "' not found";
			}

			// 读取文件全部内容
			String content = new String(Files.readAllBytes(resolvedPath), StandardCharsets.UTF_8);

			// 检查文件是否为空
			String emptyMsg = checkEmptyContent(content);
			if (emptyMsg != null) {
				return emptyMsg;
			}

			// 按行分割内容
			String[] lines = content.split("\n", -1);
			// 移除末尾的空行（如果存在）
			if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
				lines = Arrays.copyOf(lines, lines.length - 1);
			}

			// 计算起始和结束索引
			int startIdx = offset;
			int endIdx = Math.min(startIdx + limit, lines.length);

			// 检查偏移量是否超出范围
			if (startIdx >= lines.length) {
				return "Error: Line offset " + offset + " exceeds file length (" + lines.length + " lines)";
			}

			// 截取指定范围的行
			String[] selectedLines = Arrays.copyOfRange(lines, startIdx, endIdx);
			// 格式化内容并添加行号
			return formatContentWithLineNumbers(selectedLines, startIdx + 1);
		} catch (IllegalArgumentException e) {
			// 处理路径解析异常
			return "Error: " + e.getMessage();
		} catch (IOException e) {
			// 处理文件读取异常
			return "Error reading file '" + filePath + "': " + e.getMessage();
		}
	}

	@Override
	public WriteResult write(String filePath, String content) {
		try {
			// 解析目标文件路径
			Path resolvedPath = resolvePath(filePath);

			// 检查文件是否已存在
			if (Files.exists(resolvedPath)) {
				return new WriteResult(null,
					"Cannot write to " + filePath + " because it already exists. Read and then make an edit, or write to a new path.",
					null);
			}

			// 创建父目录（如果需要）
			Path parent = resolvedPath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}

			// 写入文件内容
			Files.write(resolvedPath, content.getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);

			// 返回成功写入结果
			return new WriteResult(filePath, null, null);
		} catch (IllegalArgumentException e) {
			// 处理路径解析异常
			return new WriteResult(null, "Error: " + e.getMessage(), null);
		} catch (IOException e) {
			// 处理文件写入异常
			return new WriteResult(null, "Error writing file '" + filePath + "': " + e.getMessage(), null);
		}
	}

	@Override
	public EditResult edit(String filePath, String oldString, String newString, boolean replaceAll) {
		try {
			// 解析文件路径
			Path resolvedPath = resolvePath(filePath);

			// 检查文件是否存在且为普通文件
			if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath, LinkOption.NOFOLLOW_LINKS)) {
				return new EditResult(null, 0, "Error: File '" + filePath + "' not found", null);
			}

			// 读取文件全部内容
			String content = new String(Files.readAllBytes(resolvedPath), StandardCharsets.UTF_8);

			// 统计要替换的字符串出现次数
			int occurrences = countOccurrences(content, oldString);

			// 如果未找到要替换的字符串，返回错误
			if (occurrences == 0) {
				return new EditResult(null, 0, "Error: String not found in file: '" + oldString + "'", null);
			}

			// 如果出现多次但未设置replaceAll标志，返回错误提示
			if (occurrences > 1 && !replaceAll) {
				return new EditResult(null, 0,
					"Error: String '" + oldString + "' appears " + occurrences +
					" times in file. Use replaceAll=true to replace all instances, or provide a more specific string with surrounding context.",
					null);
			}

			// 执行字符串替换操作
			String newContent = content.replace(oldString, newString);

			// 将新内容写回文件
			Files.write(resolvedPath, newContent.getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);

			// 返回成功的编辑结果
			return new EditResult(filePath, occurrences, null, null);
		} catch (IllegalArgumentException e) {
			// 处理路径解析异常
			return new EditResult(null, 0, "Error: " + e.getMessage(), null);
		} catch (IOException e) {
			// 处理文件读写异常
			return new EditResult(null, 0, "Error editing file '" + filePath + "': " + e.getMessage(), null);
		}
	}

	@Override
	public List<FileInfo> globInfo(String pattern, String path) {
		try {
			// 移除模式开头的斜杠
			if (pattern.startsWith("/")) {
				pattern = pattern.substring(1);
			}

			// 解析搜索路径
			Path searchPath = "/".equals(path) ? cwd : resolvePath(path);
			// 检查路径是否存在且为目录
			if (!Files.exists(searchPath) || !Files.isDirectory(searchPath)) {
				return Collections.emptyList();
			}

			// 初始化结果列表
			List<FileInfo> results = new ArrayList<>();
			// 获取glob模式
			String globPattern = pattern;

			// 使用PathMatcher进行glob模式匹配
			PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
			// 构造当前工作目录字符串
			final String cwdStr = cwd.toString() + (cwd.toString().endsWith("/") ? "" : "/");

			// 使用递归遍历匹配子目录中的文件
			Files.walkFileTree(searchPath, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					try {
						// 只处理普通文件
						if (!attrs.isRegularFile()) {
							return FileVisitResult.CONTINUE;
						}

						// 计算相对于搜索路径的相对路径
						Path relativePath = searchPath.relativize(file);
						// 检查是否匹配glob模式
						if (matcher.matches(relativePath)) {
							// 获取文件绝对路径
							String absPath = file.toString();

							// 非虚拟模式处理
							if (!virtualMode) {
								try {
									// 添加文件信息到结果列表
									results.add(new FileInfo(
										absPath,
										false,
										attrs.size(),
										formatTimestamp(attrs.lastModifiedTime().toInstant())
									));
								} catch (Exception e) {
									// 出现异常时添加基本信息
									results.add(new FileInfo(absPath, false, null, null));
								}
							} else {
								// 虚拟模式处理
								String relPath;
								// 根据不同情况计算相对路径
								if (absPath.startsWith(cwdStr)) {
									relPath = absPath.substring(cwdStr.length());
								} else if (absPath.startsWith(cwd.toString())) {
									relPath = absPath.substring(cwd.toString().length());
									if (relPath.startsWith("/")) {
										relPath = relPath.substring(1);
									}
								} else {
									relPath = absPath;
								}
								// 构建虚拟路径
								String virt = "/" + relPath;
								try {
									// 添加文件信息到结果列表（使用虚拟路径）
									results.add(new FileInfo(
										virt,
										false,
										attrs.size(),
										formatTimestamp(attrs.lastModifiedTime().toInstant())
									));
								} catch (Exception e) {
									// 出现异常时添加基本信息
									results.add(new FileInfo(virt, false, null, null));
								}
							}
						}
					} catch (Exception ignored) {
						// 忽略无法访问的文件
					}
					// 继续遍历
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					// 忽略访问失败的文件，继续遍历
					return FileVisitResult.CONTINUE;
				}
			});

			// 按路径排序以保证顺序一致性
			results.sort(Comparator.comparing(FileInfo::getPath));
			// 返回结果列表
			return results;
		} catch (Exception e) {
			// 发生异常时返回空列表
			return Collections.emptyList();
		}
	}

	@Override
	public Object grepRaw(String pattern, String path, String glob) {
		// 验证正则表达式语法
		try {
			Pattern.compile(pattern);
		} catch (PatternSyntaxException e) {
			return "Invalid regex pattern: " + e.getMessage();
		}

		// 解析基础路径
		Path baseFull;
		try {
			baseFull = resolvePath(path != null ? path : ".");
		} catch (IllegalArgumentException e) {
			return Collections.emptyList();
		}

		// 检查路径是否存在
		if (!Files.exists(baseFull)) {
			return Collections.emptyList();
		}

		// 首先尝试使用ripgrep进行搜索
		Map<String, List<LineMatch>> results = ripgrepSearch(pattern, baseFull, glob);
		// 如果ripgrep不可用，则使用Java实现的搜索
		if (results == null) {
			results = javaSearch(pattern, baseFull, glob);
		}

		// 构造匹配结果列表
		List<GrepMatch> matches = new ArrayList<>();
		// 遍历所有匹配结果
		for (Map.Entry<String, List<LineMatch>> entry : results.entrySet()) {
			// 遍历每条匹配的行
			for (LineMatch lm : entry.getValue()) {
				// 添加到最终结果列表
				matches.add(new GrepMatch(entry.getKey(), lm.lineNum, lm.lineText));
			}
		}
		// 返回匹配结果
		return matches;
	}

	// Helper methods

	private Map<String, List<LineMatch>> ripgrepSearch(String pattern, Path baseFull, String includeGlob) {
		// 构建ripgrep命令行参数
		List<String> cmd = new ArrayList<>();
		cmd.add("rg");
		cmd.add("--json");
		// 如果有包含glob模式，则添加相应参数
		if (includeGlob != null) {
			cmd.add("--glob");
			cmd.add(includeGlob);
		}
		cmd.add("--");
		cmd.add(pattern);
		cmd.add(baseFull.toString());

		try {
			// 启动ripgrep进程
			ProcessBuilder pb = new ProcessBuilder(cmd);
			Process process = pb.start();

			// 初始化结果映射
			Map<String, List<LineMatch>> results = new HashMap<>();
			// 创建JSON解析器
			ObjectMapper mapper = new ObjectMapper();

			// 读取ripgrep输出
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				// 逐行读取输出
				while ((line = reader.readLine()) != null) {
					try {
						// 解析JSON数据
						JsonNode data = mapper.readTree(line);
						// 只处理匹配类型的数据
						if (!"match".equals(data.path("type").asText())) {
							continue;
						}
						// 获取匹配数据
						JsonNode pdata = data.path("data");
						// 获取文件路径
						String ftext = pdata.path("path").path("text").asText(null);
						if (ftext == null) {
							continue;
						}
						// 构造路径对象
						Path p = Paths.get(ftext);
						// 根据虚拟模式计算虚拟路径
						String virt;
						if (virtualMode) {
							try {
								// 计算相对于当前工作目录的虚拟路径
								Path resolved = p.toAbsolutePath().normalize();
								Path relative = cwd.relativize(resolved);
								virt = "/" + relative.toString().replace("\\", "/");
							} catch (Exception e) {
								continue;
							}
						} else {
							// 非虚拟模式直接使用路径
							virt = p.toString();
						}
						// 获取行号
						Integer ln = pdata.path("line_number").asInt(0);
						if (ln == 0) {
							continue;
						}
						// 获取匹配行文本
						String lt = pdata.path("lines").path("text").asText("");
						if (lt.endsWith("\n")) {
							lt = lt.substring(0, lt.length() - 1);
						}
						// 将结果添加到映射中
						results.computeIfAbsent(virt, k -> new ArrayList<>())
							.add(new LineMatch(ln, lt));
					} catch (Exception ignored) {
						// 忽略格式错误的JSON行
					}
				}
			}

			// 等待进程执行完成
			process.waitFor();
			// 返回搜索结果
			return results;
		} catch (Exception e) {
			// ripgrep不可用或执行失败，返回null以启用Java搜索回退
			return null;
		}
	}

	private Map<String, List<LineMatch>> javaSearch(String pattern, Path baseFull, String includeGlob) {
		// 编译正则表达式模式
		Pattern regex;
		try {
			regex = Pattern.compile(pattern);
		} catch (PatternSyntaxException e) {
			// 正则表达式语法错误时返回空映射
			return Collections.emptyMap();
		}

		// 初始化结果映射
		Map<String, List<LineMatch>> results = new HashMap<>();
		// 确定搜索根目录
		Path root = Files.isDirectory(baseFull) ? baseFull : baseFull.getParent();

		try {
			// 递归遍历文件树
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path fp, BasicFileAttributes attrs) {
					// 只处理普通文件
					if (!attrs.isRegularFile()) {
						return FileVisitResult.CONTINUE;
					}
					// 如果指定了包含glob模式，检查文件名是否匹配
					if (includeGlob != null) {
						PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + includeGlob);
						if (!matcher.matches(fp.getFileName())) {
							return FileVisitResult.CONTINUE;
						}
					}
					try {
						// 检查文件大小是否超过限制
						if (attrs.size() > maxFileSizeBytes) {
							return FileVisitResult.CONTINUE;
						}
						// 读取文件内容
						String content = new String(Files.readAllBytes(fp), StandardCharsets.UTF_8);
						// 按行分割内容
						String[] lines = content.split("\n", -1);
						// 遍历每一行
						for (int lineNum = 1; lineNum <= lines.length; lineNum++) {
							String line = lines[lineNum - 1];
							// 使用正则表达式匹配行
							Matcher m = regex.matcher(line);
							if (m.find()) {
								// 根据虚拟模式计算文件路径
								String virtPath;
								if (virtualMode) {
									try {
										// 计算相对于当前工作目录的虚拟路径
										Path resolved = fp.toAbsolutePath().normalize();
										Path relative = cwd.relativize(resolved);
										virtPath = "/" + relative.toString().replace("\\", "/");
									} catch (Exception e) {
										continue;
									}
								} else {
									// 非虚拟模式直接使用文件路径
									virtPath = fp.toString();
								}
								// 将匹配结果添加到映射中
								results.computeIfAbsent(virtPath, k -> new ArrayList<>())
									.add(new LineMatch(lineNum, line));
							}
						}
					} catch (Exception ignored) {
						// 忽略无法读取的文件
					}
					// 继续遍历
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exc) {
					// 忽略访问失败的文件，继续遍历
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ignored) {
			// 发生IO异常时返回已有的结果
		}

		// 返回搜索结果
		return results;
	}

	private String formatContentWithLineNumbers(String[] lines, int startLine) {
		// 创建结果字符串构建器
		StringBuilder result = new StringBuilder();
		// 遍历每一行
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			// 计算行号
			int lineNum = i + startLine;

			// 检查行长度是否超过最大限制
			if (line.length() <= MAX_LINE_LENGTH) {
				// 未超过限制，直接格式化添加行号
				result.append(String.format("%" + LINE_NUMBER_WIDTH + "d\t%s\n", lineNum, line));
			} else {
				// 超过限制，需要分块处理
				// 计算需要分成多少块
				int numChunks = (line.length() + MAX_LINE_LENGTH - 1) / MAX_LINE_LENGTH;
				// 遍历每个块
				for (int chunkIdx = 0; chunkIdx < numChunks; chunkIdx++) {
					// 计算块的起始和结束位置
					int start = chunkIdx * MAX_LINE_LENGTH;
					int end = Math.min(start + MAX_LINE_LENGTH, line.length());
					// 提取块内容
					String chunk = line.substring(start, end);
					if (chunkIdx == 0) {
						// 第一块，正常添加行号
						result.append(String.format("%" + LINE_NUMBER_WIDTH + "d\t%s\n", lineNum, chunk));
					} else {
						// 后续块，使用延续标记
						String continuationMarker = lineNum + "." + chunkIdx;
						result.append(String.format("%" + LINE_NUMBER_WIDTH + "s\t%s\n", continuationMarker, chunk));
					}
				}
			}
		}
		// 移除末尾换行符
		if (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
			result.setLength(result.length() - 1);
		}
		// 返回格式化结果
		return result.toString();
	}

	private String checkEmptyContent(String content) {
		// 检查内容是否为空或仅包含空白字符
		if (content == null || content.trim().isEmpty()) {
			// 返回空内容警告
			return EMPTY_CONTENT_WARNING;
		}
		// 内容非空，返回null
		return null;
	}

	private int countOccurrences(String content, String search) {
		// 初始化计数器
		int count = 0;
		// 初始化搜索起始位置
		int index = 0;
		// 循环查找所有匹配项
		while ((index = content.indexOf(search, index)) != -1) {
			// 找到匹配项，计数器加1
			count++;
			// 更新搜索起始位置
			index += search.length();
		}
		// 返回匹配次数
		return count;
	}

	private String formatTimestamp(Instant instant) {
		// 将时间戳格式化为ISO格式的UTC时间字符串
		return instant.atOffset(ZoneOffset.UTC)
			.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}

	private static class LineMatch {
		// 行号
		final int lineNum;
		// 行文本
		final String lineText;

		// 构造函数
		LineMatch(int lineNum, String lineText) {
			this.lineNum = lineNum;
			this.lineText = lineText;
		}
	}
}

