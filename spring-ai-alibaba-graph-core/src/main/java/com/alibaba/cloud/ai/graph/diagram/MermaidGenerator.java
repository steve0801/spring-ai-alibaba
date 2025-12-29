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
import static java.util.Optional.ofNullable;

/**
 * This class represents a MermaidGenerator that extends DiagramGenerator. It generates a
 * flowchart using Mermaid syntax. The flowchart includes various nodes such as start,
 * stop, web_search, retrieve, grade_documents, generate, transform_query, and different
 * conditional states.
 */
	// Mermaid图表生成器类，继承自DiagramGenerator
public class MermaidGenerator extends DiagramGenerator {

	// 子图节点名称前缀常量
	public static final char SUBGRAPH_PREFIX = '_';

	// 格式化节点名称的方法，根据上下文决定是否添加前缀或后缀
	private String formatNode(String id, Context ctx) {
		// 如果不是子图，则直接返回原ID
		if (!ctx.isSubGraph()) {
			return id;
		}

		// 如果当前子图中已存在相同ID的节点，则直接返回原ID
		if (ctx.anySubGraphWithId(id)) {
			return id;
		}

		// 如果是开始或结束节点，则在ID后添加标题
		if (isStart(id) || isEnd(id)) {
			return format("%s%s", id, ctx.title());
		}

		// 对于其他节点，在ID和标题之间添加下划线分隔符
		return format("%s_%s", id, ctx.title());
	}

	// 重写父类方法，添加图表头部信息
	@Override
	protected void appendHeader(Context ctx) {
		// 如果是子图，则生成子图头部
		if (ctx.isSubGraph()) {
			ctx.sb()
				// 添加子图声明
				.append(format("subgraph %s\n", ctx.title()))
				// 添加开始节点
				.append(format("\t%1$s((start)):::%1$s\n", formatNode(START, ctx)))
				// 添加结束节点
				.append(format("\t%1$s((stop)):::%1$s\n", formatNode(END, ctx)));
		}
		// 如果不是子图，则生成普通图表头部
		else {
			// 如果有标题则添加标题信息
			ofNullable(ctx.title()).map(title -> ctx.sb().append(format("---\ntitle: %s\n---\n", title)))
				// 否则直接使用StringBuilder
				.orElseGet(ctx::sb)
				// 添加流程图声明
				.append("flowchart TD\n")
				// 添加开始节点
				.append(format("\t%s((start))\n", START))
				// 添加结束节点
				.append(format("\t%s((stop))\n", END));
		}
	}

	// 重写父类方法，添加图表尾部信息
	@Override
	protected void appendFooter(Context ctx) {
		// 如果是子图，则添加子图结束标记
		if (ctx.isSubGraph()) {
			ctx.sb().append("end\n");
		}
		// 如果不是子图，则添加样式定义
		else {
			ctx.sb()
				// 添加换行
				.append('\n')
				// 为开始节点定义样式
				.append(format("\tclassDef %s fill:black,stroke-width:1px,font-size:xx-small;\n",
						formatNode(START, ctx)))
				// 为结束节点定义样式
				.append(format("\tclassDef %s fill:black,stroke-width:1px,font-size:xx-small;\n",
						formatNode(END, ctx)));
		}
	}

	// 重写父类方法，声明条件开始节点
	@Override
	protected void declareConditionalStart(Context ctx, String name) {
		// 添加制表符
		ctx.sb().append('\t');
		// 添加条件节点，格式为菱形
		ctx.sb().append(format("%s{\"check state\"}\n", formatNode(name, ctx)));
	}

	// 重写父类方法，声明普通节点
	@Override
	protected void declareNode(Context ctx, String name) {
		// 添加制表符
		ctx.sb().append('\t');
		// 添加普通节点，格式为矩形
		ctx.sb().append(format("%s(\"%s\")\n", formatNode(name, ctx), name));
	}

	// 重写父类方法，声明条件边
	@Override
	protected void declareConditionalEdge(Context ctx, int ordinal) {
		// 添加制表符
		ctx.sb().append('\t');
		// 生成条件节点，使用序号作为标识
		ctx.sb().append(format("%s{\"check state\"}\n", formatNode(format("condition%d", ordinal), ctx)));
	}

	// 重写父类方法，添加注释行
	@Override
	protected void commentLine(Context ctx, boolean yesOrNo) {
		// 如果需要注释，则添加注释符号
		if (yesOrNo)
			ctx.sb().append("\t%%");
	}

	// 重写父类方法，生成节点之间的连接线
	@Override
	protected void call(Context ctx, String from, String to, CallStyle style) {
		// 添加制表符
		ctx.sb().append('\t');

		// 格式化起始和目标节点名称
		from = formatNode(from, ctx);
		to = formatNode(to, ctx);
		// 根据连接样式生成不同类型的连接线
		ctx.sb().append(switch (style) {
			// 条件连接使用虚线
			case CONDITIONAL -> format("%1$s:::%1$s -.-> %2$s:::%2$s\n", from, to);
			// 默认连接使用实线
			default -> format("%1$s:::%1$s --> %2$s:::%2$s\n", from, to);
		});
	}

	// 重写父类方法，生成带描述的节点连接线
	@Override
	protected void call(Context ctx, String from, String to, String description, CallStyle style) {
		// 添加制表符
		ctx.sb().append('\t');
		// 格式化起始和目标节点名称
		from = formatNode(from, ctx);
		to = formatNode(to, ctx);

		// 根据连接样式生成带描述的连接线
		ctx.sb().append(switch (style) {
			// 条件连接使用虚线并包含描述
			case CONDITIONAL -> format("%1$s:::%1$s -.->|%2$s| %3$s:::%3$s\n", from, description, to);
			// 默认连接使用实线并包含描述
			default -> format("%1$s:::%1s -->|%2$s| %3$s:::%3$s\n", from, description, to);
		});
	}

}
