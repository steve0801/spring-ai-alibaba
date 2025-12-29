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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.BasicDBObject;
import com.mongodb.ClientSessionOptions;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.InsertOneResult;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.lang.String.format;

public class MongoSaver implements BaseCheckpointSaver {
	// MongoSaver类的静态常量定义
	private static final Logger logger = LoggerFactory.getLogger(MongoSaver.class);

	// MongoDB客户端实例
	private MongoClient client;

	// MongoDB数据库实例
	private MongoDatabase database;

	// 事务选项配置
	private TransactionOptions txnOptions;

	// 用于JSON序列化和反序列化的ObjectMapper实例
	private final ObjectMapper objectMapper;

	// 数据库名称常量
	private static final String DB_NAME = "check_point_db";

	// 集合名称常量
	private static final String COLLECTION_NAME = "checkpoint_collection";

	// 文档ID前缀常量
	private static final String DOCUMENT_PREFIX = "mongo:checkpoint:document:";

	// 文档内容键名常量
	private static final String DOCUMENT_CONTENT_KEY = "checkpoint_content";

	/**
	 * 使用MongoClient实例构造MongoSaver对象
	 *
	 * @param client MongoDB客户端
	 */
	public MongoSaver(MongoClient client) {
		// 初始化MongoClient
		this.client = client;
		// 获取数据库实例
		this.database = client.getDatabase(DB_NAME);
		// 设置事务选项，写关注设置为多数确认
		this.txnOptions = TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build();
		// 创建默认ObjectMapper实例
		this.objectMapper = new ObjectMapper();
		// 添加JVM关闭钩子，自动关闭MongoClient连接
		Runtime.getRuntime().addShutdownHook(new Thread(client::close));
	}

	/**
	 * 使用MongoClient和ObjectMapper实例构造MongoSaver对象
	 *
	 * @param client       MongoDB客户端
	 * @param objectMapper JSON序列化/反序列化对象映射器
	 */
	public MongoSaver(MongoClient client, ObjectMapper objectMapper) {
		// 初始化MongoClient
		this.client = client;
		// 获取数据库实例
		this.database = client.getDatabase(DB_NAME);
		// 设置事务选项，写关注设置为多数确认
		this.txnOptions = TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build();
		// 使用传入的ObjectMapper实例
		this.objectMapper = objectMapper;
		// 添加JVM关闭钩子，自动关闭MongoClient连接
		Runtime.getRuntime().addShutdownHook(new Thread(client::close));
	}

