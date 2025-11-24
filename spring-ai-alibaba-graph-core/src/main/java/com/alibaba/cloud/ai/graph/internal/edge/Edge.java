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
package com.alibaba.cloud.ai.graph.internal.edge;

import com.alibaba.cloud.ai.graph.exception.Errors;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.internal.node.Node;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static java.lang.String.format;

/**
 * Represents an edge in a graph with a source ID and a target value.
 *
 * @param sourceId The ID of the source node.
 * @param targets The targets value associated with the edge.
 */
	/**
	 * Represents an edge in a graph with a source ID and a target value.
	 *
	 * @param sourceId The ID of the source node.
	 * @param targets The targets value associated with the edge.
	 */
// 定义Edge记录类，包含源节点ID和目标值列表
public record Edge(String sourceId, List<EdgeValue> targets) {

	// 构造函数，用于创建具有单个目标值的边
	public Edge(String sourceId, EdgeValue target) {
		this(sourceId, List.of(target));
	}

	// 构造函数，用于创建没有目标的边
	public Edge(String id) {
		this(id, List.of());
	}

	// 判断是否为并行边（具有多个目标）
	public boolean isParallel() {
		return targets.size() > 1;
	}

	// 获取单个目标值，如果是并行边则抛出异常
	public EdgeValue target() {
		if (isParallel()) {
			throw new IllegalStateException(format("Edge '%s' is parallel", sourceId));
		}
		return targets.get(0);
	}

	// 检查是否存在与给定目标ID匹配的目标
	public boolean anyMatchByTargetId(String targetId) {
		return targets().stream()
			.anyMatch(v -> (v.id() != null) ? Objects.equals(v.id(), targetId)
					: v.value().mappings().containsValue(targetId)

			);
	}

	// 更新源ID和目标ID，返回新的Edge实例
	public Edge withSourceAndTargetIdsUpdated(Node node, Function<String, String> newSourceId,
			Function<String, EdgeValue> newTarget) {

		var newTargets = targets().stream().map(t -> t.withTargetIdsUpdated(newTarget)).toList();
		return new Edge(newSourceId.apply(sourceId), newTargets);

	}

	// 验证边的有效性
	public void validate(StateGraph.Nodes nodes) throws GraphStateException {
		// 检查源节点是否存在（START节点除外）
		if (!Objects.equals(sourceId(), START) && !nodes.anyMatchById(sourceId())) {
			throw Errors.missingNodeReferencedByEdge.exception(sourceId());
		}

		// 如果是并行边，检查是否存在重复的目标
		// 如果是并行边，检查是否存在重复的目标
		if (isParallel()) { // check for duplicates targets
			// 通过EdgeValue的id对targets进行分组并统计每个id的出现次数
			Set<String> duplicates = targets.stream()
				.collect(Collectors.groupingBy(EdgeValue::id, Collectors.counting())) // Group
																						// by
																						// element
																						// and
																						// count
																						// occurrences
				// 将分组统计结果转换为entry流
				.entrySet()
				.stream()
				// 过滤出出现次数大于1的元素（即重复的id）
				.filter(entry -> entry.getValue() > 1) // Filter elements with more than
														// one occurrence
				// 提取重复的id键
				.map(Map.Entry::getKey)
				// 收集到Set中
				.collect(Collectors.toSet());
			// 如果存在重复的id，则抛出异常
			if (!duplicates.isEmpty()) {
				throw Errors.duplicateEdgeTargetError.exception(sourceId(), duplicates);
			}
		}

		// 验证每个目标的有效性
		for (EdgeValue target : targets) {
			validate(target, nodes);
		}

	}

	// 验证单个目标的有效性
	private void validate(EdgeValue target, StateGraph.Nodes nodes) throws GraphStateException {
		// 如果目标有ID，检查该节点是否存在（END节点除外）
		if (target.id() != null) {
			if (!Objects.equals(target.id(), StateGraph.END) && !nodes.anyMatchById(target.id())) {
				throw Errors.missingNodeReferencedByEdge.exception(target.id());
			}
		}
		// 如果目标有值，检查映射中的每个节点是否存在（END节点除外）
		else if (target.value() != null) {
			for (String nodeId : target.value().mappings().values()) {
				if (!Objects.equals(nodeId, StateGraph.END) && !nodes.anyMatchById(nodeId)) {
					throw Errors.missingNodeInEdgeMapping.exception(sourceId(), nodeId);
				}
			}
		}
		// 如果目标既没有ID也没有值，则抛出异常
		else {
			throw Errors.invalidEdgeTarget.exception(sourceId());
		}

	}

	/**
	 * Checks if this edge is equal to another object.
	 * @param o the object to compare with
	 * @return true if this edge is equal to the specified object, false otherwise
	 */
	// 重写equals方法，比较两个Edge对象是否相等
	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Edge node = (Edge) o;
		return Objects.equals(sourceId, node.sourceId);
	}

	/**
	 * Returns the hash code value for this edge.
	 * @return the hash code value for this edge
	 */
	// 重写hashCode方法，返回Edge对象的哈希码
	@Override
	public int hashCode() {
		return Objects.hash(sourceId);
	}

}

