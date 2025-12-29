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
package com.alibaba.cloud.ai.graph.async;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static java.util.concurrent.ForkJoinPool.commonPool;

/**
 * Represents a queue-based asynchronous generator.
 */
// 队列式异步生成器类，已标记为废弃
@Deprecated
public class AsyncGeneratorQueue {

	/**
	 * Inner class to generate asynchronous elements from the queue.
	 *
	 * @param <E> the type of elements in the queue
	 */
	// 从队列生成异步元素的内部类
	public static class Generator<E> implements AsyncGenerator<E> {

		// 标记是否结束的数据元素
		Data<E> isEnd = null;

		// 阻塞队列，用于存储数据元素
		final BlockingQueue<Data<E>> queue;

		/**
		 * Constructs a Generator with the specified queue.
		 * @param queue the blocking queue to generate elements from
		 */
		// 使用指定的队列构造Generator
		public Generator(BlockingQueue<Data<E>> queue) {
			// 设置队列
			this.queue = queue;
		}

		// 获取队列的方法
		public BlockingQueue<Data<E>> queue() {
			// 返回队列
			return queue;
		}

		/**
		 * Retrieves the next element from the queue asynchronously.
		 * @return the next element from the queue
		 */
		// 异步从队列获取下一个元素
		@Override
		public Data<E> next() {
			// 循环直到找到结束标记
			while (isEnd == null) {
				// 从队列中轮询数据
				Data<E> value = queue.poll();
				// 检查值是否不为null
				if (value != null) {
					// 检查数据是否已完成
					if (value.isDone()) {
						// 设置结束标记
						isEnd = value;
					}
					// 返回当前值
					return value;
				}
			}
			// 返回结束标记
			return isEnd;
		}

	}

	/**
	 * Creates an AsyncGenerator from the provided blocking queue and consumer.
	 * @param <E> the type of elements in the queue
	 * @param <Q> the type of blocking queue
	 * @param queue the blocking queue to generate elements from
	 * @param consumer the consumer for processing elements from the queue
	 * @return an AsyncGenerator instance
	 */
	// 从提供的阻塞队列和消费者创建AsyncGenerator
	public static <E, Q extends BlockingQueue<AsyncGenerator.Data<E>>> AsyncGenerator<E> of(Q queue,
			Consumer<Q> consumer) {
		// 调用带执行器的重载方法，使用公共线程池
		return of(queue, consumer, commonPool());
	}

	/**
	 * Creates an AsyncGenerator from the provided queue, executor, and consumer.
	 * @param <E> the type of elements in the queue
	 * @param <Q> the type of blocking queue
	 * @param queue the blocking queue to generate elements from
	 * @param consumer the consumer for processing elements from the queue
	 * @param executor the executor for asynchronous processing
	 * @return an AsyncGenerator instance
	 */
	// 从提供的队列、执行器和消费者创建AsyncGenerator
	public static <E, Q extends BlockingQueue<AsyncGenerator.Data<E>>> AsyncGenerator<E> of(Q queue,
			Consumer<Q> consumer, Executor executor) {
		// 检查队列是否为null
		Objects.requireNonNull(queue);
		// 检查执行器是否为null
		Objects.requireNonNull(executor);
		// 检查消费者是否为null
		Objects.requireNonNull(consumer);

		// 在执行器中执行以下操作
		executor.execute(() -> {
			// 尝试执行消费者操作
			try {
				// 执行消费者，传入队列
				consumer.accept(queue);
			}
			// 捕获所有异常
			catch (Throwable ex) {
				// 创建一个错误的CompletableFuture
				CompletableFuture<E> error = new CompletableFuture<>();
				// 将future设置为异常完成状态
				error.completeExceptionally(ex);
				// 将错误数据添加到队列
				queue.add(AsyncGenerator.Data.of(error));
			}
			// 无论是否发生异常，都会执行以下操作
			finally {
				// 向队列添加完成数据
				queue.add(AsyncGenerator.Data.done());
			}

		});

		// 返回使用队列构造的Generator实例
		return new Generator<>(queue);
	}

}