	// 实现BaseCheckpointSaver接口的list方法，列出指定配置的所有检查点
	@Override
	public Collection<Checkpoint> list(RunnableConfig config) {
		// 获取配置中的线程ID
		Optional<String> configOption = config.threadId();
		// 检查线程ID是否存在
		if (configOption.isPresent()) {
			// 创建客户端会话并设置事务选项
			ClientSession clientSession = this.client
					.startSession(ClientSessionOptions.builder().defaultTransactionOptions(txnOptions).build());
			// 开始事务
			clientSession.startTransaction();
			// 声明检查点列表变量
			List<Checkpoint> checkpoints = null;
			// 尝试执行数据库操作
			try {
				// 获取集合实例
				MongoCollection<Document> collection = database.getCollection(COLLECTION_NAME);
				// 构建查询条件，使用文档前缀和线程ID
				BasicDBObject dbObject = new BasicDBObject("_id", DOCUMENT_PREFIX + configOption.get());
				// 查找文档
				Document document = collection.find(dbObject).first();
				// 如果文档不存在，返回空列表
				if (document == null)
					return Collections.emptyList();
				// 从文档中获取检查点字符串内容
				String checkpointsStr = document.getString(DOCUMENT_CONTENT_KEY);
				// 将JSON字符串反序列化为检查点列表
				checkpoints = objectMapper.readValue(checkpointsStr, new TypeReference<>() {
				});
				// 提交事务
				clientSession.commitTransaction();
			}
			// 捕获异常并回滚事务
			catch (Exception e) {
				// 回滚事务
				clientSession.abortTransaction();
				// 抛出运行时异常
				throw new RuntimeException(e);
			}
			// 确保会话关闭
			finally {
				// 关闭客户端会话
				clientSession.close();
			}
			// 返回检查点列表
			return checkpoints;
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
			// 创建客户端会话并设置事务选项
			ClientSession clientSession = this.client
					.startSession(ClientSessionOptions.builder().defaultTransactionOptions(txnOptions).build());
			// 声明检查点列表变量
			List<Checkpoint> checkpoints = null;
			// 尝试执行数据库操作
			try {
				// 开始事务
				clientSession.startTransaction();
				// 获取集合实例
				MongoCollection<Document> collection = database.getCollection(COLLECTION_NAME);
				// 构建查询条件，使用文档前缀和线程ID
				BasicDBObject dbObject = new BasicDBObject("_id", DOCUMENT_PREFIX + configOption.get());
				// 查找文档
				Document document = collection.find(dbObject).first();
				// 如果文档不存在，返回空Optional
				if (document == null)
					return Optional.empty();
				// 从文档中获取检查点字符串内容
				String checkpointsStr = document.getString(DOCUMENT_CONTENT_KEY);
				// 将JSON字符串反序列化为检查点列表
				checkpoints = objectMapper.readValue(checkpointsStr, new TypeReference<>() {
				});
				// 提交事务
				clientSession.commitTransaction();
				// 如果配置中指定了检查点ID
				if (config.checkPointId().isPresent()) {
					// 将检查点列表赋值给final变量用于lambda表达式
					List<Checkpoint> finalCheckpoints = checkpoints;
					// 根据配置中的检查点ID查找对应的检查点
					return config.checkPointId()
							.flatMap(id -> finalCheckpoints.stream()
									// 过滤出ID匹配的检查点
									.filter(checkpoint -> checkpoint.getId().equals(id))
									// 获取第一个匹配项
									.findFirst());
				}
				// 获取最后一个检查点
				return getLast(getLinkedList(checkpoints), config);
			}
			// 捕获异常并回滚事务
			catch (Exception e) {
				// 回滚事务
				clientSession.abortTransaction();
				// 抛出运行时异常
				throw new RuntimeException(e);
			}
			// 确保会话关闭
			finally {
				// 关闭客户端会话
				clientSession.close();
			}
		}
		// 如果线程ID不存在，抛出非法参数异常
		else {
			// 抛出线程ID不能为空的异常
			throw new IllegalArgumentException("threadId is not allow null");
		}
	}

