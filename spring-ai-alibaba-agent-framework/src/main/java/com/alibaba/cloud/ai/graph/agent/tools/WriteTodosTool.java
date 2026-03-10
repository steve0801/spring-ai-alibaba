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

import com.alibaba.cloud.ai.graph.agent.interceptor.todolist.TodoListInterceptor.Todo;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants.AGENT_STATE_FOR_UPDATE_CONTEXT_KEY;

/**
 * Tool for writing and managing todos in the agent workflow.
 * This tool allows agents to create, update, and track task lists.
 *
 */
public class WriteTodosTool implements BiFunction<WriteTodosTool.Request, ToolContext, WriteTodosTool.Response> {
	//public static final String DEFAULT_TOOL_DESCRIPTION = """
	//		// 定义工具的默认描述信息，详细说明了何时以及如何使用待办事项工具
	//		Use this tool to create and manage a structured task list for your current work session. This helps you track progress, organize complex tasks, and demonstrate thoroughness to the user.
	//
	//		Only use this tool if you think it will be helpful in staying organized. If the user's request is trivial and takes less than 3 steps, it is better to NOT use this tool and just do the task directly.
	//
	//		## When to Use This Tool
	//		Use this tool in these scenarios:
	//
	//		1. Complex multi-step tasks - When a task requires 3 or more distinct steps or actions
	//		2. Non-trivial and complex tasks - Tasks that require careful planning or multiple operations
	//		3. User explicitly requests todo list - When the user directly asks you to use the todo list
	//		4. User provides multiple tasks - When users provide a list of things to be done (numbered or comma-separated)
	//		5. The plan may need future revisions or updates based on results from the first few steps
	//
	//		## How to Use This Tool
	//		1. When you start working on a task - Mark it as in_progress BEFORE beginning work.
	//		2. After completing a task - Mark it as completed and add any new follow-up tasks discovered during implementation.
	//		3. You can also update future tasks, such as deleting them if they are no longer necessary, or adding new tasks that are necessary. Don't change previously completed tasks.
	//		4. You can make several updates to the todo list at once. For example, when you complete a task, you can mark the next task you need to start as in_progress.
	//
	//		## When NOT to Use This Tool
	//		It is important to skip using this tool when:
	//		1. There is only a single, straightforward task
	//		2. The task is trivial and tracking it provides no benefit
	//		3. The task can be completed in less than 3 trivial steps
	//		4. The task is purely conversational or informational
	//
	//		## Task States and Management
	//
	//		1. **Task States**: Use these states to track progress:
	//		   - pending: Task not yet started
	//		   - in_progress: Currently working on (you can have multiple tasks in_progress at a time if they are not related to each other and can be run in parallel)
	//		   - completed: Task finished successfully
	//
	//		2. **Task Management**:
	//		   - Update task status in real-time as you work
	//		   - Mark tasks complete IMMEDIATELY after finishing (don't batch completions)
	//		   - Complete current tasks before starting new ones
	//		   - Remove tasks that are no longer relevant from the list entirely
	//		   - IMPORTANT: When you write this todo list, you should mark your first task (or tasks) as in_progress immediately!.
	//		   - IMPORTANT: Unless all tasks are completed, you should always have at least one task in_progress to show the user that you are working on something.
	//
	//		3. **Task Completion Requirements**:
	//		   - ONLY mark a task as completed when you have FULLY accomplished it
	//		   - If you encounter errors, blockers, or cannot finish, keep the task as in_progress
	//		   - When blocked, create a new task describing what needs to be resolved
	//		   - Never mark a task as completed if:
	//		     - There are unresolved issues or errors
	//		     - Work is partial or incomplete
	//		     - You encountered blockers that prevent completion
	//		     - You couldn't find necessary resources or dependencies
	//		     - Quality standards haven't been met
	//
	//		4. **Task Breakdown**:
	//		   - Create specific, actionable items
	//		   - Break complex tasks into smaller, manageable steps
	//		   - Use clear, descriptive task names
	//
	//		Being proactive with task management demonstrates attentiveness and ensures you complete all requirements successfully
	//		Remember: If you only need to make a few tool calls to complete a task, and it is clear what you need to do, it is better to just do the task directly and NOT call this tool at all.
	//		""";

