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

import java.util.Map;

/**
 * Fixed count loop strategy
 *
 * @author vlsmb
 * @since 2025/11/1
 */
public class CountLoopStrategy implements LoopStrategy {

    // 最大循环次数
    private final int maxCount;

    // 构造函数，设置最大循环次数，不超过LoopStrategy定义的最大值
    public CountLoopStrategy(int maxCount) {
        this.maxCount = Math.min(maxCount, maxLoopCount());
    }

    // 循环初始化方法，设置初始计数为0，根据maxCount是否大于0设置循环标志
    @Override
    public Map<String, Object> loopInit(OverAllState state) {
        return Map.of(loopCountKey(), 0, loopFlagKey(), maxCount > 0);
    }

    // 循环分发方法，控制循环计数和循环标志
    @Override
    public Map<String, Object> loopDispatch(OverAllState state) {
        // 获取当前循环计数，如果状态中没有则使用maxCount作为默认值
        int count = state.value(loopCountKey(), maxCount);
        // 如果当前计数小于最大计数，继续循环并增加计数
        if (count < maxCount) {
            return Map.of(loopCountKey(), count + 1, loopFlagKey(), true);
        } else {
            // 否则停止循环
            return Map.of(loopFlagKey(), false);
        }
    }
}
