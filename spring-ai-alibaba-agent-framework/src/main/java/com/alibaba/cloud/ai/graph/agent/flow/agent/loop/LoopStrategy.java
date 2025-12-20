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

import java.util.List;
import java.util.Map;

/**
 * <p>Loop strategy for LoopAgent, used to control the behavior of LoopAgent.</p>
 * <p>This part is equivalent to defining the loopInitNode and loopDispatchNode for the StateGraph corresponding to LoopAgent.</p>
 * <p>Built-in strategies provided by LoopMode can be used directly when in use. If custom loop logic is required, this interface can be implemented.</p>
 *
 * @author vlsmb
 * @since 2025/11/1
 */
public interface LoopStrategy {

    // 可迭代元素的最大数量常量
    int ITERABLE_ELEMENT_COUNT = 1000;

    // 循环标志键的前缀
    String LOOP_FLAG_PREFIX = "__loop_flag__";

    // 循环列表键的前缀
    String LOOP_LIST_PREFIX = "__loop_list__";

    // 循环计数键的前缀
    String LOOP_COUNT_PREFIX = "__loop_count__";

    // 初始化节点名称的前缀
    String INIT_NODE_NAME = "_loop_init__";

    // 分发节点名称的前缀
    String DISPATCH_NODE_NAME = "_loop_dispatch__";

    // 消息键的名称
    String MESSAGE_KEY = "messages";

    // 循环初始化方法，在循环开始前调用
    Map<String, Object> loopInit(OverAllState state);

    // 循环分发方法，在每次循环迭代时调用
    Map<String, Object> loopDispatch(OverAllState state);

    // 生成唯一键的方法，基于对象的系统标识哈希码
    default String uniqueKey() {
        return String.valueOf(System.identityHashCode(this));
    }

    // 获取临时键列表的方法
    default List<String> tempKeys() {
        return List.of(
                loopFlagKey(),
                loopListKey(),
                loopCountKey()
        );
    }

    // 获取最大循环次数的方法
    default int maxLoopCount() {
        return ITERABLE_ELEMENT_COUNT;
    }

    // 生成循环标志键的方法
    default String loopFlagKey() {
        return LOOP_FLAG_PREFIX + uniqueKey();
    }

    // 生成循环列表键的方法
    default String loopListKey() {
        return LOOP_LIST_PREFIX + uniqueKey();
    }

    // 生成循环计数键的方法
    default String loopCountKey() {
        return LOOP_COUNT_PREFIX + uniqueKey();
    }

    // 生成循环初始化节点名称的方法
    default String loopInitNodeName() {
        return INIT_NODE_NAME + uniqueKey();
    }

    // 生成循环分发节点名称的方法
    default String loopDispatchNodeName() {
        return DISPATCH_NODE_NAME + uniqueKey();
    }
}
