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

package com.alibaba.cloud.ai.graph.agent.flow;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LoopAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.loop.LoopMode;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;

import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EnabledIfEnvironmentVariable(named = "AI_DASHSCOPE_API_KEY", matches = ".+")
public class LoopAgentTest {

	private static final Logger logger = LoggerFactory.getLogger(LoopAgentTest.class);

	// 声明ChatModel实例，用于与大语言模型交互
	private ChatModel chatModel;

	// 声明SequentialAgent实例，用于处理博客文章的生成和评审
	private SequentialAgent blogAgent;

    // 声明SequentialAgent实例，用于处理SQL生成和评分
    private SequentialAgent sqlAgent;

	@BeforeEach
	// 在每个测试方法执行前运行的初始化方法
	void setUp() throws GraphStateException {
		// 使用环境变量中的API密钥创建DashScopeApi实例
		DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey(System.getenv("AI_DASHSCOPE_API_KEY")).build();

		// 创建DashScope ChatModel实例
		this.chatModel = DashScopeChatModel.builder().dashScopeApi(dashScopeApi).build();

        // 创建writerAgent，用于文章写作
        ReactAgent writerAgent = ReactAgent.builder()
                .name("writer_agent")
                .model(chatModel)
                .description("可以写文章。")
                .instruction("你是一个知名的作家，擅长写作和创作。请根据用户的提问进行回答。")
                .outputKey("article")
                .build();

        // 创建reviewerAgent，用于文章评审和修改
        ReactAgent reviewerAgent = ReactAgent.builder()
                .name("reviewer_agent")
                .model(chatModel)
                .description("可以对文章进行评论和修改。")
                .instruction("你是一个知名的评论家，擅长对文章进行评论和修改。对于散文类文章，请确保文章中必须包含对于西湖风景的描述。最终只返回修改后的文章，不要包含任何评论信息。")
                .outputKey("reviewed_article")
                .build();

        // 创建blogAgent，按顺序执行writerAgent和reviewerAgent
        this.blogAgent = SequentialAgent.builder()
                .name("blog_agent")
                .description("可以根据用户给定的主题写一篇文章，然后将文章交给评论员进行评论。")
                .subAgents(List.of(writerAgent, reviewerAgent))
                .build();

        // 创建sqlGenerateAgent，用于根据自然语言生成SQL代码
        ReactAgent sqlGenerateAgent = ReactAgent.builder()
                .name("sqlGenerateAgent")
                .model(chatModel)
                .description("可以根据用户的自然语言生成MySQL的SQL代码。")
                .instruction("你是一个熟悉MySQL数据库的小助手，请你根据用户的自然语言，输出对应的SQL。")
                .outputSchema("""
                        {
                           "query": 用户的请求,
                           "output": 生成SQL结果
                        }
                        """)
                .outputKey("sql")
                .build();

        // 创建sqlRatingAgent，用于对SQL语句进行评分
        ReactAgent sqlRatingAgent = ReactAgent.builder()
                .name("sqlRatingAgent")
                .model(chatModel)
                .description("可以根据输入的自然语言和SQL语句的匹配度进行评分。")
                .instruction("你是一个熟悉MySQL数据库的小助手，请你根据用户输入的自然语言和对应的SQL语句，输出一个评分。评分为一个浮点数，在0到1之间。越趋近于1说明SQL越匹配自然语言。")
                .outputSchema("你的输出有且仅有一个浮点数，且在0到1之间，**不要输出任何额外的字符**")
                .outputKey("score")
                .build();

        // 创建sqlAgent，按顺序执行sqlGenerateAgent和sqlRatingAgent
        this.sqlAgent = SequentialAgent.builder()
                .name("sql_agent")
                .description("可以根据用户的输入，生成SQL语句，并对其评分。")
                .subAgents(List.of(sqlGenerateAgent, sqlRatingAgent))
                .build();
	}

    @Test
    // 测试基于计数的循环模式
    void testCountMode() throws Exception {
        // 创建LoopAgent，使用计数循环策略，执行2次
        LoopAgent loopAgent = LoopAgent.builder()
                .name("loop_agent")
                .description("循环执行一个任务，直到满足条件。")
                .subAgent(this.blogAgent)
                .loopStrategy(LoopMode.count(2))
                .build();
        // 执行循环代理，生成Python Socket编程的demo并优化代码
        OverAllState state = loopAgent.invoke("帮我写一个Python Socket编程的demo，并优化代码").orElseThrow();
        logger.info("Result: {}", state.data());
        // 验证结果中包含消息列表
        Optional<Object> optional = state.value("messages");
        assert optional.isPresent();
        Object object = optional.get();
        assert object instanceof List;
        List<?> messages = (List<?>) object;
        assert !messages.isEmpty();
    }

    @Test
    // 测试基于条件的循环模式
    void testConditionMode() throws Exception {
        // 创建LoopAgent，使用条件循环策略
        LoopAgent loopAgent = LoopAgent.builder()
                .name("loop_agent")
                .description("循环执行一个任务，直到满足条件。")
                .subAgent(this.sqlAgent)
                .loopStrategy(LoopMode.condition(messages -> {
                    logger.info("Messages: {}", messages);
                    // 如果消息列表为空，返回false
                    if(messages.isEmpty()) {
                        return false;
                    }
                    // 获取最后一条消息的文本内容
                    String text = messages.get(messages.size() - 1).getText();
                    try {
                        // 尝试解析文本为浮点数评分
                        double score = Double.parseDouble(text);
                        // 如果评分大于0.5，返回true结束循环
                        return score > 0.5;
                    } catch (Exception e) {
                        // 解析失败返回false继续循环
                        return false;
                    }
                }))
                .build();
        // 执行循环代理，生成查找名字以s开头的用户SQL
        OverAllState state = loopAgent.invoke("现在有一个用户表，名为user，有列（id, name, password），现在我想要找所有名字以s开头的用户，如何写对应SQL？").orElseThrow();
        logger.info("Result: {}", state.data());
        // 验证结果中包含消息列表
        Optional<Object> optional = state.value("messages");
        assert optional.isPresent();
        Object object = optional.get();
        assert object instanceof List;
        List<?> messages = (List<?>) object;
        assert !messages.isEmpty();
    }

    @Test
    // 测试基于数组的循环模式
    void testArrayMode() throws Exception {
        // 创建LoopAgent，使用数组循环策略
        LoopAgent loopAgent = LoopAgent.builder()
                .name("loop_agent")
                .description("循环执行任务。")
                .subAgent(this.sqlAgent)
                .loopStrategy(LoopMode.array())
                .build();
        // 执行循环代理，处理多个SQL查询请求
        OverAllState state = loopAgent.invoke("""
                ["现在有一个用户表，名为user，有列（id, name, password），现在我想要找所有名字以s开头的用户，如何写对应SQL？",
                "现在有一个用户表，名为user，有列（id, name, password），现在我想要找所有名字以t开头的用户，如何写对应SQL？",
                "现在有一个用户表，名为user，现在我想要找所有用户，如何写对应SQL？"]
                """).orElseThrow();
        logger.info("Result: {}", state.data());
        // 验证结果中包含消息列表
        // 从状态中获取"messages"键对应的值
        Optional<Object> optional = state.value("messages");
        // 断言optional存在值（不为空）
        assert optional.isPresent();
        // 获取optional中的对象
        Object object = optional.get();
        // 断言对象是List类型
        assert object instanceof List;
        // 将对象转换为List类型
        List<?> messages = (List<?>) object;
        // 断言消息列表不为空
        assert !messages.isEmpty();
    }

}
