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
package com.alibaba.cloud.ai.graph.async.internal.reactive;

import com.alibaba.cloud.ai.graph.async.AsyncGenerator;

import java.util.concurrent.Flow;

/**
 * A {@code GeneratorPublisher} is a {@link Flow.Publisher} that generates items from an
 * asynchronous generator.
 *
 * @param <T> the type of items to be published
 */
@Deprecated
// GeneratorPublisher类实现Flow.Publisher接口，用于从异步生成器生成项目
public class GeneratorPublisher<T> implements Flow.Publisher<T> {

	// 异步生成器委托对象，用于实际的数据生成
	private final AsyncGenerator<? extends T> delegate;

	/**
	 * Constructs a new <code>GeneratorPublisher</code> with the specified async
	 * generator.
	 * @param delegate The async generator to be used by this publisher.
	 */
	// 使用指定的异步生成器构造新的GeneratorPublisher
	public GeneratorPublisher(AsyncGenerator<? extends T> delegate) {
		// 将传入的异步生成器赋值给委托对象
		this.delegate = delegate;
	}

	/**
	 * Subscribes the provided {@code Flow.Subscriber} to this signal. The subscriber
	 * receives initial subscription, handles asynchronous data flow, and manages any
	 * errors or completion signals.
	 * @param subscriber The subscriber to which the signal will be delivered.
	 */
	// 订阅提供的Flow.Subscriber，处理异步数据流和错误或完成信号
	@Override
	public void subscribe(Flow.Subscriber<? super T> subscriber) {
		// 调用订阅者的onSubscribe方法，传递一个新的Flow.Subscription实现
		subscriber.onSubscribe(new Flow.Subscription() {
			/**
			 * Requests more elements from the upstream Publisher.
			 *
			 * <p>
			 * The Publisher calls this method to indicate that it wants more items. The
			 * parameter {@code n} specifies the number of additional items requested.
			 * @param n the number of items to request, a count greater than zero
			 */
			// 请求来自上游发布者的更多元素
			@Override
			public void request(long n) {
				// 当前实现为空，未实现请求逻辑
			}

			/**
			 * Cancels the operation.
			 * @throws UnsupportedOperationException if the method is not yet implemented.
			 */
			// 取消操作
			@Override
			public void cancel() {
				// 抛出未实现异常，取消操作尚未实现
				throw new UnsupportedOperationException("cancel is not implemented yet!");
			}
		});

		// 使用异步生成器遍历数据，处理完成和错误情况
		delegate.forEachAsync(subscriber::onNext).thenAccept(value -> {
			// 当异步操作完成时，通知订阅者完成
			subscriber.onComplete();
		}).exceptionally(ex -> {
			// 如果发生异常，通知订阅者错误
			subscriber.onError(ex);
			// 返回null值
			return null;
		}).join();
	}

}
