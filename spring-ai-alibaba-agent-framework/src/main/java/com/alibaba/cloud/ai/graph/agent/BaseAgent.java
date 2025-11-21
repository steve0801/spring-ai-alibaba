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
package com.alibaba.cloud.ai.graph.agent;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.internal.node.Node;

import java.lang.reflect.Type;

public abstract class BaseAgent extends Agent {

	// 定义输入模式字符串，用于描述代理期望的输入格式
	protected String inputSchema;
	// 定义输入类型，表示代理期望的输入数据类型
	protected Type inputType;

	// 定义输出模式字符串，用于描述代理返回的输出格式
	protected String outputSchema;
	// 定义输出类型，表示代理返回的输出数据类型
	protected Class<?> outputType;

	// 代理结果的输出键，用于在状态中存储代理的输出结果
	/** The output key for the agent's result */
	protected String outputKey;

	// 输出键的策略，定义如何处理输出键的更新
	protected KeyStrategy outputKeyStrategy;

	// 是否包含内容的标志，控制代理是否在输出中包含详细内容
	protected boolean includeContents;

	// 是否返回推理内容的标志，控制代理是否返回推理过程中的中间内容
	protected boolean returnReasoningContents;

	// BaseAgent的构造函数，初始化所有基础属性
	public BaseAgent(String name, String description, boolean includeContents, boolean returnReasoningContents, String outputKey,
			KeyStrategy outputKeyStrategy) {
		// 调用父类Agent的构造函数初始化名称和描述
		super(name, description);
		// 初始化是否包含内容的标志
		this.includeContents = includeContents;
		// 初始化是否返回推理内容的标志
		this.returnReasoningContents = returnReasoningContents;
		// 初始化输出键
		this.outputKey = outputKey;
		// 初始化输出键策略
		this.outputKeyStrategy = outputKeyStrategy;
	}

	// 抽象方法，将代理转换为节点的表示形式
	public abstract Node asNode(boolean includeContents, boolean returnReasoningContents, String outputKeyToParent);

	// 获取是否包含内容的标志
	public boolean isIncludeContents() {
		return includeContents;
	}

	// 获取输出键
	public String getOutputKey() {
		return outputKey;
	}

	// 设置输出键
	public void setOutputKey(String outputKey) {
		this.outputKey = outputKey;
	}

	// 获取输出键策略
	public KeyStrategy getOutputKeyStrategy() {
		return outputKeyStrategy;
	}

	// 设置输出键策略
	public void setOutputKeyStrategy(KeyStrategy outputKeyStrategy) {
		this.outputKeyStrategy = outputKeyStrategy;
	}

	// 获取输入模式
	String getInputSchema() {
		return inputSchema;
	}

	// 设置输入模式
	void setInputSchema(String inputSchema) {
		this.inputSchema = inputSchema;
	}

	// 获取输入类型
	Type getInputType() {
		return inputType;
	}

	// 设置输入类型
	void setInputType(Type inputType) {
		this.inputType = inputType;
	}

	// 获取输出模式
	String getOutputSchema() {
		return outputSchema;
	}

	// 设置输出模式
	void setOutputSchema(String outputSchema) {
		this.outputSchema = outputSchema;
	}

	// 设置是否包含内容的标志
	void setIncludeContents(boolean includeContents) {
		this.includeContents = includeContents;
	}

	// 获取是否返回推理内容的标志
	public boolean isReturnReasoningContents() {
		return returnReasoningContents;
	}

	// 设置是否返回推理内容的标志
	public void setReturnReasoningContents(boolean returnReasoningContents) {
		this.returnReasoningContents = returnReasoningContents;
	}
}
