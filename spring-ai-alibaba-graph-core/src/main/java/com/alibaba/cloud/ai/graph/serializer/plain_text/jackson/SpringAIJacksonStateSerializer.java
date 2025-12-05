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
package com.alibaba.cloud.ai.graph.serializer.plain_text.jackson;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.AgentStateFactory;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;

import com.fasterxml.jackson.databind.module.SimpleModule;

import com.alibaba.cloud.ai.graph.serializer.AgentInstructionMessage;

import java.util.Collection;
import java.util.Map;

public class SpringAIJacksonStateSerializer extends JacksonStateSerializer {

	public SpringAIJacksonStateSerializer(AgentStateFactory<OverAllState> stateFactory) {
		super(stateFactory);

		var module = new SimpleModule();

		registerMessageHandlers(module);

		//ChatMessageSerializer.registerTo(module);
		//ChatMessageDeserializer.registerTo(module);
        //NodeOutputDeserializer.registerTo(module);

		// 注册ToolResponseMessage类型映射，使用MessageType.TOOL.name()作为标识符
		typeMapper.register(new TypeMapper.Reference<ToolResponseMessage>(MessageType.TOOL.name()) {
		// 注册SystemMessage类型映射，使用MessageType.SYSTEM.name()作为标识符
		}).register(new TypeMapper.Reference<SystemMessage>(MessageType.SYSTEM.name()) {
		// 注册UserMessage类型映射，使用MessageType.USER.name()作为标识符
		}).register(new TypeMapper.Reference<UserMessage>(MessageType.USER.name()) {
		// 注册AssistantMessage类型映射，使用MessageType.ASSISTANT.name()作为标识符
		}).register(new TypeMapper.Reference<AssistantMessage>(MessageType.ASSISTANT.name()) {
		// 注册Document类型映射，使用"DOCUMENT"作为标识符
		}).register(new TypeMapper.Reference<Document>("DOCUMENT") {
		// 注册AgentInstructionMessage类型映射，使用"TEMPLATED_USER"作为标识符
		}).register(new TypeMapper.Reference<AgentInstructionMessage>("TEMPLATED_USER") {
		});

		// Conditionally register DeepSeekAssistantMessage if the class is available
		// 注册DeepSeek支持（如果相关类在classpath中可用）
		registerDeepSeekSupportIfAvailable(module);

		// 将配置好的module注册到objectMapper中
		objectMapper.registerModule(module);

		// 创建默认类型解析器构建器，用于非final类型的类型解析
		ObjectMapper.DefaultTypeResolverBuilder typeResolver = new ObjectMapper.DefaultTypeResolverBuilder(
				// 设置默认类型解析策略为NON_FINAL（非final类型）
				ObjectMapper.DefaultTyping.NON_FINAL,
				// 使用宽松的子类型验证器
				LaissezFaireSubTypeValidator.instance) {
			// 序列化版本UID
			private static final long serialVersionUID = 1L;

			// 重写useForType方法以自定义类型解析规则
			@Override
			public boolean useForType(JavaType t) {
				// 如果类型是Map、Map-like、Collection-like、Collection或数组类型，则不使用类型信息
				if (t.isTypeOrSubTypeOf(Map.class) || t.isMapLikeType() || t.isCollectionLikeType()
						|| t.isTypeOrSubTypeOf(Collection.class) || t.isArrayType()) {
					return false;
				}
				// 其他情况使用父类的类型解析逻辑
				return super.useForType(t);
			}
		};
		// 初始化类型解析器，使用CLASS作为类型标识符
		typeResolver = (ObjectMapper.DefaultTypeResolverBuilder) typeResolver.init(JsonTypeInfo.Id.CLASS, null);
		// 设置类型信息包含方式为PROPERTY（作为属性包含）
		typeResolver = (ObjectMapper.DefaultTypeResolverBuilder) typeResolver.inclusion(JsonTypeInfo.As.PROPERTY);
		// 设置类型属性名为"@class"
		typeResolver = (ObjectMapper.DefaultTypeResolverBuilder) typeResolver.typeProperty("@class");
		// 将配置好的类型解析器设置为objectMapper的默认类型解析器
		objectMapper.setDefaultTyping(typeResolver);
	}

