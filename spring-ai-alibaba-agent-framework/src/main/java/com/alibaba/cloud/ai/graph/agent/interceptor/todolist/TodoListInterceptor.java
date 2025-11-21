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
package com.alibaba.cloud.ai.graph.agent.interceptor.todolist;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.tools.WriteTodosTool;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

import static com.alibaba.cloud.ai.graph.agent.tools.WriteTodosTool.DEFAULT_TOOL_DESCRIPTION;

/**
 * Model interceptor that provides todo list management capabilities to agents.
 *
 * This interceptor enhances the system prompt to guide agents on using todo lists
 * for complex multi-step operations. It helps agents:
 * - Track progress on complex tasks
 * - Organize work into manageable steps
 * - Provide users with visibility into task completion
 *
 * The interceptor automatically injects system prompts that guide the agent on when
 * and how to use the todo functionality effectively.
 *
 * Example:
 * TodoListInterceptor interceptor = TodoListInterceptor.builder()
 *     .systemPrompt("Custom guidance for using todos...")
 *     .build();
 */
public class TodoListInterceptor extends ModelInterceptor {

	// 定义默认的系统提示信息，指导代理如何使用待办事项列表功能
	private static final String DEFAULT_SYSTEM_PROMPT = """
			## `write_todos`
			
			You have access to the `write_todos` tool to help you manage and plan complex objectives.
			Use this tool for complex objectives to ensure that you are tracking each necessary step and giving the user visibility into your progress.
			This tool is very helpful for planning complex objectives, and for breaking down these larger complex objectives into smaller steps.
			
			It is critical that you mark todos as completed as soon as you are done with a step. Do not batch up multiple steps before marking them as completed.
			For simple objectives that only require a few steps, it is better to just complete the objective directly and NOT use this tool.
			Writing todos takes time and tokens, use it when it is helpful for managing complex many-step problems! But not for simple few-step requests.
			
			## Important To-Do List Usage Notes to Remember
			- The `write_todos` tool should never be called multiple times in parallel.
			- Don't be afraid to revise the To-Do list as you go. New information may reveal new tasks that need to be done, or old tasks that are irrelevant.
			""";

	// 存储工具回调列表，包含待办事项工具
	private final List<ToolCallback> tools;
	// 存储系统提示信息
	private final String systemPrompt;
	// 存储工具描述信息
	private final String toolDescription;

	// 私有构造函数，通过Builder模式创建TodoListInterceptor实例
	private TodoListInterceptor(Builder builder) {
		// 创建write_todos工具并使用自定义描述
		this.tools = Collections.singletonList(
				WriteTodosTool.builder().
						withName("write_todos")
						.withDescription(builder.toolDescription)
						.build()
		);
		// 设置系统提示信息
		this.systemPrompt = builder.systemPrompt;
		// 设置工具描述信息
		this.toolDescription = builder.toolDescription;
	}

	// 静态方法，返回Builder实例用于创建TodoListInterceptor
	public static Builder builder() {
		return new Builder();
	}

	// 获取工具回调列表的方法
	@Override
	public List<ToolCallback> getTools() {
		return tools;
	}

	// 获取拦截器名称的方法
	@Override
	public String getName() {
		return "TodoList";
	}

	// 拦截模型调用的核心方法，增强系统提示信息
	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		// 声明增强的系统消息变量
		SystemMessage enhancedSystemMessage;

		// 如果原始请求中没有系统消息，则使用默认系统提示
		if (request.getSystemMessage() == null) {
			enhancedSystemMessage = new SystemMessage(this.systemPrompt);
		} else {
			// 如果原始请求中有系统消息，则将原始消息与系统提示合并
			enhancedSystemMessage = new SystemMessage(request.getSystemMessage().getText() + "\n\n" + systemPrompt);
		}

		// 创建增强的请求对象
		ModelRequest enhancedRequest = ModelRequest.builder(request)
				.systemMessage(enhancedSystemMessage)
				.build();

		// 使用增强的请求调用处理器
		return handler.call(enhancedRequest);
	}

	/**
	 * Todo item status.
	 */
	// 待办事项状态枚举，使用JsonFormat注解指定序列化为字符串格式
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	public enum TodoStatus {
		// 定义三种待办事项状态：待处理、进行中、已完成
		PENDING("pending"),
		IN_PROGRESS("in_progress"),
		COMPLETED("completed");

		// 存储状态值
		private final String value;

		// 构造函数，初始化状态值
		TodoStatus(String value) {
			this.value = value;
		}

		// JsonCreator注解，用于从字符串值创建枚举实例
		@JsonCreator
		public static TodoStatus fromValue(String value) {
			// 检查输入值是否为空
			if (value == null) {
				throw new IllegalArgumentException("Status value cannot be null");
			}

			// 首先尝试匹配小写值
			for (TodoStatus status : values()) {
				if (status.value.equals(value)) {
					return status;
				}
			}

			// 备用方案：尝试匹配枚举常量名称（不区分大小写）
			try {
				return TodoStatus.valueOf(value.toUpperCase());
			}
			// 如果匹配失败，抛出有用的错误信息
			catch (IllegalArgumentException e) {
				// 如果也失败了，抛出有帮助的错误
				throw new IllegalArgumentException(
						"Unknown status: " + value + ". Valid values are: pending, in_progress, completed");
			}
		}

		// JsonValue注解，用于获取枚举的字符串值
		@JsonValue
		public String getValue() {
			return value;
		}
	}

	/**
	 * Represents a single todo item.
	 */
	// 表示单个待办事项的类
	public static class Todo {
		// 待办事项内容
		private String content;
		// 待办事项状态
		private TodoStatus status;

		// 默认构造函数
		public Todo() {
		}

		// 带参数的构造函数
		public Todo(String content, TodoStatus status) {
			this.content = content;
			this.status = status;
		}

		// 获取待办事项内容
		public String getContent() {
			return content;
		}

		// 设置待办事项内容
		public void setContent(String content) {
			this.content = content;
		}

		// 获取待办事项状态
		public TodoStatus getStatus() {
			return status;
		}

		// 设置待办事项状态
		public void setStatus(TodoStatus status) {
			this.status = status;
		}

		// 重写toString方法，用于输出待办事项信息
		@Override
		public String toString() {
			return String.format("Todo{content='%s', status=%s}", content, status);
		}
	}

	// 构建器类，用于创建TodoListInterceptor实例
	public static class Builder {
		// 系统提示信息，默认使用DEFAULT_SYSTEM_PROMPT
		private String systemPrompt = DEFAULT_SYSTEM_PROMPT;
		// 工具描述信息，默认使用DEFAULT_TOOL_DESCRIPTION
		private String toolDescription = DEFAULT_TOOL_DESCRIPTION;

		/**
		 * Set a custom system prompt for guiding todo usage.
		 */
		// 设置自定义系统提示信息的方法
		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		/**
		 * Set a custom tool description for the write_todos tool.
		 */
		// 设置自定义工具描述信息的方法
		public Builder toolDescription(String toolDescription) {
			this.toolDescription = toolDescription;
			return this;
		}

		// 构建TodoListInterceptor实例的方法
		public TodoListInterceptor build() {
			return new TodoListInterceptor(this);
		}
	}
}

