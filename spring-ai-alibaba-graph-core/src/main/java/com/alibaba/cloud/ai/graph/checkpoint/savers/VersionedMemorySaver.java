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
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.HasVersions;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;

/**
 * VersionedMemorySaver is a class that implements {@link BaseCheckpointSaver} and
 * {@link HasVersions}. It provides methods to save checkpoints with versioning and
 * retrieve them based on thread IDs and versions. Experimental feature
 */
	// 版本化内存保存器类，实现BaseCheckpointSaver和HasVersions接口
public class VersionedMemorySaver implements BaseCheckpointSaver, HasVersions {

	// 按线程ID存储检查点历史记录的映射表，每个线程对应一个按版本排序的树映射
	final Map<String, TreeMap<Integer, Tag>> _checkpointsHistoryByThread = new HashMap<>();

	// 无版本内存保存器实例，用于处理非版本化操作
	final MemorySaver noVersionSaver = new MemorySaver();

	// 可重入锁，用于线程安全操作
	private final ReentrantLock _lock = new ReentrantLock();

	/**
	 * VersionedMemorySaver类的默认构造函数。初始化具有默认设置的类的新实例。
	 */
	public VersionedMemorySaver() {
	}

	/**
	 * 根据指定的线程ID检索检查点历史记录
	 * @param threadId 要检索检查点历史记录的线程ID
	 * @return 如果线程存在，则返回包含表示检查点历史记录的TreeMap<Integer, Tag>的Optional；否则返回空Optional
	 */
	// 根据线程ID获取检查点历史记录
	private Optional<TreeMap<Integer, Tag>> getCheckpointHistoryByThread(String threadId) {
		// 从映射表中根据线程ID获取检查点历史记录，如果不存在则返回空Optional
		return ofNullable(_checkpointsHistoryByThread.get(threadId));
		// .orElseThrow( () -> new IllegalArgumentException( format("Thread %s not found",
		// threadId )) );
	}

	/**
	 * 根据提供的版本检索可选的标签
	 * @param checkpointsHistory 包含按版本索引的历史标签的映射
	 * @param threadVersion 要检索标签的版本
	 * @return 包含给定版本关联标签的Optional，如果未找到则返回空Optional
	 */
	// 根据版本获取标签
	final Optional<Tag> getTagByVersion(TreeMap<Integer, Tag> checkpointsHistory, int threadVersion) {
		// 获取锁以确保线程安全
		_lock.lock();
		try {
			// 从历史记录映射中获取指定版本的标签
			return ofNullable(checkpointsHistory.get(threadVersion));

		}
		// 确保在操作完成后释放锁
		finally {
			_lock.unlock();
		}

	}

	/**
	 * 获取特定线程版本的检查点
	 * @param threadId 线程ID
	 * @param threadVersion 线程版本
	 * @return 指定线程版本的检查点集合
	 * @throws IllegalArgumentException 如果给定线程找不到版本
	 */
	// 根据线程版本获取检查点
	final Collection<Checkpoint> getCheckpointsByVersion(String threadId, int threadVersion) {

		// 获取锁以确保线程安全
		_lock.lock();
		try {
			// 获取指定线程的历史记录，然后获取指定版本的标签，再获取标签中的检查点
			return getCheckpointHistoryByThread(threadId).map(history -> history.get(threadVersion))
				.map(Tag::checkpoints)
				// 如果未找到版本，则抛出IllegalArgumentException异常
				.orElseThrow(() -> new IllegalArgumentException(
						format("Version %s for thread %s not found", threadVersion, threadId)));

		}
		// 确保在操作完成后释放锁
		finally {
			_lock.unlock();
		}
	}

	/**
	 * 返回与指定线程ID关联的版本集合
	 * @param threadId 要检索版本的线程ID；如果为null，则使用默认值
	 * @return 版本集合，如果没有找到版本则返回空集合
	 */
	// 获取指定线程ID的版本列表
	@Override
	public Collection<Integer> versionsByThreadId(String threadId) {
		// 获取线程ID的检查点历史记录，并返回其键集合（版本号集合）
		return getCheckpointHistoryByThread(ofNullable(threadId).orElse(THREAD_ID_DEFAULT))
			.map(history -> (Collection<Integer>) history.keySet())
			// 如果未找到历史记录，则返回空集合
			.orElse(Collections.emptyList());
	}

