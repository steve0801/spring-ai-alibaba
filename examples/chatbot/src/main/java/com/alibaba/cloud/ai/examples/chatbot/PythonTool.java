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
package com.alibaba.cloud.ai.examples.chatbot;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.function.BiFunction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool for executing Python code using GraalVM polyglot.
 *
 * This tool allows the agent to execute Python code snippets and get results.
 * It uses GraalVM's polyglot API to run Python code in a sandboxed environment.
 */
public class PythonTool implements BiFunction<PythonTool.PythonRequest, ToolContext, String> {

	public static final String DESCRIPTION = """
			Executes Python code and returns the result.
			
			Usage:
			- The code parameter must be valid Python code
			- The tool will execute the code and return the output
			- If the code produces a result, it will be returned as a string
			- Errors will be caught and returned as error messages
			- The execution is sandboxed for security
			
			Examples:
			- Simple calculation: code = "2 + 2" returns "4"
			- String operations: code = "'Hello, ' + 'World'" returns "Hello, World"
			- List operations: code = "[1, 2, 3][0]" returns "1"
			""";
	// 定义工具描述信息，包括功能说明、使用方法和示例

	private static final Logger log = LoggerFactory.getLogger(PythonTool.class);
	// 创建日志记录器实例，用于记录执行过程中的日志信息

	private final Engine engine;
	// 声明GraalVM引擎实例，用于创建Python执行上下文

	public PythonTool() {
		// 构造函数，初始化Python执行引擎
		// Create a shared engine for better performance
		// 创建共享引擎以提高性能
		this.engine = Engine.newBuilder()
				.option("engine.WarnInterpreterOnly", "false")
				// 设置引擎选项，关闭仅解释器模式警告
				.build();
				// 构建并返回Engine实例
	}

	/**
	 * Create a ToolCallback for the Python tool.
	 */
	 // 创建Python工具回调函数的工厂方法
	public static ToolCallback createPythonToolCallback(String description) {
		// 使用FunctionToolCallback构建器创建工具回调
		return FunctionToolCallback.builder("python", new PythonTool())
				.description(description)
				// 设置工具描述
				.inputType(PythonRequest.class)
				// 指定输入参数类型为PythonRequest
				.build();
				// 构建并返回ToolCallback实例
	}

	@Override
	public String apply(PythonRequest request, ToolContext toolContext) {
		// 实现BiFunction接口的apply方法，处理Python代码执行请求
		if (request.code == null || request.code.trim().isEmpty()) {
			// 检查传入的Python代码是否为空或仅包含空白字符
			return "Error: Python code cannot be empty";
			// 如果代码为空，则返回错误信息
		}

		try (Context context = Context.newBuilder("python")
				// 创建新的Python执行上下文
				.engine(engine)
				// 使用预定义的引擎实例
				.allowAllAccess(false) // Security: restrict access by default
				// 安全设置：默认禁止所有访问权限
				.allowIO(false) // Disable file I/O for security
				// 安全设置：禁用文件I/O操作
				.allowNativeAccess(false) // Disable native access for security
				// 安全设置：禁用本地访问权限
				.allowCreateProcess(false) // Disable process creation for security
				// 安全设置：禁止创建新进程
				.allowHostAccess(true) // Allow access to host objects
				// 允许访问宿主对象
				.build()) {
				// 构建并返回Context实例

			log.debug("Executing Python code: {}", request.code);
			// 记录调试日志，显示即将执行的Python代码

			// Execute the Python code
			// 执行Python代码
			Value result = context.eval("python", request.code);
			// 在Python上下文中评估执行代码，并获取结果

			// Convert result to string
			// 将结果转换为字符串形式
			if (result.isNull()) {
				// 判断结果是否为null
				return "Execution completed with no return value";
				// 如果结果为null，返回无返回值提示信息
			}

			// Handle different result types
			// 处理不同类型的结果
			if (result.isString()) {
				// 判断结果是否为字符串类型
				return result.asString();
				// 直接返回字符串结果
			}
			else if (result.isNumber()) {
				// 判断结果是否为数字类型
				return String.valueOf(result.as(Object.class));
				// 将数字转换为字符串后返回
			}
			else if (result.isBoolean()) {
				// 判断结果是否为布尔类型
				return String.valueOf(result.asBoolean());
				// 将布尔值转换为字符串后返回
			}
			else if (result.hasArrayElements()) {
				// 判断结果是否为数组或列表类型
				// Convert array/list to string representation
				// 将数组/列表转换为字符串表示形式
				StringBuilder sb = new StringBuilder("[");
				// 创建StringBuilder用于构建结果字符串，起始为"["
				long size = result.getArraySize();
				// 获取数组大小
				for (long i = 0; i < size; i++) {
					// 遍历数组中的每个元素
					if (i > 0) {
						// 如果不是第一个元素
						sb.append(", ");
						// 添加逗号和空格作为分隔符
					}
					Value element = result.getArrayElement(i);
					// 获取当前索引的数组元素
					sb.append(element.toString());
					// 将元素转换为字符串并添加到结果中
				}
				sb.append("]");
				// 结束数组表示，添加"]"
				return sb.toString();
				// 返回构建好的数组字符串表示
			}
			else {
				// For other types, use toString()
				// 对于其他类型，使用toString()方法
				return result.toString();
				// 返回结果的字符串表示
			}
		}
		catch (PolyglotException e) {
			// 捕获多语言执行异常
			log.error("Error executing Python code", e);
			// 记录错误日志
			return "Error executing Python code: " + e.getMessage();
			// 返回包含异常信息的错误消息
		}
		catch (Exception e) {
			// 捕获其他未预期异常
			log.error("Unexpected error executing Python code", e);
			// 记录错误日志
			return "Unexpected error: " + e.getMessage();
			// 返回包含异常信息的错误消息
		}
	}

	/**
	 * Request structure for the Python tool.
	 */
	 // Python工具的请求结构体定义
	public static class PythonRequest {

		@JsonProperty(required = true)
		// 标记该字段为JSON属性且必需
		@JsonPropertyDescription("The Python code to execute")
		// 为该字段添加描述信息
		public String code;
		// 存储待执行的Python代码

		public PythonRequest() {
			// 默认构造函数
		}

		public PythonRequest(String code) {
			// 带参数的构造函数
			this.code = code;
			// 初始化code字段
		}
	}

}

