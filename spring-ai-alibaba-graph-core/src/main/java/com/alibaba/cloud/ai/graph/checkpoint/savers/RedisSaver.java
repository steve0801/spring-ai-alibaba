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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static java.lang.String.format;

/**
 * The type Redis saver.
 *
 * @author disaster
 * @since 1.0.0-M2
 */
public class RedisSaver implements BaseCheckpointSaver {

	private RedissonClient redisson;

	// 用于JSON序列化和反序列化的ObjectMapper实例
	private final ObjectMapper objectMapper;

	// Redis键名前缀常量
	private static final String PREFIX = "graph:checkpoint:content:";

	// 分布式锁前缀常量
	private static final String LOCK_PREFIX = "graph:checkpoint:lock:";

	/**
	 * 使用RedissonClient实例构造RedisSaver对象
	 *
	 * @param redisson Redisson客户端
	 */
	public RedisSaver(RedissonClient redisson) {
		// 调用带ObjectMapper参数的构造方法
		this(redisson, new ObjectMapper());
	}

	/**
	 * 使用RedissonClient和ObjectMapper实例构造RedisSaver对象
	 *
	 * @param redisson     Redisson客户端
	 * @param objectMapper JSON序列化/反序列化对象映射器
	 */
	public RedisSaver(RedissonClient redisson, ObjectMapper objectMapper) {
		// 初始化Redisson客户端
		this.redisson = redisson;
		// 配置ObjectMapper实例
		this.objectMapper = configureObjectMapper(objectMapper);
	}

	// 配置ObjectMapper实例，注册JDK8模块
	private static ObjectMapper configureObjectMapper(ObjectMapper objectMapper) {
		// 确保ObjectMapper不为null
		ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
		// 注册JDK8模块以支持Optional等类型
		mapper.registerModule(new Jdk8Module());
		// 返回配置好的ObjectMapper
		return mapper;
	}

	// 实现BaseCheckpointSaver接口的list方法，列出指定配置的所有检查点
	@Override
	public Collection<Checkpoint> list(RunnableConfig config) {
		// 获取配置中的线程ID
		Optional<String> configOption = config.threadId();
		// 检查线程ID是否存在
		if (configOption.isPresent()) {
			// 获取分布式锁
			RLock lock = redisson.getLock(LOCK_PREFIX + configOption.get());
			// 定义锁获取状态变量
			boolean tryLock = false;
			// 尝试获取锁并执行操作
			try {
				// 尝试获取锁，等待2毫秒
				tryLock = lock.tryLock(2, TimeUnit.MILLISECONDS);
				// 如果成功获取锁
				if (tryLock) {
					// 获取存储检查点的桶
					RBucket<String> bucket = redisson.getBucket(PREFIX + configOption.get());
					// 获取桶中的内容
					String content = bucket.get();
					// 如果内容为空，返回空链表
					if (content == null) {
						return new LinkedList<>();
					}
					// 将JSON内容反序列化为检查点列表并返回
					return objectMapper.readValue(content, new TypeReference<>() {
					});
				}
				// 如果未能获取锁，返回空列表
				else {
					return List.of();
				}
			}
			// 捕获中断异常
			catch (InterruptedException e) {
				// 抛出运行时异常
				throw new RuntimeException(e);
			}
			// 捕获JSON映射异常
			catch (JsonMappingException e) {
				// 抛出JSON解析失败异常
				throw new RuntimeException("Failed to parse JSON", e);
			}
			// 捕获JSON处理异常
			catch (JsonProcessingException e) {
				// 抛出JSON解析失败异常
				throw new RuntimeException("Failed to parse JSON", e);
			}
			// 确保锁被释放
			finally {
				// 如果成功获取了锁，则解锁
				if (tryLock) {
					lock.unlock();
				}
			}
		}
		// 如果线程ID不存在，抛出非法参数异常
		else {
			// 抛出线程ID不能为空的异常
			throw new IllegalArgumentException("threadId is not allow null");
		}
	}

	// 实现BaseCheckpointSaver接口的get方法，获取指定配置的检查点
	@Override
	public Optional<Checkpoint> get(RunnableConfig config) {
		// 获取配置中的线程ID
		Optional<String> configOption = config.threadId();
		// 检查线程ID是否存在
		if (configOption.isPresent()) {
			// 获取分布式锁
			RLock lock = redisson.getLock(LOCK_PREFIX + configOption.get());
			// 定义锁获取状态变量
			boolean tryLock = false;
			// 尝试获取锁并执行操作
			try {
				// 尝试获取锁，等待2毫秒
				tryLock = lock.tryLock(2, TimeUnit.MILLISECONDS);
				// 如果成功获取锁
				if (tryLock) {
					// 获取存储检查点的桶
					RBucket<String> bucket = redisson.getBucket(PREFIX + configOption.get());
					// 获取桶中的内容
					String content = bucket.get();
					// 声明检查点列表变量
					List<Checkpoint> checkpoints;
					// 如果内容为空，创建新的空链表
					if (content == null) {
						checkpoints = new LinkedList<>();
					}
					// 否则将JSON内容反序列化为检查点列表
					else {
						checkpoints = objectMapper.readValue(content, new TypeReference<>() {
						});
					}
					// 如果配置中指定了检查点ID
					if (config.checkPointId().isPresent()) {
						// 根据配置中的检查点ID查找对应的检查点
						return config.checkPointId()
							.flatMap(id -> checkpoints.stream()
								// 过滤出ID匹配的检查点
								.filter(checkpoint -> checkpoint.getId().equals(id))
								// 获取第一个匹配项
								.findFirst());
					}
					// 获取最后一个检查点
					return getLast(getLinkedList(checkpoints), config);
				}
				// 如果未能获取锁，返回空Optional
				else {
					return Optional.empty();
				}
			}
			// 捕获中断异常
			catch (InterruptedException e) {
				// 抛出运行时异常
				throw new RuntimeException(e);
			}
			// 捕获JSON映射异常
			catch (JsonMappingException e) {
				// 抛出JSON解析失败异常
				throw new RuntimeException("Failed to parse JSON", e);
			}
			// 捕获JSON处理异常
			catch (JsonProcessingException e) {
				// 抛出JSON解析失败异常
				throw new RuntimeException("Failed to parse JSON", e);
			}
			// 确保锁被释放
			finally {
				// 如果成功获取了锁，则解锁
				if (tryLock) {
					lock.unlock();
				}
			}
		}
		// 如果线程ID不存在，抛出非法参数异常
		else {
			// 抛出线程ID不能为空的异常
			throw new IllegalArgumentException("threadId isn't allow null");
		}
	}

