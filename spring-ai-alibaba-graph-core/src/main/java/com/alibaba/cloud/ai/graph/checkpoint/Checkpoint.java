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

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.UUID;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

	// 检查点类，用于保存图执行的状态信息
public class Checkpoint {

	// 检查点的唯一标识符
	private final String id;

	// 检查点的状态数据，存储为键值对映射
	private Map<String, Object> state = null;

	// 当前节点ID
	private String nodeId = null;

	// 下一个节点ID
	private String nextNodeId = null;

	// 获取检查点ID
	public String getId() {
		return id;
	}

	// 获取检查点状态
	public Map<String, Object> getState() {
		return state;
	}

	// 获取当前节点ID
	public String getNodeId() {
		return nodeId;
	}

	// 获取下一个节点ID
	public String getNextNodeId() {
		return nextNodeId;
	}

	/**
	 * 创建给定检查点的副本，带有新的ID
	 * @param checkpoint 要复制的检查点值
	 * @return 带有不同ID的新副本
	 */
	// 创建检查点副本的静态方法
	public static Checkpoint copyOf(Checkpoint checkpoint) {
		// 确保传入的检查点不为null
		requireNonNull(checkpoint, "checkpoint cannot be null");
		// 创建新检查点，使用新的UUID作为ID，保持其他属性不变
		return new Checkpoint(UUID.randomUUID().toString(), checkpoint.state, checkpoint.nodeId, checkpoint.nextNodeId);
	}

	// Jackson反序列化构造函数
	@JsonCreator
	private Checkpoint(@JsonProperty("id") String id, @JsonProperty("state") Map<String, Object> state,
			@JsonProperty("nodeId") String nodeId, @JsonProperty("nextNodeId") String nextNodeId) {

		// 设置检查点ID，确保不为null
		this.id = requireNonNull(id, "id cannot be null");
		// 设置状态，确保不为null
		this.state = requireNonNull(state, "state cannot be null");
		// 设置节点ID，确保不为null
		this.nodeId = requireNonNull(nodeId, "nodeId cannot be null");
		// 设置下一个节点ID，确保不为null
		this.nextNodeId = requireNonNull(nextNodeId, "Checkpoint.nextNodeId cannot be null");

	}

	// 获取检查点构建器实例
	public static Builder builder() {
		return new Builder();
	}

	// 检查点构建器类
	public static class Builder {

		// 默认使用随机UUID作为ID
		private String id = UUID.randomUUID().toString();

		// 状态数据
		private Map<String, Object> state = null;

		// 当前节点ID
		private String nodeId = null;

		// 下一个节点ID
		private String nextNodeId = null;

		// 设置检查点ID
		public Builder id(String id) {
			this.id = id;
			return this;
		}

		// 设置状态（从OverAllState实例）
		public Builder state(OverAllState state) {
			this.state = state.data();
			return this;
		}

		// 设置状态（直接传入Map）
		public Builder state(Map<String, Object> state) {
			this.state = state;
			return this;
		}

		// 设置当前节点ID
		public Builder nodeId(String nodeId) {
			this.nodeId = nodeId;
			return this;
		}

		// 设置下一个节点ID
		public Builder nextNodeId(String nextNodeId) {
			this.nextNodeId = nextNodeId;
			return this;
		}

		// 构建检查点实例
		public Checkpoint build() {
			return new Checkpoint(id, state, nodeId, nextNodeId);
		}

	}

	// 更新检查点状态
	public Checkpoint updateState(Map<String, Object> values, Map<String, KeyStrategy> channels) {

		// 创建新检查点，使用更新后的状态
		return new Checkpoint(this.id, OverAllState.updateState(this.state, values, channels), this.nodeId,
				this.nextNodeId);
	}

	// 重写toString方法，返回检查点的字符串表示
	@Override
	public String toString() {
		// 格式化输出检查点信息
		return format("Checkpoint{ id=%s, nodeId=%s, nextNodeId=%s, state=%s }", id, nodeId, nextNodeId, state);
	}

}