	/**
	 * 获取线程ID的最后版本
	 * @param threadId 要检索最后版本的线程ID，如果未指定则为null
	 * @return 包含最后版本号的Optional，如果没有找到版本则为空Optional
	 */
	// 获取线程ID的最新版本
	@Override
	public Optional<Integer> lastVersionByThreadId(String threadId) {
		// 获取线程ID的检查点历史记录，并返回其最后的键（最大版本号）
		return getCheckpointHistoryByThread(ofNullable(threadId).orElse(THREAD_ID_DEFAULT)).map(TreeMap::lastKey);
	}

	/**
	 * 根据提供的配置列出检查点
	 * @param config 包含配置详细信息的RunnableConfig对象
	 * @return Checkpoint对象的集合
	 * @throws RuntimeException 如果在列出过程中发生错误
	 */
	// 列出检查点
	@Override
	public Collection<Checkpoint> list(RunnableConfig config) {
		// 获取锁以确保线程安全
		_lock.lock();
		try {
			// 使用无版本保存器列出检查点
			return noVersionSaver.list(config);
		}
		// 确保在操作完成后释放锁
		finally {
			_lock.unlock();
		}
	}

	/**
	 * 获取给定配置的可选检查点
	 * @param config 用于检索检查点的配置，不为null
	 * @return 如果找到则包含检查点的Optional，如果未找到则为空
	 */
	// 获取检查点
	@Override
	public Optional<Checkpoint> get(RunnableConfig config) {

		// 获取锁以确保线程安全
		_lock.lock();
		try {

			// 使用无版本保存器获取检查点
			return noVersionSaver.get(config);

		}
		// 确保在操作完成后释放锁
		finally {
			_lock.unlock();
		}
	}

	/**
	 * 使用指定的检查点更新或插入给定的RunnableConfig
	 * @param config 要更新或插入的RunnableConfig
	 * @param checkpoint 与RunnableConfig关联的Checkpoint
	 * @return 如果存在则返回之前的RunnableConfig，否则返回null
	 * @throws Exception 如果在更新或插入过程中发生错误
	 */
	// 保存检查点
	@Override
	public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {

		// 获取锁以确保线程安全
		_lock.lock();
		try {
			// 使用无版本保存器保存检查点
			return noVersionSaver.put(config, checkpoint);
		}
		// 确保在操作完成后释放锁
		finally {
			_lock.unlock();
		}
	}

	// 清除检查点配置
	@Override
	public boolean clear(RunnableConfig config) {
		// 返回false表示不支持清除操作
		return false;
	}

	/**
	 * 基于提供的RunnableConfig释放标签
	 * @param config 释放操作的配置
	 * @return 表示释放标签的Tag
	 * @throws Exception 如果在释放过程中发生错误
	 */
	// 释放标签
	@Override
	public Tag release(RunnableConfig config) throws Exception {

		// 获取锁以确保线程安全
		_lock.lock();
		try {

			// 获取配置中的线程ID，如果不存在则使用默认值
			var threadId = config.threadId().orElse(THREAD_ID_DEFAULT);

			// 使用无版本保存器释放标签
			var tag = noVersionSaver.release(config);

			// 获取或创建线程的检查点历史记录映射
			var checkpointsHistory = _checkpointsHistoryByThread.computeIfAbsent(threadId, k -> new TreeMap<>());

			// 获取当前最大版本号，如果不存在则为0
			var threadVersion = ofNullable(checkpointsHistory.lastEntry()).map(Map.Entry::getKey).orElse(0);

			// 将新版本的标签存入历史记录
			checkpointsHistory.put(threadVersion + 1, tag);

			// 返回释放的标签
			return tag;

		}
		// 确保在操作完成后释放锁
		finally {
			_lock.unlock();
		}
	}

}
