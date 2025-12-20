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

package com.alibaba.cloud.ai.graph.agent.flow.agent.loop;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.core.convert.converter.Converter;

import java.util.List;
import java.util.Map;

/**
 * JSON array loop strategy that retrieves a JSON array from the current message state,
 * sends each array element as a message to the model, and returns the result.
 * By default, the text of the last message is treated as a JSON array, but users can customize the converter.
 *
 * @author vlsmb
 * @since 2025/11/1
 */
public class ArrayLoopStrategy implements LoopStrategy {

    private final Converter<List<Message>, List<?>> converter;

    public ArrayLoopStrategy(Converter<List<Message>, List<?>> converter) {
        this.converter = converter;
    }

    public ArrayLoopStrategy() {
        this(DEFAULT_MESSAGE_CONVERTER);
    }

    @Override
    // 初始化循环状态，从消息中提取JSON数组
    public Map<String, Object> loopInit(OverAllState state) {
        // 抑制未经检查的类型转换警告
        @SuppressWarnings("unchecked")
        // 从状态中获取消息列表，如果不存在则返回空列表
        List<Message> messages = (List<Message>) state.value(LoopStrategy.MESSAGE_KEY).orElse(List.of());
        // 使用转换器将消息转换为列表
        List<?> list = converter.convert(messages);
        // 如果转换成功，返回初始化的循环状态
        if(list != null) {
            return Map.of(loopCountKey(), 0, loopFlagKey(), true, loopListKey(), list);
        }
        // 如果转换失败，返回错误状态和错误消息
        return Map.of(loopCountKey(), 0, loopFlagKey(), false, loopListKey(), List.of(),
                LoopStrategy.MESSAGE_KEY, new SystemMessage("Invalid json array format"));
    }

    @Override
    // 分发循环消息，每次返回数组中的一个元素作为消息
    public Map<String, Object> loopDispatch(OverAllState state) {
        // 从状态中获取循环列表，如果不存在则返回空列表
        List<?> list = state.value(loopListKey(), List.class).orElse(List.of());
        // 获取当前循环计数，如果不存在则使用最大循环次数
        int index = state.value(loopCountKey(), maxLoopCount());
        // 如果索引小于列表大小，说明还有元素需要处理
        if(index < list.size()) {
            // 创建用户消息，内容为当前索引位置的元素
            UserMessage message = new UserMessage(list.get(index).toString());
            // 返回更新后的循环状态和消息
            return Map.of(loopCountKey(), index + 1, loopFlagKey(), true,
                    LoopStrategy.MESSAGE_KEY, message);
        } else {
            // 如果索引超出列表大小，说明循环结束
            return Map.of(loopFlagKey(), false);
        }
    }

    /**
     * 默认的转换器，将最后一个消息的文本作为json数组
     */
    // 定义默认的消息转换器，用于将消息列表转换为对象列表
    private static final Converter<List<Message>, List<?>> DEFAULT_MESSAGE_CONVERTER =
            messages -> {
                // 声明最后一个消息变量
                String lastMessage;
                // 如果消息列表不为空，获取最后一条消息的文本
                if(!messages.isEmpty()) {
                    lastMessage = messages.get(messages.size() - 1).getText();
                } else {
                    // 如果消息列表为空，设置为null
                    lastMessage = null;
                }
                // 如果最后一条消息为null，返回null
                if(lastMessage == null) {
                    return null;
                }
                // 使用JsonParser将最后一条消息的文本解析为List对象
                return JsonParser.fromJson(lastMessage, List.class);
            };

}
