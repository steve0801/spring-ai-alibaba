/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.graph.internal.node;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.exception.Errors;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;

import java.util.Objects;
import java.util.function.Function;

import static java.lang.String.format;

/**
 * Represents a node in a graph, characterized by a unique identifier and a factory for
 * creating actions to be executed by the node. This is a generic record where the state
 * type is specified by the type parameter {@code State}.
 *
 * {@link OverAllState}.
 *
 */
	// 节点类，表示图中的一个节点，具有唯一标识符和用于创建节点动作的工厂
public class Node {

	// 私有节点前缀常量，用于标识私有节点
	public static final String PRIVATE_PREFIX = "__";

	// 动作工厂接口，用于创建异步节点动作
	public interface ActionFactory {

		// 根据编译配置创建异步节点动作的方法
		AsyncNodeActionWithConfig apply(CompileConfig config) throws GraphStateException;

	}

	// 节点的唯一标识符
	private final String id;

	// 节点动作工厂
	private final ActionFactory actionFactory;

	// 使用ID和动作工厂构造节点的构造函数
	public Node(String id, ActionFactory actionFactory) {
		// 初始化节点ID
		this.id = id;
		// 初始化动作工厂
		this.actionFactory = actionFactory;
	}

	/**
	 * 只接受id的构造函数，并将actionFactory设置为null
	 * @param id 节点的唯一标识符
	 */
	// 使用ID构造节点的构造函数，动作工厂设置为null
	public Node(String id) {
		// 调用带动作工厂的构造函数，传入null作为动作工厂
		this(id, null);
	}

	// 验证节点的合法性
	public void validate() throws GraphStateException {
		// 如果节点ID是END或START，则直接返回，无需验证
		if (Objects.equals(id, StateGraph.END) || Objects.equals(id, StateGraph.START)) {
			return;
		}

		// 如果节点ID是空白的，抛出无效节点标识符异常
		if (id.isBlank()) {
			throw Errors.invalidNodeIdentifier.exception("blank node id");
		}

		// 如果节点ID以私有前缀开头，抛出无效节点标识符异常
		if (id.startsWith(PRIVATE_PREFIX)) {
			throw Errors.invalidNodeIdentifier.exception("id that start with %s", PRIVATE_PREFIX);
		}
	}

	/**
	 * 获取节点ID
	 * @return 节点的唯一标识符
	 */
	// 获取节点ID的方法
	public String id() {
		return id;
	}

	/**
	 * 获取动作工厂
	 * @return 一个工厂函数，接受CompileConfig并返回AsyncNodeActionWithConfig实例
	 */
	// 获取动作工厂的方法
	public ActionFactory actionFactory() {
		return actionFactory;
	}

	// 检查节点是否为并行节点
	public boolean isParallel() {
		// return id.startsWith(PARALLEL_PREFIX);
		// 返回false表示当前实现不支持并行节点
		return false;
	}

	// 使用更新后的ID创建新节点
	public Node withIdUpdated(Function<String, String> newId) {
		// 使用新的ID和原有的动作工厂创建新节点
		return new Node(newId.apply(id), actionFactory);
	}

	/**
	 * 检查此节点是否与另一个对象相等
	 * @param o 要比较的对象
	 * @return 如果此节点等于指定对象，则返回true，否则返回false
	 */
	// 重写equals方法以比较节点
	@Override
	public boolean equals(Object o) {
		// 如果是同一个对象，返回true
		if (this == o)
			return true;
		// 如果对象为null，返回false
		if (o == null)
			return false;
		// 如果对象是Node类型，比较ID是否相等
		if (o instanceof Node node) {
			return Objects.equals(id, node.id);
		}
		// 其他情况返回false
		return false;

	}

	/**
	 * 返回此节点的哈希码值
	 * @return 此节点的哈希码值
	 */
	// 重写hashCode方法以计算节点哈希码
	@Override
	public int hashCode() {
		// 使用ID计算哈希码
		return Objects.hash(id);
	}

	// 重写toString方法以返回节点的字符串表示
	@Override
	public String toString() {
		// 格式化输出节点信息，如果动作工厂不为null则显示"action"，否则显示"null"
		return format("Node(%s,%s)", id, actionFactory != null ? "action" : "null");
	}

}
