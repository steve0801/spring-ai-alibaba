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

package com.alibaba.cloud.ai.agent.studio.service;

import com.alibaba.cloud.ai.agent.studio.dto.ListThreadsResponse;
import com.alibaba.cloud.ai.agent.studio.dto.Thread;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * In-memory implementation of ThreadService.
 * For production use, this should be backed by a database or distributed cache.
 */
@Service
public class ThreadServiceImpl implements ThreadService {

	private static final Logger log = LoggerFactory.getLogger(ThreadServiceImpl.class);

	// In-memory storage: key = "appName:userId:threadId", value = Thread
	private final Map<String, Thread> threads = new ConcurrentHashMap<>();

	// Storage for thread states: key = "appName:userId:threadId", value = state
	private final Map<String, Map<String, Object>> thradStates = new ConcurrentHashMap<>();

	@Override
	// 获取指定线程的Mono流，包含appName、userId、threadId和可选的状态参数
	public Mono<Optional<Thread>> getThread(
			String appName, String userId, String threadId, Optional<Map<String, Object>> state) {
		// 使用Mono.fromCallable包装同步操作，使其变为异步流
		return Mono.fromCallable(() -> {
			// 构建用于存储的键值
			String key = buildKey(appName, userId, threadId);
			// 从线程映射中获取线程对象
			Thread thread = threads.get(key);

			// 将线程对象包装为Optional并返回
			return Optional.ofNullable(thread);
		});
	}

	@Override
	// 列出指定应用和用户的线程列表
	public Mono<ListThreadsResponse> listThreads(String appName, String userId) {
		// 使用Mono.fromCallable包装同步操作
		return Mono.fromCallable(() -> {
			// 构建用于过滤的键前缀
			String prefix = buildKeyPrefix(appName, userId);

			// 通过流式处理过滤出匹配前缀的线程并收集为列表
			List<Thread> userThreads = threads.entrySet().stream()
					// 过滤出键以前缀开头的条目
					.filter(entry -> entry.getKey().startsWith(prefix))
					// 映射为值（Thread对象）
					.map(Map.Entry::getValue)
					// 收集为List
					.collect(Collectors.toList());

			// 记录调试日志，显示找到的线程数量
			log.debug("Found {} threads for app={}, user={}", userThreads.size(), appName, userId);
			// 创建并返回线程列表响应对象
			return ListThreadsResponse.of(userThreads);
		});
	}

	@Override
	// 创建新线程的方法
	public Mono<Thread> createThread(
			String appName, String userId, Map<String, Object> initialState, String threadId) {
		// 使用Mono.fromCallable包装同步操作
		return Mono.fromCallable(() -> {
			// 如果未提供threadId或为空，则生成新的UUID作为线程ID
			String finalThreadId = (threadId == null || threadId.trim().isEmpty())
					? generateThreadId()
					: threadId;

			// 构建用于存储的键值
			String key = buildKey(appName, userId, finalThreadId);

			// 检查线程是否已存在
			if (threads.containsKey(key)) {
				// 记录警告日志
				log.warn("Attempted to create duplicate thread: {}", finalThreadId);
				// 抛出异常表示线程已存在
				throw new IllegalStateException("Thread already exists: " + finalThreadId);
			}

			// 创建新的线程对象
			Thread newThread = Thread.builder(finalThreadId)
					// 设置应用名称
					.appName(appName)
					// 设置用户ID
					.userId(userId)
					// 构建线程对象
					.build();

			// 将新线程放入线程映射中
			threads.put(key, newThread);

			// 如果提供了初始状态且不为空，则存储初始状态
			if (initialState != null && !initialState.isEmpty()) {
				// 将初始状态放入线程状态映射中
				thradStates.put(key, new ConcurrentHashMap<>(initialState));
			}

			// 记录信息日志，表示线程创建成功
			log.info("Created thread: {} for app={}, user={}", finalThreadId, appName, userId);
			// 返回新创建的线程
			return newThread;
		});
	}

	@Override
	// 删除指定线程的方法
	public Mono<Void> deleteThread(String appName, String userId, String threadId) {
		// 使用Mono.fromRunnable包装无返回值的同步操作
		return Mono.fromRunnable(() -> {
			// 构建用于存储的键值
			String key = buildKey(appName, userId, threadId);
			// 从线程映射中移除线程并获取被移除的线程对象
			Thread removed = threads.remove(key);
			// 从线程状态映射中移除状态
			thradStates.remove(key);

			// 如果成功移除了线程
			if (removed != null) {
				// 记录信息日志，表示线程删除成功
				log.info("Deleted thread: {} for app={}, user={}", threadId, appName, userId);
			}
			// 如果线程不存在
			else {
				// 记录警告日志，表示尝试删除不存在的线程
				log.warn("Attempted to delete non-existent thread: {}", threadId);
			}
		});
	}

	/**
	 * Gets the state for a thread.
	 *
	 * @param appName The application name.
	 * @param userId The user ID.
	 * @param threadId The thread ID.
	 * @return The thread state, or empty map if not found.
	 */
	// 获取指定线程的状态
	public Map<String, Object> getThreadState(String appName, String userId, String threadId) {
		// 构建用于存储的键值
		String key = buildKey(appName, userId, threadId);
		// 从线程状态映射中获取状态，如果不存在则返回空的ConcurrentHashMap
		return thradStates.getOrDefault(key, new ConcurrentHashMap<>());
	}

	/**
	 * Updates the state for a thread.
	 *
	 * @param appName The application name.
	 * @param userId The user ID.
	 * @param threadId The thread ID.
	 * @param state The new state.
	 */
	// 更新指定线程的状态
	public void updateThreadState(
			String appName, String userId, String threadId, Map<String, Object> state) {
		// 构建用于存储的键值
		String key = buildKey(appName, userId, threadId);
		// 检查线程是否存在
		if (threads.containsKey(key)) {
			// 将新状态放入线程状态映射中
			thradStates.put(key, new ConcurrentHashMap<>(state));
			// 更新最后更新时间（此行代码未实现，仅为注释说明）
			// Update last update time
			// 从线程映射中获取线程对象
			Thread thread = threads.get(key);
			// 记录调试日志，表示状态更新成功
			log.debug("Updated state for thread: {}", threadId);
		}
	}

	/**
	 * Builds a storage key for a thread.
	 */
	// 构建线程的存储键值
	private String buildKey(String appName, String userId, String threadId) {
		// 使用格式化字符串构建键值，格式为"appName:userId:threadId"
		return String.format("%s:%s:%s", appName, userId, threadId);
	}

	/**
	 * Builds a key prefix for filtering threads by app and user.
	 */
	// 构建用于过滤线程的键前缀
	private String buildKeyPrefix(String appName, String userId) {
		// 使用格式化字符串构建前缀，格式为"appName:userId:"
		return String.format("%s:%s:", appName, userId);
	}

	/**
	 * Generates a unique thread ID.
	 */
	// 生成唯一的线程ID
	private String generateThreadId() {
		// 使用UUID.randomUUID()生成唯一的字符串标识符
		return UUID.randomUUID().toString();
	}
}
