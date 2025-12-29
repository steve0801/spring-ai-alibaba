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
package com.alibaba.cloud.ai.graph.checkpoint.config;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import java.util.ArrayList;
import java.util.List;

// 保存器配置类，用于管理多个检查点保存器
public class SaverConfig {

	// 保存器列表，用于存储所有的检查点保存器
	private final List<BaseCheckpointSaver> savers = new ArrayList<>();

	// 创建构建器的静态方法
	public static Builder builder() {
		// 返回新的构建器实例
		return new Builder();
	}

	// 注册保存器的方法
	public SaverConfig register(BaseCheckpointSaver saver) {
		// 将保存器添加到列表中
		savers.add(saver);
		// 返回当前实例以支持链式调用
		return this;
	}

	// 获取保存器的方法
	public BaseCheckpointSaver get() {
		// 检查保存器列表是否为空
		if (savers.isEmpty()) {
			// 如果为空则返回null
			return null;
		}
		// 检查保存器列表大小是否为1
		if (savers.size() == 1) {
			// 如果只有一个保存器，则返回该保存器
			return savers.get(0);
		}
		// 如果有多个保存器但没有指定具体哪一个，则抛出非法状态异常
		throw new IllegalStateException("Multiple savers configured, but no specific one requested.");
	}

	// 获取所有保存器的方法
	public List<BaseCheckpointSaver> getAll() {
		// 返回保存器列表
		return savers;
	}

	// SaverConfig的构建器类
	public static class Builder {

		// 构建器持有的配置实例
		private final SaverConfig config;

		// 构建器的私有构造函数
		Builder() {
			// 创建新的配置实例
			this.config = new SaverConfig();
		}

		// 注册保存器到配置的构建器方法
		public Builder register(BaseCheckpointSaver saver) {
			// 调用配置实例的register方法
			this.config.register(saver);
			// 返回当前构建器实例以支持链式调用
			return this;
		}

		// 构建SaverConfig实例的方法
		public SaverConfig build() {
			// 返回配置实例
			return this.config;
		}

	}

}
