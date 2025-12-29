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
package com.alibaba.cloud.ai.graph.exception;

import java.util.Map;
import java.util.Optional;
import static java.lang.String.format;

	// 子图中断异常类，继承自GraphRunnerException
public class SubGraphInterruptionException extends GraphRunnerException {

	// 父节点ID
	final String parentNodeId;

	// 节点ID
	final String nodeId;

	// 状态映射
	final Map<String, Object> state;

	// 构造函数，创建子图中断异常实例
	public SubGraphInterruptionException(String parentNodeId, String nodeId, Map<String, Object> state) {
		// 调用父类构造函数，格式化异常消息
		super(format("interruption in subgraph: %s on node: %s", parentNodeId, nodeId));
		// 设置父节点ID
		this.parentNodeId = parentNodeId;
		// 设置节点ID
		this.nodeId = nodeId;
		// 设置状态映射
		this.state = state;
	}

	// 获取父节点ID的方法
	public String parentNodeId() {
		return parentNodeId;
	}

	// 获取节点ID的方法
	public String nodeId() {
		return nodeId;
	}

	// 获取状态映射的方法
	public Map<String, Object> state() {
		return state;
	}

	// 从Throwable中查找SubGraphInterruptionException的静态方法
	public static Optional<SubGraphInterruptionException> from(Throwable throwable) {
		// 初始化当前异常为输入的throwable
		Throwable current = throwable;
		// 遍历异常链，直到找到SubGraphInterruptionException或到达链尾
		while (current != null) {
			// 检查当前异常是否为SubGraphInterruptionException实例
			if (current instanceof SubGraphInterruptionException ex) {
				// 如果是，返回包含该异常的Optional
				return Optional.of(ex);
			}
			// 移动到异常链的下一个异常（原因）
			current = current.getCause();
		}
		// 如果没有找到SubGraphInterruptionException，返回空Optional
		return Optional.empty();
	}

}