	/**
	 * Conditionally registers DeepSeekAssistantMessage support if the class is available on the classpath.
	 * This avoids forcing a dependency on DeepSeek-related JARs.
	 */
	// 私有方法，用于条件性地注册DeepSeekAssistantMessage支持（如果相关类在classpath中可用）
	private void registerDeepSeekSupportIfAvailable(SimpleModule module) {
		// 尝试加载DeepSeekAssistantMessage类
		try {
			Class.forName("org.springframework.ai.deepseek.DeepSeekAssistantMessage");
			// 类存在，注册类型映射器
			// TypeMapper只需要类型名称，不需要实际的类
			typeMapper.register(new TypeMapper.Reference<Object>("DEEPSEEK_ASSISTANT") {
			});
		}
		// 捕获类未找到异常
		catch (ClassNotFoundException e) {
			// DeepSeekAssistantMessage不可用，跳过注册
			// 对于不包含DeepSeek依赖的项目来说这是预期的行为
		}
	}

	// 定义聊天消息反序列化器接口
	interface ChatMessageDeserializer {

		// 系统消息反序列化器实例
		SystemMessageHandler.Deserializer system = new SystemMessageHandler.Deserializer();

		// 用户消息反序列化器实例
		UserMessageHandler.Deserializer user = new UserMessageHandler.Deserializer();

		// 助手消息反序列化器实例
		AssistantMessageHandler.Deserializer ai = new AssistantMessageHandler.Deserializer();

		// 工具响应消息反序列化器实例
		ToolResponseMessageHandler.Deserializer tool = new ToolResponseMessageHandler.Deserializer();

		// 文档反序列化器实例
		DocumentHandler.Deserializer document = new DocumentHandler.Deserializer();

		// 代理指令消息反序列化器实例
		AgentInstructionMessageHandler.Deserializer templatedUser = new AgentInstructionMessageHandler.Deserializer();

		// 流输出反序列化器实例
		StreamingOutputDeserializer streamingOutput = new StreamingOutputDeserializer();

		// 静态方法，用于将所有反序列化器注册到指定的SimpleModule中
		static void registerTo(SimpleModule module) {
			// 依次添加各种消息类型的反序列化器
			module.addDeserializer(ToolResponseMessage.class, tool)
				.addDeserializer(SystemMessage.class, system)
				.addDeserializer(UserMessage.class, user)
				.addDeserializer(AssistantMessage.class, ai)
				.addDeserializer(Document.class, document)
				.addDeserializer(AgentInstructionMessage.class, templatedUser)
				.addDeserializer(StreamingOutput.class, streamingOutput);

			// 条件性地注册DeepSeekAssistantMessage反序列化器（如果可用）
			registerDeepSeekDeserializerIfAvailable(module);
		}

		/**
		 * Conditionally registers DeepSeekAssistantMessage deserializer if the class is available.
		 */
		// 抑制未检查警告
		@SuppressWarnings("unchecked")
		// 静态方法，用于条件性地注册DeepSeekAssistantMessage反序列化器（如果类可用）
		static void registerDeepSeekDeserializerIfAvailable(SimpleModule module) {
			// 尝试加载DeepSeekAssistantMessage类
			try {
				Class<?> deepSeekClass = Class.forName("org.springframework.ai.deepseek.DeepSeekAssistantMessage");
				// 创建DeepSeek助手消息反序列化器实例
				DeepSeekAssistantMessageHandler.Deserializer deepSeekAi = new DeepSeekAssistantMessageHandler.Deserializer();
				// 使用原始类型避免类型推断问题
				module.addDeserializer((Class<Object>) deepSeekClass, (com.fasterxml.jackson.databind.JsonDeserializer<? extends Object>) deepSeekAi);
			}
			// 捕获类未找到异常或非法状态异常
			catch (ClassNotFoundException | IllegalStateException e) {
				// DeepSeekAssistantMessage不可用，跳过注册
				// 如果找到了类但构造函数失败，可能会抛出IllegalStateException
			}
		}

	}

