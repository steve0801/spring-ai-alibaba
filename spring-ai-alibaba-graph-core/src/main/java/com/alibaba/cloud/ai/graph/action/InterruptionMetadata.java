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
package com.alibaba.cloud.ai.graph.action;

import com.alibaba.cloud.ai.graph.HasMetadata;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.utils.CollectionsUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * Represents the metadata associated with a graph execution interruption. This class is
 * immutable and captures the state of the graph at the point of interruption, the node
 * where the interruption occurred, and any additional custom metadata.
 *
 */
public final class InterruptionMetadata extends NodeOutput implements HasMetadata<InterruptionMetadata.Builder> {

	private final Map<String, Object> metadata;

	private List<ToolFeedback> toolFeedbacks;

	private InterruptionMetadata(Builder builder) {
		super(builder.nodeId, builder.state);
		this.metadata = builder.metadata();
		this.toolFeedbacks = new ArrayList<>(builder.toolFeedbacks);
	}

	/**
	 * Retrieves a metadata value associated with the specified key.
	 * @param key the key whose associated value is to be returned
	 * @return an {@link Optional} containing the value to which the specified key is
	 * mapped, or an empty {@link Optional} if this metadata contains no mapping for the
	 * key.
	 */
	// 重写HasMetadata接口的metadata方法，根据键获取元数据值
	@Override
	public Optional<Object> metadata(String key) {
		// 使用Optional包装metadata并获取指定键的值
		return ofNullable(metadata).map(m -> m.get(key));
	}

	// 重写HasMetadata接口的metadata方法，获取完整的元数据映射
	@Override
	public Optional<Map<String, Object>> metadata() {
		// 返回不可修改的元数据映射的Optional包装
		return Optional.of(Collections.unmodifiableMap(metadata));
	}

	// 获取工具反馈列表的方法
	public List<ToolFeedback> toolFeedbacks() {
		// 检查工具反馈列表是否为空
		if (toolFeedbacks == null) {
			// 如果为空则返回新的空列表
			return new ArrayList<>();
		}
		// 返回工具反馈列表
		return toolFeedbacks;
	}

	// 重写toString方法，提供对象的字符串表示
	@Override
	public String toString() {
		// 使用格式化字符串构建InterruptionMetadata的字符串表示
		return String.format("""
				InterruptionMetadata{
				\tnodeId='%s',
				\tstate=%s,
				\tmetadata=%s
				}""", node(), state(), CollectionsUtils.toString(metadata));
	}

	// 创建InterruptionMetadata构建器的静态方法，需要节点ID和状态参数
	/**
	 * Creates a new builder for {@link InterruptionMetadata}.
	 * @return a new {@link Builder} instance
	 */
	public static Builder builder(String nodeId, OverAllState state) {
		// 返回使用指定节点ID和状态初始化的构建器
		return new Builder(nodeId, state);
	}

	// 创建空参数的InterruptionMetadata构建器的静态方法
	public static Builder builder() {
		// 返回空参数构建器
		return new Builder();
	}

	// 从现有的InterruptionMetadata创建构建器的静态方法
	public static Builder builder(InterruptionMetadata interruptionMetadata) {
		// 从现有对象创建构建器并设置节点ID和状态
		return new Builder(interruptionMetadata.metadata().orElse(Map.of()))
			.nodeId(interruptionMetadata.node())
			.state(interruptionMetadata.state());
	}

	// InterruptionMetadata的构建器类，用于创建InterruptionMetadata实例
	/**
	 * A builder for creating instances of {@link InterruptionMetadata}.
	 *
	 */
	public static class Builder extends HasMetadata.Builder<Builder> {
		// 工具反馈列表
		List<ToolFeedback> toolFeedbacks;

		// 节点ID
		String nodeId;

		// 整体状态
		OverAllState state;

		// 无参构造函数，初始化工具反馈列表
		public Builder() {
			// 初始化工具反馈列表为空列表
			this.toolFeedbacks = new ArrayList<>();
		}

		// 带参数构造函数，初始化节点ID和状态
		/**
		 * Constructs a new builder.
		 *
		 */
		public Builder(String nodeId, OverAllState state) {
			// 设置节点ID
			this.nodeId = nodeId;
			// 设置状态
			this.state = state;
			// 初始化工具反馈列表为空列表
			this.toolFeedbacks = new ArrayList<>();
		}

		// 使用元数据构造构建器的构造函数
		public Builder(Map<String, Object> metadata) {
			// 调用父类构造函数设置元数据
			super(metadata);
			// 初始化工具反馈列表为空列表
			this.toolFeedbacks = new ArrayList<>();
		}

		// 设置节点ID的构建器方法
		public Builder nodeId(String nodeId) {
			// 设置节点ID
			this.nodeId = nodeId;
			// 返回当前构建器实例以支持链式调用
			return this;
		}

