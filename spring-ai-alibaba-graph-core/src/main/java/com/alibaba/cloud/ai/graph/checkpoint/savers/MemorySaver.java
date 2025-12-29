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
import com.alibaba.cloud.ai.graph.utils.TryFunction;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

import static java.lang.String.format;

// 内存检查点保存器类，实现了BaseCheckpointSaver接口
public class MemorySaver implements BaseCheckpointSaver {

	// 按线程ID存储检查点的映射表
	final Map<String, LinkedList<Checkpoint>> _checkpointsByThread = new HashMap<>();

	// 可重入锁，用于线程安全操作
	private final ReentrantLock _lock = new ReentrantLock();

	// 内存保存器的构造函数
	public MemorySaver() {
	}

	// 加载检查点的受保护方法
	protected LinkedList<Checkpoint> loadedCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints)
			throws Exception {
		// 返回检查点列表
		return checkpoints;
	}

	// 插入检查点的受保护方法
	protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint)
			throws Exception {
		// 空实现，子类可重写此方法
	}

	// 更新检查点的受保护方法
	protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint)
			throws Exception {
		// 空实现，子类可重写此方法
	}

	// 释放检查点的受保护方法
	protected void releasedCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Tag releaseTag)
			throws Exception {
		// 空实现，子类可重写此方法
	}

	// 加载或初始化检查点的受保护最终方法
	protected final <T> T loadOrInitCheckpoints(RunnableConfig config,
			TryFunction<LinkedList<Checkpoint>, T, Exception> transformer) throws Exception {
		// 获取锁
		_lock.lock();
		// 使用try-finally确保锁被释放
		try {
			// 获取线程ID，如果不存在则使用默认值
			var threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
			// 应用转换器，加载或初始化检查点
			return transformer.tryApply(
					loadedCheckpoints(config, _checkpointsByThread.computeIfAbsent(threadId, k -> new LinkedList<>())));

		}
		// 确保锁被释放
		finally {
			// 释放锁
			_lock.unlock();
		}
	}

	// 获取检查点按线程映射的方法
	public Map<String, LinkedList<Checkpoint>> get_checkpointsByThread() {
		// 返回检查点映射表
		return _checkpointsByThread;
	}

	// 重写清除方法
	@Override
	public boolean clear(RunnableConfig config) {
		// 获取锁
		_lock.lock();
		// 使用try-finally确保锁被释放
		try {
			// 获取线程ID，如果不存在则使用默认值
			var threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
			// 获取指定线程ID的检查点列表
			LinkedList<Checkpoint> checkpoints = _checkpointsByThread.get(threadId);
			// 检查列表是否不为null
			if (checkpoints != null) {
				// 清空检查点列表
				checkpoints.clear();
				// 返回清除成功
				return true;
			}
			// 如果列表为null，返回清除失败
			return false;
		}
		// 确保锁被释放
		finally {
			// 释放锁
			_lock.unlock();
		}
	}

	// 移除指定线程ID的检查点的受保护最终方法
	protected final Collection<Checkpoint> remove(String threadId) {
		// 从映射表中移除指定线程ID的检查点，并返回被移除的集合
		return _checkpointsByThread.remove(Objects.requireNonNull(threadId));
	}

	// 重写列出检查点的方法
	@Override
	public final Collection<Checkpoint> list(RunnableConfig config) {
		// 尝试加载或初始化检查点
		try {
			// 使用不可修改集合转换器获取检查点列表
			return loadOrInitCheckpoints(config, Collections::unmodifiableCollection);
		}
		// 捕获异常
		catch (Exception e) {
			// 抛出运行时异常
			throw new RuntimeException(e);
		}
	}

	// 重写获取检查点的方法
	@Override
	public final Optional<Checkpoint> get(RunnableConfig config) {

		// 尝试加载或初始化检查点
		try {
			// 使用lambda表达式处理检查点获取逻辑
			return loadOrInitCheckpoints(config, checkpoints -> {
				// 检查配置中是否包含检查点ID
				if (config.checkPointId().isPresent()) {
					// 如果存在检查点ID，查找匹配的检查点
					return config.checkPointId()
						.flatMap(id -> checkpoints.stream()
							.filter(checkpoint -> checkpoint.getId().equals(id))
							.findFirst());
				}
				// 如果没有指定检查点ID，获取最新的检查点
				return getLast(checkpoints, config);

			});
		}
		// 捕获异常
		catch (Exception e) {
			// 抛出运行时异常
			throw new RuntimeException(e);
		}
	}

	// 重写保存检查点的方法
	@Override
	public final RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {

		// 加载或初始化检查点
		return loadOrInitCheckpoints(config, checkpoints -> {

			// 检查配置中是否包含检查点ID
			if (config.checkPointId().isPresent()) { // 替换检查点
				// 获取检查点ID
				String checkPointId = config.checkPointId().get();
				// 查找匹配的检查点索引
				int index = IntStream.range(0, checkpoints.size())
					.filter(i -> checkpoints.get(i).getId().equals(checkPointId))
					.findFirst()
					.orElseThrow(() -> (new NoSuchElementException(
							format("Checkpoint with id %s not found!", checkPointId))));
				// 设置新的检查点
				checkpoints.set(index, checkpoint);
				// 调用更新检查点方法
				updatedCheckpoint(config, checkpoints, checkpoint);
				// 返回原配置
				return config;
			}

			// 将新检查点添加到列表开头
			checkpoints.push(checkpoint); // 添加检查点
			// 调用插入检查点方法
			insertedCheckpoint(config, checkpoints, checkpoint);

			// 返回包含新检查点ID的配置
			return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();

		});
	}

	// 重写释放检查点的方法
	@Override
	public final Tag release(RunnableConfig config) throws Exception {

		// 加载或初始化检查点
		return loadOrInitCheckpoints(config, checkpoints -> {

			// 获取线程ID，如果不存在则使用默认值
			var threadId = config.threadId().orElse(THREAD_ID_DEFAULT);

			// 创建标签对象，包含线程ID和移除的检查点
			var tag = new Tag(threadId, remove(threadId));

			// 调用释放检查点方法
			releasedCheckpoints(config, checkpoints, tag);

			// 返回标签
			return tag;
		});
	}

}
