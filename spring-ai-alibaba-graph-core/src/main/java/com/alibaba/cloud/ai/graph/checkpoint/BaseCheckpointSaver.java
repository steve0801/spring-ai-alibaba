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
package com.alibaba.cloud.ai.graph.checkpoint;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Optional.ofNullable;

public interface BaseCheckpointSaver {

	public String THREAD_ID_DEFAULT = "$default";

	/**
	 * Configures ObjectMapper with Spring AI Message type handlers for checkpoint
	 * serialization.
	 * This is a public static method to allow other checkpoint savers to reuse the
	 * same configuration.
	 *
	 * @param objectMapper the ObjectMapper to configure
	 * @return the configured ObjectMapper
	 */
	static ObjectMapper configureObjectMapper(ObjectMapper objectMapper) {
		// 确保传入的objectMapper不为null，如果为null则抛出异常
		ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
		// 注册JDK8模块以支持Optional等JDK8新特性的序列化
		mapper.registerModule(new Jdk8Module());

		// Register Spring AI Message type handlers for proper
		// serialization/deserialization
		// This is crucial to prevent Message objects from being deserialized as HashMap
		// 创建SimpleModule用于注册自定义序列化处理器
		SimpleModule module = new SimpleModule();

		// Use the centralized registration logic from SpringAIJacksonStateSerializer
		// 使用SpringAIJacksonStateSerializer中的集中注册逻辑来注册消息处理器
		SpringAIJacksonStateSerializer.registerMessageHandlers(module);

		// 将配置好的module注册到mapper中
		mapper.registerModule(module);

		// Configure default typing for non-final types (similar to
		// SpringAIJacksonStateSerializer)
		// 配置非final类型的默认类型解析器，类似于SpringAIJacksonStateSerializer中的配置
		ObjectMapper.DefaultTypeResolverBuilder typeResolver = new ObjectMapper.DefaultTypeResolverBuilder(
				ObjectMapper.DefaultTyping.NON_FINAL, LaissezFaireSubTypeValidator.instance) {
			private static final long serialVersionUID = 1L;

			// 重写useForType方法来自定义哪些类型需要类型信息
			@Override
			public boolean useForType(JavaType t) {
				// 对于Map、Map-like、Collection-like、Collection和数组类型不使用类型信息
				if (t.isTypeOrSubTypeOf(java.util.Map.class) || t.isMapLikeType() || t.isCollectionLikeType()
						|| t.isTypeOrSubTypeOf(java.util.Collection.class) || t.isArrayType()) {
					return false;
				}
				// 其他类型使用父类的判断逻辑
				return super.useForType(t);
			}
		};
		// 初始化类型解析器，使用CLASS作为类型ID
		typeResolver = (ObjectMapper.DefaultTypeResolverBuilder) typeResolver.init(JsonTypeInfo.Id.CLASS, null);
		// 设置类型信息包含方式为PROPERTY
		typeResolver = (ObjectMapper.DefaultTypeResolverBuilder) typeResolver.inclusion(JsonTypeInfo.As.PROPERTY);
		// 设置类型属性名为"@class"
		typeResolver = (ObjectMapper.DefaultTypeResolverBuilder) typeResolver.typeProperty("@class");
		// 将配置好的类型解析器设置为mapper的默认类型解析器
		mapper.setDefaultTyping(typeResolver);

		// 返回配置完成的ObjectMapper实例
		return mapper;
	}

	// 定义Tag记录类，用于表示线程ID和检查点集合的组合
	record Tag(String threadId, Collection<Checkpoint> checkpoints) {
		// Tag记录的构造函数，对传入的参数进行处理
		public Tag(String threadId, Collection<Checkpoint> checkpoints) {
			// 设置线程ID
			this.threadId = threadId;
			// 如果检查点集合不为null则创建不可变副本，否则创建空列表
			this.checkpoints = ofNullable(checkpoints).map(List::copyOf).orElseGet(List::of);
		}
	}

	// 默认的release方法，释放配置相关的资源，返回null表示不执行任何操作
	default Tag release(RunnableConfig config) throws Exception {
		// 返回null表示默认不执行任何操作
		return null;
	}

	// 列出指定配置的所有检查点
	Collection<Checkpoint> list(RunnableConfig config);

	// 获取指定配置的检查点
	Optional<Checkpoint> get(RunnableConfig config);

	// 保存检查点到指定配置
	RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception;

	// 清除指定配置的检查点
	boolean clear(RunnableConfig config);

	// 获取链表中最后一个检查点的默认方法
	default Optional<Checkpoint> getLast(LinkedList<Checkpoint> checkpoints, RunnableConfig config) {
		// 如果检查点链表为空或null，返回空Optional，否则返回链表头部的检查点
		return (checkpoints == null || checkpoints.isEmpty()) ? Optional.empty() : ofNullable(checkpoints.peek());
	}

	// 将检查点列表转换为链表的默认方法
	default LinkedList<Checkpoint> getLinkedList(List<Checkpoint> checkpoints) {
		// 如果检查点列表不为null则创建链表副本，否则创建空链表
		return Objects.nonNull(checkpoints) ? new LinkedList<>(checkpoints) : new LinkedList<>();
	}

}
