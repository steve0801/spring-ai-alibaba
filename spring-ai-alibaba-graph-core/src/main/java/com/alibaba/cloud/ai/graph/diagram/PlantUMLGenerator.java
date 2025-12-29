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
package com.alibaba.cloud.ai.graph.diagram;

import com.alibaba.cloud.ai.graph.DiagramGenerator;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static java.lang.String.format;

	// PlantUML生成器类，继承自DiagramGenerator
public class PlantUMLGenerator extends DiagramGenerator {

	// 重写父类方法，添加图表头部信息
	@Override
	protected void appendHeader(Context ctx) {

		// 如果是子图，则生成子图头部
		if (ctx.isSubGraph()) {
			ctx.sb()
				// 添加包声明，用于子图
				.append(format("package %s [\n{{\n", ctx.title()))
				// 添加开始节点（圆形）
				.append(format("circle \" \" as %s\n", START))
				// 添加结束节点（退出圆形）
				.append(format("circle exit as %s\n", END));
		}
		// 如果不是子图，则生成普通图表头部
		else {
			ctx.sb()
				// 添加PlantUML开始标记，标题转换为蛇形命名
				.append(format("@startuml %s\n", ctx.titleToSnakeCase().orElse("unnamed")))
				// 设置用例字体大小
				.append("skinparam usecaseFontSize 14\n")
				// 设置用例刻板印象字体大小
				.append("skinparam usecaseStereotypeFontSize 12\n")
				// 设置六边形字体大小
				.append("skinparam hexagonFontSize 14\n")
				// 设置六边形刻板印象字体大小
				.append("skinparam hexagonStereotypeFontSize 12\n")
				// 添加标题
				.append(format("title \"%s\"\n", ctx.title()))
				// 添加页脚开始标记
				.append("footer\n\n")
				// 添加页脚内容
				.append("powered by spring-ai-alibaba\n")
				// 添加页脚结束标记
				.append("end footer\n")
				// 添加开始节点（带输入刻板印象）
				.append(format("circle start<<input>> as %s\n", START))
				// 添加结束节点
				.append(format("circle stop as %s\n", END));
		}
	}

	// 重写父类方法，添加图表尾部信息
	@Override
	protected void appendFooter(Context ctx) {
		// 如果是子图，则添加子图结束标记
		if (ctx.isSubGraph()) {
			ctx.sb().append("}}\n]\n");
		}
		// 如果不是子图，则添加PlantUML结束标记
		else {
			ctx.sb().append("@enduml\n");
		}
	}

	// 重写父类方法，生成节点之间的连接线
	@Override
	protected void call(Context ctx, String from, String to, CallStyle style) {
		// 根据连接样式生成不同类型的连接线
		ctx.sb().append(switch (style) {
			// 条件连接使用虚线向下箭头
			case CONDITIONAL -> format("\"%s\" .down.> \"%s\"\n", from, to);
			// 默认连接使用实线向下箭头
			default -> format("\"%s\" -down-> \"%s\"\n", from, to);
		});
	}

	// 重写父类方法，生成带描述的节点连接线
	@Override
	protected void call(Context ctx, String from, String to, String description, CallStyle style) {

		// 根据连接样式生成带描述的连接线
		ctx.sb().append(switch (style) {
			// 条件连接使用虚线向下箭头并包含描述
			case CONDITIONAL -> format("\"%s\" .down.> \"%s\": \"%s\"\n", from, to, description);
			// 默认连接使用实线向下箭头并包含描述
			default -> format("\"%s\" -down-> \"%s\": \"%s\"\n", from, to, description);
		});
	}

	// 重写父类方法，声明条件开始节点
	@Override
	protected void declareConditionalStart(Context ctx, String name) {
		// 添加六边形节点表示条件检查
		ctx.sb().append(format("hexagon \"check state\" as %s<<Condition>>\n", name));
	}

	// 重写父类方法，声明普通节点
	@Override
	protected void declareNode(Context ctx, String name) {
		// 添加用例节点表示普通节点
		ctx.sb().append(format("usecase \"%s\"<<Node>>\n", name));
	}

	// 重写父类方法，声明条件边
	@Override
	protected void declareConditionalEdge(Context ctx, int ordinal) {
		// 添加六边形节点表示条件边，使用序号作为标识
		ctx.sb().append(format("hexagon \"check state\" as condition%d<<Condition>>\n", ordinal));
	}

	// 重写父类方法，添加注释行
	@Override
	protected void commentLine(Context ctx, boolean yesOrNo) {
		// 如果需要注释，则添加单引号注释符号
		if (yesOrNo)
			ctx.sb().append("'");
	}

}
