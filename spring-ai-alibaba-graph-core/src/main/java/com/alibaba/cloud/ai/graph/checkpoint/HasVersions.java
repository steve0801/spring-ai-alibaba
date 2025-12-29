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

import com.alibaba.cloud.ai.graph.RunnableConfig;

import java.util.Collection;
import java.util.Optional;

/**
 * Represents an entity that can have different versions associated with it. Experimental
 * feature
 */
	// 版本化实体接口，定义了与版本相关的操作方法
public interface HasVersions {

	/**
	 * 获取与指定线程ID关联的整数版本集合
	 * @param threadId 要检索版本信息的线程ID
	 * @return 包含版本的Collection<Integer>，如果未找到版本则返回空集合
	 */
	// 根据线程ID获取版本集合
	Collection<Integer> versionsByThreadId(String threadId);

	/**
	 * 从给定的RunnableConfig中获取与特定线程ID关联的版本集合
	 * @param config 包含线程ID信息的配置对象
	 * @return 表示与线程ID关联的版本的Collection<Integer>，如果没有指定则返回空集合
	 */
	// 根据配置对象获取版本集合的默认实现
	default Collection<Integer> versionsByThreadId(RunnableConfig config) {
		// 从配置中获取线程ID，如果不存在则为null，然后调用基于字符串线程ID的方法
		return versionsByThreadId(config.threadId().orElse(null));
	}

	/**
	 * 获取与特定线程ID关联的最后版本
	 * @param threadId 线程的唯一标识符
	 * @return 如果找到则包含最后版本的Optional，否则为空Optional
	 */
	// 根据线程ID获取最后版本
	Optional<Integer> lastVersionByThreadId(String threadId);

	/**
	 * 获取与特定线程ID关联的最后版本
	 * @param config 包含线程ID的配置
	 * @return 如果找到则包含最后版本的Optional，否则为空Optional
	 */
	// 根据配置对象获取最后版本的默认实现
	default Optional<Integer> lastVersionByThreadId(RunnableConfig config) {
		// 从配置中获取线程ID，如果不存在则为null，然后调用基于字符串线程ID的方法
		return lastVersionByThreadId(config.threadId().orElse(null));
	}

}
