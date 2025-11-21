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
package com.alibaba.cloud.ai.graph.agent.hooks.pii;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.pii.PIIDetectionHook;
import com.alibaba.cloud.ai.graph.agent.hook.pii.PIIType;
import com.alibaba.cloud.ai.graph.agent.hook.pii.PIIDetectors;
import com.alibaba.cloud.ai.graph.agent.hook.pii.RedactionStrategy;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;

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
// PII检测钩子测试类，需要DashScope API密钥环境变量才能运行
public class PIIDectionHookTest {

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
    // 测试使用REDACT策略的PII检测功能
    public void testPIIDetectionWithRedactStrategy() throws Exception {
        // 创建PII检测钩子，配置为检测邮箱地址并使用REDACT策略
        PIIDetectionHook hook = PIIDetectionHook.builder()
                .piiType(PIIType.EMAIL)
                .strategy(RedactionStrategy.REDACT)
                .applyToInput(true)
                .applyToOutput(true)
                .build();

        // 创建ReactAgent实例用于测试
        ReactAgent agent = createAgent(hook, "test-pii-redact-agent", chatModel);

        System.out.println("=== 测试PII检测（REDACT策略）===");

        // 创建测试消息列表，包含邮箱地址
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("我的邮箱地址是 test@example.com，请记住它。"));

        // 调用代理执行PII检测
        Optional<OverAllState> result = agent.invoke(messages);

        // 验证结果存在
        assertTrue(result.isPresent(), "结果应该存在");
        Object messagesObj = result.get().value("messages").get();
        assertNotNull(messagesObj, "消息应该存在于结果中");

