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
package com.alibaba.cloud.ai.graph.agent.hooks.summarization;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "AI_DASHSCOPE_API_KEY", matches = ".+")
// 总结功能测试类，需要DashScope API密钥环境变量才能运行
public class SummarizationTest {

    // 声明ChatModel实例用于测试
    private ChatModel chatModel;

    @BeforeEach
    // 在每个测试方法执行前运行的初始化方法
    void setUp() {
        // 使用环境变量中的API密钥创建DashScopeApi实例
        DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey(System.getenv("AI_DASHSCOPE_API_KEY")).build();
        // 创建DashScope ChatModel实例
        this.chatModel = DashScopeChatModel.builder().dashScopeApi(dashScopeApi).build();
    }

    @Test
    // 测试总结功能的效果
    public void testSummarizationEffect() throws Exception {
        // 创建长对话用于测试总结功能
        List<Message> longConversation = createLongConversation(50);

        // 创建SummarizationHook实例，配置较低的token阈值以便触发总结
        SummarizationHook hook = SummarizationHook.builder()
                .model(chatModel)
                .maxTokensBeforeSummary(200) // 设置较低的阈值以便触发总结
                .messagesToKeep(10) // 保留最近10条消息
                .build();

        // 创建ReactAgent实例用于测试
        ReactAgent agent = createAgent(hook, "test-summarization-agent", chatModel);

        System.out.println("=== 测试带有总结功能的对话 ===");
        System.out.println("初始消息数量: " + longConversation.size());

        // 调用 agent，应该触发总结
        Optional<OverAllState> result = agent.invoke(longConversation);

        // 验证结果
        assertTrue(result.isPresent(), "结果应该存在");
        Object messagesObj = result.get().value("messages").get();
        assertNotNull(messagesObj, "消息应该存在于结果中");

        // 处理返回的消息列表
        if (messagesObj instanceof List) {
            List<Message> messages = (List<Message>) messagesObj;
            System.out.println("总结后消息数量: " + messages.size());

            // 检查是否包含总结消息
            if (!messages.isEmpty()) {
                Message firstMessage = messages.get(0);
                if (firstMessage.getText().contains("summary of the conversation")) {
                    System.out.println("总结功能");
                    System.out.println("总结消息预览: " + firstMessage.getText().substring(0,
                        Math.min(100, firstMessage.getText().length())) + "...");
                }
            }
        }
    }

    @Test
    // 测试不带总结功能的正常对话流程
    public void testWithoutSummarization() throws Exception {
        // 创建短对话用于测试
        List<Message> shortConversation = createShortConversation();

        // 创建不带总结钩子的ReactAgent实例
        ReactAgent agent = ReactAgent.builder()
                .name("test-no-summarization-agent")
                .model(chatModel)
                .saver(new MemorySaver())
                .build();

        System.out.println("\n=== 测试不带总结功能的对话 ===");
        System.out.println("初始消息数量: " + shortConversation.size());

        // 调用 agent
        Optional<OverAllState> result = agent.invoke(shortConversation);

        // 验证结果
        assertTrue(result.isPresent(), "结果应该存在");
        Object messagesObj = result.get().value("messages").get();
        assertNotNull(messagesObj, "消息应该存在于结果中");

        // 处理返回的消息列表
        if (messagesObj instanceof List) {
            List<Message> messages = (List<Message>) messagesObj;
            System.out.println("处理后消息数量: " + messages.size());
            System.out.println("✓ 正常对话流程，未触发总结");
        }
    }

    // 创建长对话的私有方法
    private List<Message> createLongConversation(int messageCount) {
        List<Message> messages = new ArrayList<>();
        // 添加初始系统消息
        messages.add(new UserMessage("我们开始一个长对话来测试总结功能。"));
        messages.add(new AssistantMessage("好的，我明白了。我们来进行一个长对话测试。"));

        // 添加大量交替的用户和助手消息
        for (int i = 0; i < messageCount; i++) {
            if (i % 2 == 0) {
                messages.add(new UserMessage("用户消息 " + i + "：这是对话中的一条用户消息，包含一些内容用于增加token数量，我们需要足够多的文字来确保能够触发总结功能。"));
            } else {
                messages.add(new AssistantMessage("助手消息 " + i + "：这是对话中的一条助手回复，也包含一些内容用于增加token数量，我们需要足够多的文字来确保能够触发总结功能。"));
            }
        }

        // 添加最后几条消息
        messages.add(new UserMessage("这是倒数第二条消息。"));
        messages.add(new AssistantMessage("我收到了你的消息。"));
        messages.add(new UserMessage("这是最后一条消息，请总结以上对话。"));
        return messages;
    }

    // 创建短对话的私有方法
    private List<Message> createShortConversation() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("你好"));
        messages.add(new AssistantMessage("你好！有什么我可以帮助你的吗？"));
        messages.add(new UserMessage("我想了解总结功能是如何工作的"));
        messages.add(new AssistantMessage("总结功能会在对话变得很长时自动总结早期内容，以避免超出token限制。"));
        messages.add(new UserMessage("谢谢你的解释"));
        return messages;
    }

    // 创建ReactAgent实例的辅助方法
    public ReactAgent createAgent(SummarizationHook hook, String name, ChatModel model) throws GraphStateException {
        // 使用ReactAgent构建器创建代理实例
        return ReactAgent.builder()
                .name(name)
                .model(model)
                .hooks(List.of(hook))
                .saver(new MemorySaver())
                .build();
    }


}

    