	/**
	 * Registers all Spring AI Message handlers (serializers and deserializers) to
	 * the provided Jackson module.
	 * This allows other components (like CheckpointSavers) to reuse the same
	 * serialization logic.
	 *
	 * @param module the Jackson SimpleModule to register handlers to
	 */
	// 公共静态方法，用于注册所有Spring AI消息处理器（序列化器和反序列化器）到指定的Jackson模块
	public static void registerMessageHandlers(SimpleModule module) {
		// 注册聊天消息序列化器
		ChatMessageSerializer.registerTo(module);
		// 注册聊天消息反序列化器
		ChatMessageDeserializer.registerTo(module);
		// 注册节点输出反序列化器
		NodeOutputDeserializer.registerTo(module);
	}

	// 定义聊天消息序列化器接口
	interface ChatMessageSerializer {

		// 系统消息序列化器实例
		SystemMessageHandler.Serializer system = new SystemMessageHandler.Serializer();

		// 用户消息序列化器实例
		UserMessageHandler.Serializer user = new UserMessageHandler.Serializer();

		// 助手消息序列化器实例
		AssistantMessageHandler.Serializer ai = new AssistantMessageHandler.Serializer();

		// 工具响应消息序列化器实例
		ToolResponseMessageHandler.Serializer tool = new ToolResponseMessageHandler.Serializer();

		// 文档序列化器实例
		DocumentHandler.Serializer document = new DocumentHandler.Serializer();

		// 代理指令消息序列化器实例
		AgentInstructionMessageHandler.Serializer templatedUser = new AgentInstructionMessageHandler.Serializer();

		// Jackson节点输出序列化器实例
		JacksonNodeOutputSerializer output = new JacksonNodeOutputSerializer();

		// 流输出序列化器实例
		StreamingOutputSerializer streamingOutput = new StreamingOutputSerializer();

		// 静态方法，用于将所有序列化器注册到指定的SimpleModule中
		static void registerTo(SimpleModule module) {
			// 依次添加各种消息类型的序列化器
			module.addSerializer(ToolResponseMessage.class, tool)
				.addSerializer(SystemMessage.class, system)
				.addSerializer(UserMessage.class, user)
				.addSerializer(AssistantMessage.class, ai)
				.addSerializer(Document.class, document)
				.addSerializer(AgentInstructionMessage.class, templatedUser)
				.addSerializer(NodeOutput.class, output)
				.addSerializer(StreamingOutput.class, streamingOutput);

			// 条件性地注册DeepSeekAssistantMessage序列化器（如果可用）
			registerDeepSeekSerializerIfAvailable(module);
		}

		/**
		 * Conditionally registers DeepSeekAssistantMessage serializer if the class is available.
		 */
		// 抑制未检查警告
		@SuppressWarnings("unchecked")
		// 静态方法，用于条件性地注册DeepSeekAssistantMessage序列化器（如果类可用）
		static void registerDeepSeekSerializerIfAvailable(SimpleModule module) {
			// 尝试加载DeepSeekAssistantMessage类
			try {
				Class<?> deepSeekClass = Class.forName("org.springframework.ai.deepseek.DeepSeekAssistantMessage");
				// 创建DeepSeek助手消息序列化器实例
				DeepSeekAssistantMessageHandler.Serializer deepSeekAi = new DeepSeekAssistantMessageHandler.Serializer();
				// 使用原始类型避免类型推断问题
				module.addSerializer((Class<Object>) deepSeekClass, (com.fasterxml.jackson.databind.JsonSerializer<Object>) deepSeekAi);
			}
			// 捕获类未找到异常或非法状态异常
			catch (ClassNotFoundException | IllegalStateException e) {
				// DeepSeekAssistantMessage不可用，跳过注册
				// 如果找到了类但构造函数失败，可能会抛出IllegalStateException
			}
		}

	}

	// 定义节点输出反序列化器接口
    interface NodeOutputDeserializer {

		// Jackson节点输出反序列化器实例
        JacksonNodeOutputDeserializer nodeOutput = new JacksonNodeOutputDeserializer();

		// 静态方法，用于将节点输出反序列化器注册到指定的SimpleModule中
        static void registerTo(SimpleModule module) {
			// 添加节点输出反序列化器
            module.addDeserializer(NodeOutput.class, nodeOutput);
        }
	}

}
