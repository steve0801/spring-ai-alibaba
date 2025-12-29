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
package com.alibaba.cloud.ai.graph.observation.graph;

import com.alibaba.cloud.ai.graph.observation.SpringAiAlibabaKind;
import com.alibaba.cloud.ai.graph.observation.graph.GraphObservationDocumentation.HighCardinalityKeyNames;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Default implementation of GraphObservationConvention. Provides standard observation
 * conventions for graph operations with configurable naming.
 *
 * @author XiaoYunTao
 * @since 2025/6/29
 */
	// 默认图观察约定类，实现GraphObservationConvention接口
public class DefaultGraphObservationConvention implements GraphObservationConvention {

	/** 图观察的默认操作名称 */
	// 默认操作名称常量
	public static final String DEFAULT_OPERATION_NAME = "spring.ai.alibaba.graph";

	// 操作名称字段
	private String name;

	/**
	 * 使用默认操作名称构造默认约定
	 */
	// 默认构造函数，使用默认操作名称
	public DefaultGraphObservationConvention() {
		// 调用带参数的构造函数，传入默认操作名称
		this(DEFAULT_OPERATION_NAME);
	}

	/**
	 * 使用自定义操作名称构造约定
	 * @param name 自定义操作名称
	 */
	// 带自定义名称的构造函数
	public DefaultGraphObservationConvention(String name) {
		// 设置操作名称
		this.name = name;
	}

	// 重写getName方法，返回操作名称
	@Override
	public String getName() {
		return this.name;
	}

	/**
	 * 为观察生成上下文名称。将操作名称与图名称（如果可用）结合
	 */
	// 重写getContextualName方法，生成上下文名称
	@Override
	@Nullable
	public String getContextualName(GraphObservationContext context) {
		// 检查上下文名称是否不为空
		if (StringUtils.hasText(context.getName())) {
			// 返回格式化的操作名称和上下文名称的组合
			return "%s.%s".formatted(DEFAULT_OPERATION_NAME, context.getName());
		}
		// 如果上下文名称为空，返回默认操作名称
		return DEFAULT_OPERATION_NAME;
	}

	/**
	 * 提供用于指标的低基数键值。包括用于分组和过滤的图类型和名称
	 */
	// 重写getLowCardinalityKeyValues方法，提供低基数键值
	@Override
	public KeyValues getLowCardinalityKeyValues(GraphObservationContext context) {
		// 返回包含图类型和图名称的键值对
		return KeyValues.of(
				// 设置Spring AI Alibaba类型为图类型
				KeyValue.of(GraphObservationDocumentation.LowCardinalityKeyNames.SPRING_AI_ALIBABA_KIND,
						SpringAiAlibabaKind.GRAPH.getValue()),
				// 设置图名称
				KeyValue.of(GraphObservationDocumentation.LowCardinalityKeyNames.GRAPH_NAME, context.getGraphName()));
	}

	/**
	 * 提供用于详细分析的高基数键值。包括图状态和输出信息
	 */
	// 重写getHighCardinalityKeyValues方法，提供高基数键值
	@Override
	public KeyValues getHighCardinalityKeyValues(GraphObservationContext context) {
		// 创建包含图节点状态的键值对
		KeyValues keyValues = KeyValues
			.of(KeyValue.of(HighCardinalityKeyNames.GRAPH_NODE_STATE, context.getState().toString()));
		// 检查输出是否不为null
		if (context.getOutput() != null) {
			// 如果输出不为null，则添加输出信息到键值对
			keyValues.and(KeyValue.of(HighCardinalityKeyNames.GRAPH_NODE_OUTPUT, context.getOutput().toString()));
		}
		// 返回键值对
		return keyValues;
	}

}