        // 处理返回的消息列表
        if (messagesObj instanceof List) {
            List<Message> resultMessages = (List<Message>) messagesObj;
            System.out.println("返回消息数量: " + resultMessages.size());

            // 遍历消息列表，检查邮箱地址是否被正确检测和替换
            for (Message message : resultMessages) {
                if (message instanceof UserMessage) {
                    String content = message.getText();
                    if (content.contains("[REDACTED_EMAIL]")) {
                        System.out.println("✓ 成功检测并替换用户消息中的邮箱地址");
                    }
                } else if (message instanceof AssistantMessage) {
                    String content = message.getText();
                    System.out.println("AI回复: " + content);
                }
            }
        }
    }

    @Test
    // 测试使用MASK策略的PII检测功能
    public void testPIIDetectionWithMaskStrategy() throws Exception {
        // 创建PII检测钩子，配置为检测信用卡号并使用MASK策略
        PIIDetectionHook hook = PIIDetectionHook.builder()
                .piiType(PIIType.CREDIT_CARD)
                .strategy(RedactionStrategy.MASK)
                .applyToInput(true)
                .applyToOutput(true)
                .build();

        // 创建ReactAgent实例用于测试
        ReactAgent agent = createAgent(hook, "test-pii-mask-agent", chatModel);

        System.out.println("\n=== 测试PII检测（MASK策略）===");

        // 创建测试消息列表，包含信用卡号
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("我的信用卡号是 1234 5678 9012 3456，请帮我检查一下。"));

        // 调用代理执行PII检测
        Optional<OverAllState> result = agent.invoke(messages);

        // 验证结果存在
        assertTrue(result.isPresent(), "结果应该存在");
        Object messagesObj = result.get().value("messages").get();
        assertNotNull(messagesObj, "消息应该存在于结果中");

        // 处理返回的消息列表
        if (messagesObj instanceof List) {
            List<Message> resultMessages = (List<Message>) messagesObj;
            System.out.println("返回消息数量: " + resultMessages.size());
            // 遍历消息列表，检查信用卡号是否被正确检测和掩码
            for (Message message : resultMessages) {
                if (message instanceof UserMessage) {
                    String content = message.getText();
                    if (content.contains("****") && content.contains("3456")) {
                        System.out.println("成功检测并部分掩码用户消息中的信用卡号");
                    }
                    System.out.println("处理后的用户消息: " + content);
                }
            }
        }
    }

    @Test
    // 测试使用BLOCK策略的PII检测功能
    public void testPIIDetectionWithBlockStrategy() throws Exception {
        // 创建PII检测钩子，配置为检测IP地址并使用BLOCK策略
        PIIDetectionHook hook = PIIDetectionHook.builder()
                .piiType(PIIType.IP)
                .strategy(RedactionStrategy.BLOCK)
                .applyToInput(true)
                .build();

        // 创建ReactAgent实例用于测试
        ReactAgent agent = createAgent(hook, "test-pii-block-agent", chatModel);

        System.out.println("\n=== 测试PII检测（BLOCK策略）===");

        // 创建测试消息列表，包含IP地址
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("我的服务器IP地址是 192.168.1.100，请不要泄露。"));

        // 尝试调用代理执行PII检测，应该抛出异常
        try {
            Optional<OverAllState> result = agent.invoke(messages);
            System.out.println("未抛出异常，可能IP未被正确检测");
        } catch (Exception e) {
            // 检查是否抛出了PII检测异常
            if (e.getCause() instanceof com.alibaba.cloud.ai.graph.agent.hook.pii.PIIDetectionException) {
                System.out.println("✓ 成功检测到IP地址并阻止处理: " + e.getCause().getMessage());
            } else {
                System.out.println("抛出其他异常: " + e.getMessage());
            }
        }
    }

    @Test
    // 测试不带PII检测的正常对话流程
    public void testWithoutPIIDetection() throws Exception {
        // 创建不带PII检测钩子的ReactAgent实例
        ReactAgent agent = ReactAgent.builder()
                .name("test-no-pii-agent")
                .model(chatModel)
                .saver(new MemorySaver())
                .build();

        System.out.println("\n=== 测试不带PII检测的对话 ===");

        // 创建普通测试消息列表
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("你好，有什么可以帮助你的吗？"));

        // 调用代理执行正常对话
        Optional<OverAllState> result = agent.invoke(messages);

        // 验证结果存在
        assertTrue(result.isPresent(), "结果应该存在");
        Object messagesObj = result.get().value("messages").get();
        assertNotNull(messagesObj, "消息应该存在于结果中");

        // 处理返回的消息列表
        if (messagesObj instanceof List) {
            List<Message> resultMessages = (List<Message>) messagesObj;
            System.out.println("返回消息数量: " + resultMessages.size());
            System.out.println("✓ 正常对话流程，未触发PII检测");
        }
    }

    @Test
    // 测试自定义PII检测器功能
    public void testCustomPIIDetector() throws Exception {
        // 创建PII检测钩子，配置为使用自定义检测器检测手机号码
        PIIDetectionHook hook = PIIDetectionHook.builder()
                .piiType(PIIType.CUSTOM)
                .strategy(RedactionStrategy.REDACT)
                .detector(PIIDetectors.regexDetector("PHONE", "\\b1[3-9]\\d{9}\\b"))
                .applyToInput(true)
                .build();

        // 创建ReactAgent实例用于测试
        ReactAgent agent = createAgent(hook, "test-custom-pii-agent", chatModel);

        System.out.println("\n=== 测试自定义PII检测器 ===");

        // 创建测试消息列表，包含手机号码
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("我的手机号码是 13812345678，请保存。"));

        // 调用代理执行自定义PII检测
        Optional<OverAllState> result = agent.invoke(messages);

        // 验证结果存在
        assertTrue(result.isPresent(), "结果应该存在");
        Object messagesObj = result.get().value("messages").get();
        assertNotNull(messagesObj, "消息应该存在于结果中");

        // 处理返回的消息列表
        if (messagesObj instanceof List) {
            List<Message> resultMessages = (List<Message>) messagesObj;
            System.out.println("返回消息数量: " + resultMessages.size());

            // 遍历消息列表，检查手机号码是否被正确检测和替换
            for (Message message : resultMessages) {
                if (message instanceof UserMessage) {
                    String content = message.getText();
                    if (content.contains("[REDACTED_PHONE]")) {
                        System.out.println("成功检测并替换用户消息中的手机号码");
                    }
                    System.out.println("处理后的用户消息: " + content);
                }
            }
        }
    }

    // 创建ReactAgent实例的辅助方法
    public ReactAgent createAgent(PIIDetectionHook hook, String name, ChatModel model) throws Exception {
        // 使用ReactAgent构建器创建代理实例
        return ReactAgent.builder()
                .name(name)
                .model(model)
                .hooks(List.of(hook))
                .saver(new MemorySaver())
                .build();
    }

    
}
