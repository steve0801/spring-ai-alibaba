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

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.util.json.JsonParser;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.lang.reflect.Type;

import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageToolCallResultConverter implements ToolCallResultConverter {

	private static final Logger logger = LoggerFactory.getLogger(MessageToolCallResultConverter.class);

	/**
	 * Currently Spring AI ToolResponseMessage only supports text type, that's why the return type of this method is String.
	 * More types like image/audio/video/file can be supported in the future.
	 */
	public String convert(@Nullable Object result, @Nullable Type returnType) {
		// 如果返回类型为Void.TYPE，表示工具没有返回值
		if (returnType == Void.TYPE) {
			// 记录调试日志，说明工具没有返回类型，正在转换为常规响应
			logger.debug("The tool has no return type. Converting to conventional response.");
			// 将"Done"字符串转换为JSON格式并返回
			return JsonParser.toJson("Done");
		// 如果结果是AssistantMessage类型
		} else if (result instanceof AssistantMessage assistantMessage) {
			// 检查AssistantMessage的文本内容是否非空
			if (StringUtils.hasLength(assistantMessage.getText())) {
				// 返回AssistantMessage的文本内容
				return assistantMessage.getText();
			// 检查AssistantMessage是否包含媒体内容
			} else if (CollectionUtils.isNotEmpty(assistantMessage.getMedia())) {
				// 抛出不支持操作异常，说明当前Spring AI ToolResponseMessage仅支持文本类型
				throw new UnsupportedOperationException("Currently Spring AI ToolResponseMessage only supports text type, that's why the return type of this method is String. More types like image/audio/video/file can be supported in the future.");
			}
			// 记录警告日志，说明工具返回了空的AssistantMessage，正在转换为常规响应
			logger.warn("The tool returned an empty AssistantMessage. Converting to conventional response.");
			// 将"Done"字符串转换为JSON格式并返回
			return JsonParser.toJson("Done");
		// 其他情况
		} else {
			// 记录调试日志，说明正在将工具结果转换为JSON格式
			logger.debug("Converting tool result to JSON.");
			// 将结果对象转换为JSON格式并返回
			return JsonParser.toJson(result);
		}
	}
}