		// 设置状态的构建器方法
		public Builder state(OverAllState state) {
			// 设置状态
			this.state = state;
			// 返回当前构建器实例以支持链式调用
			return this;
		}

		// 添加工具反馈的构建器方法
		public Builder addToolFeedback(ToolFeedback toolFeedback) {
			// 将工具反馈添加到列表
			this.toolFeedbacks.add(toolFeedback);
			// 返回当前构建器实例以支持链式调用
			return this;
		}

		// 设置工具反馈列表的构建器方法
		public Builder toolFeedbacks(List<ToolFeedback> toolFeedbacks) {
			// 用新的工具反馈列表替换当前列表
			this.toolFeedbacks = new ArrayList<>(toolFeedbacks);
			// 返回当前构建器实例以支持链式调用
			return this;
		}

		// 构建InterruptionMetadata实例的方法
		/**
		 * Builds the {@link InterruptionMetadata} instance.
		 * @return a new, immutable {@link InterruptionMetadata} instance
		 */
		public InterruptionMetadata build() {
			// 使用当前构建器创建InterruptionMetadata实例
			return new InterruptionMetadata(this);
		}

	}

	// 工具反馈类，用于记录工具执行的反馈信息
	public static class ToolFeedback {
		// 工具ID
		String id;
		// 工具名称
		String name;
		// 工具参数
		String arguments;
		// 反馈结果
		FeedbackResult result;
		// 描述信息
		String description;

		// ToolFeedback的构造函数
		public ToolFeedback(String id, String name, String arguments, FeedbackResult result, String description) {
			// 设置工具ID
			this.id = id;
			// 设置工具名称
			this.name = name;
			// 设置工具参数
			this.arguments = arguments;
			// 设置反馈结果
			this.result = result;
			// 设置描述信息
			this.description = description;
		}

		// 获取工具ID的方法
		public String getId() {
			// 返回工具ID
			return id;
		}

		// 获取工具名称的方法
		public String getName() {
			// 返回工具名称
			return name;
		}

		// 获取工具参数的方法
		public String getArguments() {
			// 返回工具参数
			return arguments;
		}

		// 获取反馈结果的方法
		public FeedbackResult getResult() {
			// 返回反馈结果
			return result;
		}

		// 获取描述信息的方法
		public String getDescription() {
			// 返回描述信息
			return description;
		}

		// 创建ToolFeedback构建器的静态方法
		public static Builder builder() {
			// 返回新的构建器实例
			return new Builder();
		}

		// 从现有ToolFeedback创建构建器的静态方法
		public static Builder builder(ToolFeedback toolFeedback) {
			// 从现有ToolFeedback创建构建器并设置各个属性
			return new Builder()
				.id(toolFeedback.getId())
				.name(toolFeedback.getName())
				.arguments(toolFeedback.getArguments())
				.result(toolFeedback.getResult())
				.description(toolFeedback.getDescription());
		}

		// ToolFeedback的构建器类
		public static class Builder {
			// 工具ID
			String id;
			// 工具名称
			String name;
			// 工具参数
			String arguments;
			// 反馈结果
			FeedbackResult result;
			// 描述信息
			String description;

			// 设置工具ID的构建器方法
			public Builder id(String id) {
				// 设置ID
				this.id = id;
				// 返回当前构建器实例以支持链式调用
				return this;
			}

			// 设置工具名称的构建器方法
			public Builder name(String name) {
				// 设置名称
				this.name = name;
				// 返回当前构建器实例以支持链式调用
				return this;
			}

			// 设置工具参数的构建器方法
			public Builder arguments(String arguments) {
				// 设置参数
				this.arguments = arguments;
				// 返回当前构建器实例以支持链式调用
				return this;
			}

			// 设置反馈结果的构建器方法
			public Builder result(FeedbackResult result) {
				// 设置结果
				this.result = result;
				// 返回当前构建器实例以支持链式调用
				return this;
			}

			// 根据类型字符串设置反馈结果的构建器方法
			public Builder type(String type) {
				// 将类型字符串转换为大写并解析为FeedbackResult枚举
				this.result = FeedbackResult.valueOf(type.toUpperCase());
				// 返回当前构建器实例以支持链式调用
				return this;
			}

			// 设置描述的构建器方法
			public Builder description(String description) {
				// 设置描述
				this.description = description;
				// 返回当前构建器实例以支持链式调用
				return this;
			}

			// 构建ToolFeedback实例的方法
			public ToolFeedback build() {
				// 使用当前构建器的属性创建ToolFeedback实例
				return new ToolFeedback(id, name, arguments, result, description);
			}
		}

		// 反馈结果枚举，定义了工具反馈的可能状态
		public enum FeedbackResult {
			// 已批准
			APPROVED,
			// 已拒绝
			REJECTED,
			// 已编辑
			EDITED;
		}
	}

}
