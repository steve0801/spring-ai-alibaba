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
package com.alibaba.cloud.ai.graph.agent.a2a;

import java.util.HashMap;
import java.util.Map;

import io.a2a.A2A;
import io.a2a.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoteAgentCardProvider implements AgentCardProvider {

	private static final Logger logger = LoggerFactory.getLogger(AgentCard.class);

	private final String url;

	private AgentCard agentCard;

	private RemoteAgentCardProvider(String url) {
		this.url = url;
	}

	public static AgentCardProvider newProvider(String url) {
		return new RemoteAgentCardProvider(url);
	}

	@Override
	// 获取AgentCard包装器的方法
	public AgentCardWrapper getAgentCard() {
		// 如果agentCard为空，则从URL获取AgentCard
		if (null == agentCard) {
			agentCard = getAgentCardFromUrl();
		}
		// 返回AgentCard包装器实例
		return new AgentCardWrapper(agentCard);
	}

	// 从URL获取AgentCard的方法
	private AgentCard getAgentCardFromUrl() {
		// 使用try-catch块处理可能的异常
		try {
			// 声明最终的AgentCard变量
			AgentCard finalAgentCard;
			// 通过A2A工具类获取公共AgentCard
			AgentCard publicAgentCard = A2A.getAgentCard(this.url);
			// 将公共AgentCard赋值给最终AgentCard
			finalAgentCard = publicAgentCard;
			// 检查公共AgentCard是否支持认证扩展卡片
			if (publicAgentCard.supportsAuthenticatedExtendedCard()) {
				// 创建认证头部映射
				Map<String, String> authHeaders = new HashMap<>();
				// 添加授权头部，使用虚拟令牌
				authHeaders.put("Authorization", "Bearer dummy-token-for-extended-card");
				// 通过认证头部获取扩展的AgentCard
				finalAgentCard = A2A.getAgentCard(this.url, "/agent/authenticatedExtendedCard", authHeaders);
			}
			// 如果不支持扩展卡片，则记录日志信息
			else {
				logger.info("Public card does not indicate support for an extended card. Using public card.");
			}
			// 返回最终的AgentCard
			return finalAgentCard;
		}
		// 捕获异常情况
		catch (Exception e) {
			// 记录错误日志
			logger.error("Error building agent card", e);
			// 抛出运行时异常
			throw new RuntimeException(e);
		}
	}

}