	// 实现BaseCheckpointSaver接口的put方法，保存检查点
	@Override
	public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
		// 获取配置中的线程ID
		Optional<String> configOption = config.threadId();
		// 检查线程ID是否存在
		if (configOption.isPresent()) {
			// 创建客户端会话并设置事务选项
			ClientSession clientSession = this.client
					.startSession(ClientSessionOptions.builder().defaultTransactionOptions(txnOptions).build());
			// 开始事务
			clientSession.startTransaction();
			// 尝试执行数据库操作
			try {
				// 获取集合实例
				MongoCollection<Document> collection = database.getCollection(COLLECTION_NAME);
				// 构建查询条件，使用文档前缀和线程ID
				BasicDBObject dbObject = new BasicDBObject("_id", DOCUMENT_PREFIX + configOption.get());
				// 查找文档
				Document document = collection.find(dbObject).first();
				// 声明检查点链表变量
				LinkedList<Checkpoint> checkpointLinkedList = null;
				// 检查文档是否存在
				if (Objects.nonNull(document)) {
					// 从文档中获取检查点字符串内容
					String checkpointsStr = document.getString(DOCUMENT_CONTENT_KEY);
					// 将JSON字符串反序列化为检查点列表
					List<Checkpoint> checkpoints = objectMapper.readValue(checkpointsStr, new TypeReference<>() {
					});
					// 转换为链表
					checkpointLinkedList = getLinkedList(checkpoints);
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
						checkpointLinkedList.set(index, checkpoint);
						// 创建临时文档
						Document tempDocument = new Document().append("_id", DOCUMENT_PREFIX + configOption.get())
								.append(DOCUMENT_CONTENT_KEY, objectMapper.writeValueAsString(checkpointLinkedList));
						// 替换数据库中的文档
						collection.replaceOne(Filters.eq("_id", DOCUMENT_PREFIX + configOption.get()), tempDocument);
						// 提交事务
						clientSession.commitTransaction();
						// 关闭会话
						clientSession.close();
						// 返回原始配置
						return config;
					}
				}
				// 如果链表为空（文档不存在），创建新的链表并添加检查点
				if (checkpointLinkedList == null) {
					// 创建新的链表
					checkpointLinkedList = new LinkedList<>();
					checkpointLinkedList.push(checkpoint); // 添加检查点
					// 创建临时文档
					Document tempDocument = new Document().append("_id", DOCUMENT_PREFIX + configOption.get())
							.append(DOCUMENT_CONTENT_KEY, objectMapper.writeValueAsString(checkpointLinkedList));
					// 插入新文档
					InsertOneResult insertOneResult = collection.insertOne(tempDocument);
					// 检查插入是否被确认
					insertOneResult.wasAcknowledged();
				}
				// 如果链表存在，向链表中添加新检查点
				else {
					checkpointLinkedList.push(checkpoint); // 添加检查点
					// 创建临时文档
					Document tempDocument = new Document().append("_id", DOCUMENT_PREFIX + configOption.get())
							.append(DOCUMENT_CONTENT_KEY, objectMapper.writeValueAsString(checkpointLinkedList));
					// 设置替换选项为upsert（如果不存在则插入）
					ReplaceOptions opts = new ReplaceOptions().upsert(true);
					// 替换或插入文档
					collection.replaceOne(Filters.eq("_id", DOCUMENT_PREFIX + configOption.get()), tempDocument, opts);
				}
				// 提交事务
				clientSession.commitTransaction();
			}
			// 捕获异常并回滚事务
			catch (Exception e) {
				// 回滚事务
				clientSession.abortTransaction();
				// 抛出运行时异常
				throw new RuntimeException(e);
			}
			// 确保会话关闭
			finally {
				// 关闭客户端会话
				clientSession.close();
			}
			// 返回带有新检查点ID的配置
			return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();
		}
		// 如果线程ID不存在，抛出非法参数异常
		else {
			// 抛出线程ID不能为空的异常
			throw new IllegalArgumentException("threadId is not allow null");
		}
	}

	// 实现BaseCheckpointSaver接口的clear方法，清除指定配置的检查点
	@Override
	public boolean clear(RunnableConfig config) {
		// 获取配置中的线程ID
		Optional<String> configOption = config.threadId();
		// 检查线程ID是否存在
		if (configOption.isPresent()) {
			// 创建客户端会话并设置事务选项
			ClientSession clientSession = this.client
					.startSession(ClientSessionOptions.builder().defaultTransactionOptions(txnOptions).build());
			// 开始事务
			clientSession.startTransaction();
			// 尝试执行数据库操作
			try {
				// 获取集合实例
				MongoCollection<Document> collection = database.getCollection(COLLECTION_NAME);
				// 构建查询条件，使用文档前缀和线程ID
				BasicDBObject dbObject = new BasicDBObject("_id", DOCUMENT_PREFIX + configOption.get());
				// 查找并删除文档
				collection.findOneAndDelete(dbObject);
				// 提交事务
				clientSession.commitTransaction();
				// 返回成功标识
				return true;
			}
			// 捕获异常并回滚事务
			catch (Exception e) {
				// 回滚事务
				clientSession.abortTransaction();
				// 抛出运行时异常
				throw new RuntimeException(e);
			}
			// 确保会话关闭
			finally {
				// 关闭客户端会话
				clientSession.close();
			}
		}
		// 如果线程ID不存在，抛出非法参数异常
		else {
			// 抛出线程ID不能为空的异常
			throw new IllegalArgumentException("threadId is not allow null");
		}
	}
}
