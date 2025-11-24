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

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @param id The unique identifier for the edge value.
 * @param value The condition associated with the edge value.
 */
public record EdgeValue(String id, EdgeCondition value) {

	public EdgeValue(String id) {
		this(id, null);
	}

	public EdgeValue(EdgeCondition value) {
		this(null, value);
	}

	// 更新目标ID的方法，接收一个函数用于转换目标ID
	EdgeValue withTargetIdsUpdated(Function<String, EdgeValue> target) {
		// 如果当前EdgeValue有id，则直接应用target函数进行转换
		if (id != null) {
			return target.apply(id);
		}

		// 如果没有id，则处理value中的映射关系
		// 创建新的映射关系，对每个现有的映射值应用target函数
		var newMappings = value.mappings().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> {
			// 对映射值应用target函数
			var v = target.apply(e.getValue());
			// 如果转换后的EdgeValue有id，则使用该id，否则保持原来的值
			return (v.id() != null) ? v.id() : e.getValue();
		}));

		// 返回新的EdgeValue，id为null，value为新的EdgeCondition
		return new EdgeValue(null, new EdgeCondition(value.action(), newMappings));

	}


}
