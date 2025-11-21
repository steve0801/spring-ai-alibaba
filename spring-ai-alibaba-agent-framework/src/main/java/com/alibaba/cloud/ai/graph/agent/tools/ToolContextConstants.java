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
package com.alibaba.cloud.ai.graph.agent.tools;

public interface ToolContextConstants {
	// 定义Agent状态在上下文中的键名，用于存储和获取Agent的当前状态
	String AGENT_STATE_CONTEXT_KEY = "_AGENT_STATE_";
	// 定义Agent状态更新在上下文中的键名，用于存储和获取需要更新的Agent状态
	String AGENT_STATE_FOR_UPDATE_CONTEXT_KEY = "_AGENT_STATE_FOR_UPDATE_";
	// 定义Agent配置在上下文中的键名，用于存储和获取Agent的配置信息
	String AGENT_CONFIG_CONTEXT_KEY = "_AGENT_CONFIG_";
}
