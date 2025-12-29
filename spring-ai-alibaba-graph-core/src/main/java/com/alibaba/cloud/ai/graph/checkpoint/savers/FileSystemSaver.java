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
package com.alibaba.cloud.ai.graph.checkpoint.savers;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.serializer.Serializer;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.alibaba.cloud.ai.graph.serializer.check_point.CheckPointSerializer;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedList;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

/**
 * A CheckpointSaver that stores Checkpoints in the filesystem.
 * <p>
 * Each RunnableConfig is associated with a file in the provided targetFolder. The file is
 * named "thread-<i>threadId</i>.saver" if the RunnableConfig has a threadId, or
 * "thread-$default.saver" if it doesn't.
 * </p>
 */
// 文件系统检查点保存器类，继承自内存保存器
public class FileSystemSaver extends MemorySaver {

	// 日志记录器
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileSystemSaver.class);

	// 文件扩展名常量
	public static final String EXTENSION = ".saver";

	// 目标文件夹路径
	private final Path targetFolder;

	// 检查点序列化器
	private final Serializer<Checkpoint> serializer;

	// 文件系统保存器构造函数
	@SuppressWarnings("unchecked")
	public FileSystemSaver(Path targetFolder, StateSerializer stateSerializer) {

		// 检查状态序列化器是否为null
		Objects.requireNonNull(stateSerializer, "stateSerializer cannot be null");
		// 设置目标文件夹路径，确保不为null
		this.targetFolder = Objects.requireNonNull(targetFolder, "targetFolder cannot be null");
		// 创建检查点序列化器
		this.serializer = new CheckPointSerializer(stateSerializer);

		// 尝试创建目录
		try {
			// 检查目标文件夹是否存在且不是目录
			if (Files.exists(targetFolder) && !Files.isDirectory(targetFolder)) {
				// 如果目标文件夹存在但不是目录，抛出参数异常
				throw new IllegalArgumentException(format("targetFolder '%s' must be a directory", targetFolder));
			}
			// 创建目录
			Files.createDirectories(targetFolder);
		}
		// 捕获IO异常
		catch (IOException ex) {
			// 抛出参数异常，包含原始异常信息
			throw new IllegalArgumentException(format("targetFolder '%s' cannot be created", targetFolder), ex);
		}

	}

	// 获取基础文件名的方法
	private String getBaseName(RunnableConfig config) {
		// 获取线程ID，如果不存在则使用默认值
		var threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
		// 格式化返回线程文件名
		return format("thread-%s", threadId);
	}

	// 获取文件路径的方法
	private Path getPath(RunnableConfig config) {
		// 使用目标文件夹和基础文件名构建路径
		return Paths.get(targetFolder.toString(), getBaseName(config).concat(EXTENSION));
	}

	// 获取文件对象的方法
	private File getFile(RunnableConfig config) {
		// 将路径转换为文件对象
		return getPath(config).toFile();
	}

	// 序列化检查点列表到文件的方法
	private void serialize(LinkedList<Checkpoint> checkpoints, File outFile) throws IOException {
		// 检查检查点列表是否为null
		Objects.requireNonNull(checkpoints, "checkpoints cannot be null");
		// 检查输出文件是否为null
		Objects.requireNonNull(outFile, "outFile cannot be null");
		// 使用try-with-resources创建对象输出流
		try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(outFile.toPath()))) {

			// 写入检查点列表大小
			oos.writeInt(checkpoints.size());
			// 遍历检查点列表
			for (Checkpoint checkpoint : checkpoints) {
				// 使用序列化器写入检查点
				serializer.write(checkpoint, oos);
			}
		}
	}

	// 从文件反序列化检查点列表的方法
	private void deserialize(File file, LinkedList<Checkpoint> result) throws IOException, ClassNotFoundException {
		// 检查输入文件是否为null
		Objects.requireNonNull(file, "file cannot be null");
		// 检查结果列表是否为null
		Objects.requireNonNull(result, "result cannot be null");

		// 使用try-with-resources创建对象输入流
		try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(file.toPath()))) {
			// 读取检查点列表大小
			int size = ois.readInt();
			// 循环读取所有检查点
			for (int i = 0; i < size; i++) {
				// 使用序列化器读取检查点并添加到结果列表
				result.add(serializer.read(ois));
			}
		}
	}

	// 重写加载检查点的方法
	@Override
	protected LinkedList<Checkpoint> loadedCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints)
			throws Exception {

		// 获取目标文件
		File targetFile = getFile(config);
		// 检查文件是否存在且检查点列表为空
		if (targetFile.exists() && checkpoints.isEmpty()) {
			// 从文件反序列化检查点
			deserialize(targetFile, checkpoints);
		}
		// 返回检查点列表
		return checkpoints;

	}

	// 重写插入检查点的方法
	@Override
	protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint)
			throws Exception {
		// 获取目标文件
		File targetFile = getFile(config);
		// 序列化检查点列表到文件
		serialize(checkpoints, targetFile);
	}

	// 重写更新检查点的方法
	@Override
	protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint)
			throws Exception {
		// 调用插入检查点方法处理更新
		insertedCheckpoint(config, checkpoints, checkpoint);
	}

	/**
	 * Releases the checkpoints associated with the given configuration. This involves
	 * copying the current checkpoint file (e.g., "thread-123.saver") to a versioned
	 * backup file (e.g., "thread-123-v1.saver", "thread-123-v2.saver", etc.) based on
	 * existing versioned files, deleting the original unversioned file, and then clearing
	 * the in-memory checkpoints.
	 * @param config The configuration for which to release checkpoints.
	 * @param checkpoints released checkpoints
	 * @param releaseTag released Tag
	 * @throws Exception If an error occurs during file operations or releasing from
	 * memory.
	 */
	// 释放与给定配置关联的检查点
	@Override
	protected void releasedCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Tag releaseTag)
			throws Exception {
		// 获取当前文件路径
		var currentPath = getPath(config);

		// 检查文件是否存在
		if (!Files.exists(currentPath)) {
			// 记录警告日志
			log.warn("file {} doesn't exist. Skipping file operations.", currentPath);
			// 跳过文件操作
			return;
		}

		// 编译版本模式正则表达式
		var versionPattern = Pattern.compile(format("%s-v(\\d+)\\%s$", getBaseName(config), EXTENSION));

		// 初始化最大版本号
		int maxVersion = 0;
		// 使用try-with-resources列出目标文件夹中的文件
		try (var stream = Files.list(targetFolder)) {
			// 计算最大版本号
			maxVersion = stream.map(path -> path.getFileName().toString())
				.map(versionPattern::matcher)
				.filter(Matcher::matches)
				.mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
				.max()
				.orElse(0); // 如果没有找到版本文件，默认为0
		}
		// 捕获IO异常
		catch (IOException e) {
			// 记录错误日志
			log.error(
					"Failed to list directory {} to determine next version number for backup. Skipping file operations.",
					targetFolder, e);
			// 跳过文件操作
			return;
		}

		// 计算下一个版本号
		int nextVersion = maxVersion + 1;
		// 生成备份文件名
		var backupFilename = format("%s-v%d%s", getBaseName(config), nextVersion, EXTENSION);
		// 生成备份路径
		Path backupPath = targetFolder.resolve(backupFilename);

		// 复制当前文件到备份路径，替换已存在的文件
		Files.copy(currentPath, backupPath, StandardCopyOption.REPLACE_EXISTING);

		// 删除原文件
		Files.delete(currentPath);

	}

	/**
	 * Delete the checkpoint file associated with the given RunnableConfig.
	 * @param config the RunnableConfig for which the checkpoint file should be cleared
	 * @return true if the file existed and was successfully deleted, false otherwise
	 */
	// 删除与给定RunnableConfig关联的检查点文件
	public boolean deleteFile(RunnableConfig config) {
		// 获取文件路径
		Path path = getPath(config);
		// 尝试删除文件（如果存在）
		try {
			// 返回删除结果
			return Files.deleteIfExists(path);
		}
		// 捕获IO异常
		catch (IOException e) {
			// 记录警告日志
			log.warn("Failed to delete checkpoint file {}", path, e);
			// 返回删除失败
			return false;
		}
	}

}
