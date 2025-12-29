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
import com.alibaba.cloud.ai.graph.async.AsyncGeneratorQueue;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/**
 * Represents a subscriber for generating asynchronous data streams.
 *
 * <p>
 * This class implements the {@link Flow.Subscriber} and {@link AsyncGenerator} interfaces
 * to handle data flow and produce asynchronous data. It is designed to subscribe to a
 * publisher, process incoming items, and manage error and completion signals.
 * </p>
 *
 * @param <T> The type of elements produced by this generator.
 */
@Deprecated
// GeneratorSubscriber类实现了Flow.Subscriber和AsyncGenerator接口，用于生成异步数据流
public class GeneratorSubscriber<T> implements Flow.Subscriber<T>, AsyncGenerator<T> {

	// 委托对象，用于处理异步生成器队列操作
	private final AsyncGeneratorQueue.Generator<T> delegate;

	// 映射结果的供应器，用于设置生成器的结果
	private final Supplier<Object> mapResult;

	// 获取映射结果供应器的可选包装方法
	public Optional<Supplier<Object>> mapResult() {
		// 返回mapResult的Optional包装
		return Optional.ofNullable(mapResult);
	}

	/**
	 * Constructs a new instance of {@code GeneratorSubscriber}.
	 * @param <P> the type of the publisher, which must extend {@link Flow.Publisher}
	 * @param mapResult function that will set generator's result
	 * @param publisher the source publisher that will push data to this subscriber
	 * @param queue the blocking queue used for storing asynchronous generator data
	 */
	// 使用发布者、映射结果供应器和队列构造新的GeneratorSubscriber实例
	public <P extends Flow.Publisher<T>> GeneratorSubscriber(P publisher, Supplier<Object> mapResult,
			BlockingQueue<Data<T>> queue) {
		// 创建AsyncGeneratorQueue.Generator实例作为委托对象
		this.delegate = new AsyncGeneratorQueue.Generator<>(queue);
		// 设置映射结果供应器
		this.mapResult = mapResult;
		// 订阅发布者，将当前实例作为订阅者
		publisher.subscribe(this);
	}

	/**
	 * Constructs a new instance of {@code GeneratorSubscriber}.
	 * @param <P> the type of the publisher, which must extend {@link Flow.Publisher}
	 * @param publisher the source publisher that will push data to this subscriber
	 * @param queue the blocking queue used for storing asynchronous generator data
	 */
	// 使用发布者和队列构造GeneratorSubscriber实例的重载构造函数（映射结果供应器为null）
	public <P extends Flow.Publisher<T>> GeneratorSubscriber(P publisher, BlockingQueue<Data<T>> queue) {
		// 调用主构造函数，映射结果供应器设为null
		this(publisher, null, queue);
	}

	/**
	 * Handles the subscription event from a Flux.
	 * <p>
	 * This method is called when a subscription to the source {@link Flow} has been
	 * established. The provided {@code Flow.Subscription} can be used to manage and
	 * control the flow of data emissions.
	 * @param subscription The subscription object representing this resource owner
	 * lifecycle. Used to signal that resources being subscribed to should not be released
	 * until this subscription is disposed.
	 */
	// 处理由Flux发起的订阅事件，处理Flow.Subscription
	@Override
	public void onSubscribe(Flow.Subscription subscription) {
		// 请求最大数量的数据项（Long.MAX_VALUE表示无限制）
		subscription.request(Long.MAX_VALUE);
	}

	/**
	 * Passes the received item to the delegated queue as an {@link Data} object.
	 * @param item The item to be processed and queued.
	 */
	// 将接收到的项目作为Data对象传递到委托队列
	@Override
	public void onNext(T item) {
		// 将项目包装为Data对象并添加到委托队列
		delegate.queue().add(Data.of(item));
	}

	/**
	 * Handles an error by queuing it in the delegate's queue with an errored data.
	 * @param error The Throwable that represents the error to be handled.
	 */
	// 处理错误，将错误数据添加到委托队列
	@Override
	public void onError(Throwable error) {
		// 将错误包装为错误数据并添加到委托队列
		delegate.queue().add(Data.error(error));
	}

	/**
	 * This method is called when the asynchronous operation is completed successfully. It
	 * notifies the delegate that no more data will be provided by adding a done marker to
	 * the queue.
	 */
	// 异步操作成功完成时调用，向队列添加完成标记通知委托对象不再提供数据
	@Override
	public void onComplete() {
		// 向队列添加完成数据，包含映射结果（如果存在）
		delegate.queue().add(Data.done(mapResult().map(Supplier::get).orElse(null)));
	}

	/**
	 * Returns the next {@code Data<T>} object from this iteration.
	 * @return the next element in the iteration, or null if there is no such element
	 */
	// 从迭代中返回下一个Data<T>对象
	@Override
	public Data<T> next() {
		// 返回委托对象的下一个数据
		return delegate.next();
	}

}
