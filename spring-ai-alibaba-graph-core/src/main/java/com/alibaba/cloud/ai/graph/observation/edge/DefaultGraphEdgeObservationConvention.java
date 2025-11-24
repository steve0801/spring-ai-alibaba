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
package com.alibaba.cloud.ai.graph.observation.edge;

import com.alibaba.cloud.ai.graph.observation.SpringAiAlibabaKind;
import com.alibaba.cloud.ai.graph.observation.edge.GraphEdgeObservationDocumentation.HighCardinalityKeyNames;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Default implementation of GraphEdgeObservationConvention. Provides standard observation
 * conventions for graph edge operations with configurable naming.
 *
 * @author XiaoYunTao
 * @since 2025/6/29
 */
public class DefaultGraphEdgeObservationConvention implements GraphEdgeObservationConvention {

	/** Default operation name for graph edge observations */
	public static final String DEFAULT_OPERATION_NAME = "spring.ai.alibaba.graph.edge";

	private String name;

	/**
	 * Constructs a default convention with the default operation name.
	 */
	public DefaultGraphEdgeObservationConvention() {
		this(DEFAULT_OPERATION_NAME);
	}

	/**
	 * Constructs a convention with a custom operation name.
	 * @param name the custom operation name
	 */
	public DefaultGraphEdgeObservationConvention(String name) {
		this.name = name;
	}

	@Override
	public String getName() {
		return this.name;
	}

	/**
	 * Generates a contextual name for the edge observation. Combines the operation name
	 * with the edge name if available.
	 */
	// 重写getContextualName方法，根据上下文生成边缘观察的上下文名称
	@Override
	@Nullable
	public String getContextualName(GraphEdgeObservationContext context) {
		// 如果上下文中的名称有文本内容，则格式化返回默认操作名和上下文名称的组合
		if (StringUtils.hasText(context.getName())) {
			return "%s.%s".formatted(DEFAULT_OPERATION_NAME, context.getName());
		}
		// 否则返回默认操作名
		return DEFAULT_OPERATION_NAME;
	}

	/**
	 * Provides low cardinality key values for edge metrics. Includes graph kind and edge
	 * name for grouping and filtering.
	 */
	// 重写getLowCardinalityKeyValues方法，为边缘指标提供低基数键值
	@Override
	public KeyValues getLowCardinalityKeyValues(GraphEdgeObservationContext context) {
		// 返回包含Spring AI Alibaba类型和图边缘名称的键值对
		return KeyValues.of(
				// 添加Spring AI Alibaba类型的键值对
				KeyValue.of(GraphEdgeObservationDocumentation.LowCardinalityKeyNames.SPRING_AI_ALIBABA_KIND,
						SpringAiAlibabaKind.GRAPH.getValue()),
				// 添加图边缘名称的键值对
				KeyValue.of(GraphEdgeObservationDocumentation.LowCardinalityKeyNames.GRAPH_NAME,
						context.getGraphEdgeName()));
	}

	/**
	 * Provides high cardinality key values for detailed edge analysis. Includes edge
	 * state and next node information.
	 */
	// 重写getHighCardinalityKeyValues方法，为详细边缘分析提供高基数键值
	@Override
	public KeyValues getHighCardinalityKeyValues(GraphEdgeObservationContext context) {
		// 创建包含边缘状态的键值对
		KeyValues keyValues = KeyValues
			.of(KeyValue.of(HighCardinalityKeyNames.GRAPH_NODE_STATE, context.getState().toString()));

		// 如果下一个节点不为空，则添加下一个节点的键值对
		if (null != context.getNextNode()) {
			keyValues.and(KeyValue.of(HighCardinalityKeyNames.GRAPH_NODE_OUTPUT, context.getNextNode()));
		}
		// 返回键值对
		return keyValues;
	}


}