	// 实现BaseCheckpointSaver接口的put方法，保存检查点
	@Override
	public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
		// 获取配置中的线程ID
		Optional<String> configOption = config.threadId();
		// 检查线程ID是否存在
		if (configOption.isPresent()) {
			// 获取分布式锁
			RLock lock = redisson.getLock(LOCK_PREFIX + configOption.get());
			// 定义锁获取状态变量
			boolean tryLock = false;
			// 尝试获取锁并执行操作
			try {
				// 尝试获取锁，等待2毫秒
				tryLock = lock.tryLock(2, TimeUnit.MILLISECONDS);
				// 如果成功获取锁
				if (tryLock) {
					// 获取存储检查点的桶
					RBucket<String> bucket = redisson.getBucket(PREFIX + configOption.get());
					// 获取桶中的内容
					String content = bucket.get();
					// 声明检查点列表变量
					List<Checkpoint> checkpoints;
					// 如果内容为空，创建新的空链表
					if (content == null) {
						checkpoints = new LinkedList<>();
					}
					// 否则将JSON内容反序列化为检查点列表
					else {
						checkpoints = objectMapper.readValue(content, new TypeReference<>() {
						});
					}
					// 将检查点列表转换为链表
					LinkedList<Checkpoint> linkedList = getLinkedList(checkpoints);
					// 如果配置中指定了检查点ID，表示要替换现有检查点
					if (config.checkPointId().isPresent()) { // 替换检查点
						// 获取要替换的检查点ID
						String checkPointId = config.checkPointId().get();
						// 查找检查点在列表中的索引位置
						int index = IntStream.range(0, checkpoints.size())
							.filter(i -> checkpoints.get(i).getId().equals(checkPointId))
							.findFirst()
							// 如果没找到对应ID的检查点，抛出异常
							.orElseThrow(() -> (new NoSuchElementException(
									format("Checkpoint with id %s not found!", checkPointId))));
						// 在指定位置替换检查点
						linkedList.set(index, checkpoint);
						// 将更新后的链表序列化并存储到桶中
						bucket.set(objectMapper.writeValueAsString(linkedList));
						// 返回原始配置
						return config;
					}
					// 向链表头部添加新检查点
					linkedList.push(checkpoint); // 添加检查点
					// 将更新后的链表序列化并存储到桶中
					bucket.set(objectMapper.writeValueAsString(linkedList));
				}
				// 返回带有新检查点ID的配置
				return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();
			}
			// 捕获中断异常
			catch (InterruptedException e) {
				// 抛出运行时异常
				throw new RuntimeException(e);
			}
			// 确保锁被释放
			finally {
				// 如果成功获取了锁，则解锁
				if (tryLock) {
					lock.unlock();
				}
			}
		}
		// 如果线程ID不存在，抛出非法参数异常
		else {
			// 抛出线程ID不能为空的异常
			throw new IllegalArgumentException("threadId isn't allow null");
		}
	}

	// 实现BaseCheckpointSaver接口的clear方法，清除指定配置的检查点
	@Override
	public boolean clear(RunnableConfig config) {
		// 获取配置中的线程ID
		Optional<String> configOption = config.threadId();
		// 检查线程ID是否存在
		if (configOption.isPresent()) {
			// 获取分布式锁
			RLock lock = redisson.getLock(LOCK_PREFIX + configOption.get());
			// 定义锁获取状态变量
			boolean tryLock = false;
			// 尝试获取锁并执行操作
			try {
				// 尝试获取锁，等待2毫秒
				tryLock = lock.tryLock(2, TimeUnit.MILLISECONDS);
				// 如果成功获取锁
				if (tryLock) {
					// 获取存储检查点的桶
					RBucket<String> bucket = redisson.getBucket(PREFIX + configOption.get());
					// 将桶中的内容设置为空列表的JSON字符串
					bucket.getAndSet(objectMapper.writeValueAsString(List.of()));
					// 返回锁获取状态
					return tryLock;
				}
				// 如果未能获取锁，返回false
				return false;
			}
			// 捕获中断异常
			catch (InterruptedException e) {
				// 抛出运行时异常
				throw new RuntimeException(e);
			}
			// 捕获JSON处理异常
			catch (JsonProcessingException e) {
				// 抛出JSON序列化失败异常
				throw new RuntimeException("Failed to serialize JSON", e);
			}
			// 确保锁被释放
			finally {
				// 如果成功获取了锁，则解锁
				if (tryLock) {
					lock.unlock();
				}
			}
		}
		// 如果线程ID不存在，抛出非法参数异常
		else {
			// 抛出线程ID不能为空的异常
			throw new IllegalArgumentException("threadId isn't allow null");
		}
	}
}