	public static final String DEFAULT_TOOL_DESCRIPTION = """
            // 定义工具的默认描述信息，详细说明了何时以及如何使用待办事项工具
            使用此工具来为当前工作会话创建和管理结构化的任务列表。这有助于您跟踪进度、组织复杂任务，并向用户展示您的细致性。
            
            只有在您认为它有助于保持组织性时才使用此工具。如果用户的请求很简单且只需不到3个步骤，则最好不使用此工具，直接完成任务。
            
            ## 何时使用此工具
            在以下情况下使用此工具：
            
            1. 复杂的多步骤任务 - 当任务需要3个或更多不同的步骤或操作时
            2. 非琐碎且复杂的任务 - 需要仔细规划或多个操作的任务
            3. 用户明确请求待办事项列表 - 当用户直接要求您使用待办事项列表时
            4. 用户提供多个任务 - 当用户提供一系列要完成的任务（编号或逗号分隔）时
            5. 计划可能需要根据前几个步骤的结果进行未来的修订或更新
            
            ## 如何使用此工具
            1. 当您开始处理任务时 - 在开始工作之前将其标记为 in_progress。
            2. 完成任务后 - 将其标记为 completed，并添加在实施过程中发现的任何新跟进任务。
            3. 您还可以更新未来的任务，例如，如果不再需要则删除它们，或者添加必要的新任务。不要更改已完成的任务。
            4. 您可以一次性对待办事项列表进行多次更新。例如，当您完成一个任务时，可以将下一个需要开始的任务标记为 in_progress。
            
            ## 不要使用此工具的情况
            在以下情况下，请勿使用此工具：
            1. 只有一个简单任务
            2. 任务很琐碎，跟踪它没有好处
            3. 任务可以在不到3个简单的步骤内完成
            4. 任务纯粹是对话性的或信息性的
            
            ## 任务状态和管理
            
            1. **任务状态**：使用这些状态来跟踪进度：
               - pending: 任务尚未开始
               - in_progress: 正在进行中（您可以同时有多个不相关的任务处于 in_progress 状态）
               - completed: 任务成功完成
            
            2. **任务管理**：
               - 在工作时实时更新任务状态
               - 完成任务后立即标记为 completed（不要批量完成）
               - 在开始新任务之前完成当前任务
               - 从列表中完全移除不再相关的任务
               - 重要：当您编写此待办事项列表时，应立即将第一个任务（或多个任务）标记为 in_progress！
               - 重要：除非所有任务都已完成，否则您应该始终至少有一个任务处于 in_progress 状态，以向用户显示您正在处理某项任务。
            
            3. **任务完成要求**：
               - 仅在您完全完成任务时将其标记为 completed
               - 如果遇到错误、阻碍或无法完成，请将任务保持为 in_progress
               - 当受阻时，创建一个新的任务描述需要解决的问题
               - 请勿在以下情况下将任务标记为 completed：
                 - 存在未解决的问题或错误
                 - 工作部分完成或不完整
                 - 遇到阻碍无法完成
                 - 未能找到必要的资源或依赖项
                 - 未达到质量标准
            
            4. **任务分解**：
               - 创建具体、可操作的项目
               - 将复杂任务分解为较小的、可管理的步骤
               - 使用清晰、描述性的任务名称
            
            主动进行任务管理可以展示您的关注，并确保您成功完成所有要求。
            请记住：如果您只需要调用几个工具来完成任务，并且清楚需要做什么，最好直接完成任务，而不要调用此工具。
            """;

	// WriteTodosTool的无参构造函数
	public WriteTodosTool() {
	}

	// 实现BiFunction接口的apply方法，用于处理待办事项请求
	@Override
	public Response apply(Request request, ToolContext toolContext) {
		// 从ToolContext中提取上下文数据
		Map<String, Object> contextData = toolContext.getContext();
		// 从上下文数据中获取需要更新的状态信息
		Map<String, Object> extraState = (Map<String, Object>)contextData.get(AGENT_STATE_FOR_UPDATE_CONTEXT_KEY);

		// 使用请求中的待办事项更新状态
		extraState.put("todos", request.todos);

		// 返回工具响应消息
		return new Response("Updated todo list to " + request.todos);
	}


	// 为Request记录类添加JSON类描述
	@JsonClassDescription("Request to write or update todos")
	// 定义待办事项请求参数的记录类
	public record Request(
			// 定义todos属性，标记为必需，包含待办事项列表
			@JsonProperty(required = true, value = "todos")
			// 为todos属性添加描述信息
			@JsonPropertyDescription("List of todo items with content and status")
			List<Todo> todos
	) {}

	// 定义响应结果的记录类
	public record Response(String message) {}

	// 创建Builder实例的静态方法
	public static Builder builder() {
		return new Builder();
	}

	// 内部Builder类，用于构建WriteTodosTool的ToolCallback实例
	public static class Builder {

		// 工具名称，默认为"write_todos"
		private String name = "write_todos";

		// 工具描述信息，默认使用DEFAULT_TOOL_DESCRIPTION
		private String description = DEFAULT_TOOL_DESCRIPTION;

		// Builder的无参构造函数
		public Builder() {
		}

		// 设置工具名称的方法
		public Builder withName(String name) {
			this.name = name;
			return this;
		}

		// 设置工具描述的方法
		public Builder withDescription(String description) {
			this.description = description;
			return this;
		}

		// 构建ToolCallback实例的方法
		public ToolCallback build() {
			return FunctionToolCallback.builder(name, new WriteTodosTool())
				.description(description)
				.inputType(Request.class)
				.build();
		}

	}
}

