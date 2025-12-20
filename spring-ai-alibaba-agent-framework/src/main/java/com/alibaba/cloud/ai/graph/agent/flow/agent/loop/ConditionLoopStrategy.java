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

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Conditional loop strategy that retries until the Predicate is satisfied or the maximum count is reached.
 *
 * @author vlsmb
 * @since 2025/11/1
 */
public class ConditionLoopStrategy implements LoopStrategy {

    // 条件谓词，用于判断是否满足循环终止条件
    private final Predicate<List<Message>> messagePredicate;

    // 最大循环次数，从LoopStrategy继承的最大循环次数
    private final int maxCount = maxLoopCount();

    // 构造函数，接收一个消息谓词用于判断循环条件
    public ConditionLoopStrategy(Predicate<List<Message>> messagePredicate) {
        this.messagePredicate = messagePredicate;
    }

    // 循环初始化方法，设置初始循环计数和循环标志
    @Override
    public Map<String, Object> loopInit(OverAllState state) {
        return Map.of(loopCountKey(), 0, loopFlagKey(), true);
    }

    // 循环分发方法，根据条件谓词判断是否继续循环
    @Override
    public Map<String, Object> loopDispatch(OverAllState state) {
        // 抑制未经检查的类型转换警告
        @SuppressWarnings("unchecked")
        // 从状态中获取消息列表，如果不存在则返回空列表
        List<Message> messages = (List<Message>) state.value(LoopStrategy.MESSAGE_KEY).orElse(List.of());
        // 测试消息谓词，如果满足条件则停止循环
        if(messagePredicate.test(messages)) {
            return Map.of(loopFlagKey(), false);
        } else {
            // 获取当前循环计数，如果不存在则使用最大循环次数
            int count = state.value(loopCountKey(), maxCount);
            // 如果当前循环次数小于最大循环次数，则继续循环
            if(count < maxCount) {
                return Map.of(loopCountKey(), count + 1, loopFlagKey(), true);
            } else {
                // 如果达到最大循环次数，则返回错误消息并停止循环
                return Map.of(LoopStrategy.MESSAGE_KEY, new SystemMessage("Max loop count reached"), loopFlagKey(), false);
            }
        }
    }
}
