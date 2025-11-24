package com.alibaba.cloud.ai.examples.deepresearch.sample2;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;

public class AgentExample2 {

    public static void main(String[] args) throws Exception {
// 创建模型实例
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey("your-api-key-here")
                .build();
        ChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .build();

        // 创建 Agent
        ReactAgent agent = ReactAgent.builder()
                .name("weather_agent")
                .model(chatModel)
                .instruction("You are a helpful weather forecast assistant.")
                .build();

        // 运行 Agent
        AssistantMessage response = agent.call("what is the weather in Hangzhou?");
        System.out.println(response.getText());
    }
}
