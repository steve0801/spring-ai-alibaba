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

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.SubGraphNode;

	// 子状态图节点类，继承自Node并实现SubGraphNode接口
public class SubStateGraphNode extends Node implements SubGraphNode {

	// 子图的状态图实例
	private final StateGraph subGraph;

	// 构造函数，创建子状态图节点
	public SubStateGraphNode(String id, StateGraph subGraph) {
		// 调用父类构造函数，只传入ID
		super(id);
		// 初始化子图
		this.subGraph = subGraph;
	}

	// 获取子图的方法
	public StateGraph subGraph() {
		return subGraph;
	}

	// 格式化节点ID的方法
	public String formatId(String nodeId) {
		// 使用SubGraphNode接口的静态方法格式化ID
		return SubGraphNode.formatId(id(), nodeId);
	}

}
