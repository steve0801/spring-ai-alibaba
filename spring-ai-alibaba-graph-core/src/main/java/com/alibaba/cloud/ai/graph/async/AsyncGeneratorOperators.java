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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.concurrent.CompletableFuture.completedFuture;

@Deprecated
// 异步生成器操作接口，已标记为废弃
public interface AsyncGeneratorOperators<E> {

	// 获取下一个数据元素的抽象方法
	AsyncGenerator.Data<E> next();

	// 默认执行器方法，返回直接运行的执行器
	default Executor executor() {
		// 返回直接运行的执行器实现
		return Runnable::run;
	}

	/**
	 * Maps the elements of this generator to a new asynchronous generator.
	 * @param mapFunction the function to map elements to a new asynchronous counterpart
	 * @param <U> the type of elements in the new generator
	 * @return a generator with mapped elements
	 */
	// 将此生成器的元素映射到新的异步生成器
	default <U> AsyncGenerator<U> map(Function<E, U> mapFunction) {
		// 返回一个生成器实例
		return () -> {
			// 获取下一个数据
			final AsyncGenerator.Data<E> next = next();
			// 检查数据是否已完成
			if (next.isDone()) {
				// 如果已完成，返回带结果值的完成数据
				return AsyncGenerator.Data.done(next.resultValue);
			}
			// 否则返回应用映射函数后的数据
			return AsyncGenerator.Data.of(next.data.thenApplyAsync(mapFunction, executor()));
		};
	}

	/**
	 * Maps the elements of this generator to a new asynchronous generator, and flattens
	 * the resulting nested generators.
	 * @param mapFunction the function to map elements to a new asynchronous counterpart
	 * @param <U> the type of elements in the new generator
	 * @return a generator with mapped and flattened elements
	 */
	// 将此生成器的元素映射到新的异步生成器，并展平生成的嵌套生成器
	default <U> AsyncGenerator<U> flatMap(Function<E, CompletableFuture<U>> mapFunction) {
		// 返回一个生成器实例
		return () -> {
			// 获取下一个数据
			final AsyncGenerator.Data<E> next = next();
			// 检查数据是否已完成
			if (next.isDone()) {
				// 如果已完成，返回带结果值的完成数据
				return AsyncGenerator.Data.done(next.resultValue);
			}
			// 否则返回组合后的数据
			return AsyncGenerator.Data.of(next.data.thenComposeAsync(mapFunction, executor()));
		};
	}

	/**
	 * Filters the elements of this generator based on the given predicate. Only elements
	 * that satisfy the predicate will be included in the resulting generator.
	 * @param predicate the predicate to test elements against
	 * @return a generator with elements that satisfy the predicate
	 */
	// 根据给定的谓词过滤此生成器的元素，只有满足谓词的元素才会包含在结果生成器中
	default AsyncGenerator<E> filter(Predicate<E> predicate) {
		// 返回一个生成器实例
		return () -> {
			// 获取下一个数据
			AsyncGenerator.Data<E> next = next();
			// 循环直到数据完成
			while (!next.isDone()) {

				// 等待数据完成并获取值
				final E value = next.data.join();

				// 测试值是否满足谓词
				if (predicate.test(value)) {
					// 如果满足谓词，返回当前数据
					return next;
				}
				// 获取下一个数据
				next = next();
			}
			// 如果已完成，返回带结果值的完成数据
			return AsyncGenerator.Data.done(next.resultValue);
		};
	}

	/**
	 * Asynchronously iterates over the elements of the AsyncGenerator and applies the
	 * given consumer to each element.
	 * @param consumer the consumer function to be applied to each element
	 * @return a CompletableFuture representing the completion of the iteration process.
	 */
	// 异步遍历AsyncGenerator的元素并对每个元素应用给定的消费者
	default CompletableFuture<Object> forEachAsync(Consumer<E> consumer) {
		// 初始化一个已完成的future
		CompletableFuture<Object> future = completedFuture(null);
		// 循环处理每个数据元素
		for (AsyncGenerator.Data<E> next = next(); !next.isDone(); next = next()) {
			// 保存当前数据的最终引用
			final AsyncGenerator.Data<E> finalNext = next;
			// 检查是否包含嵌入的生成器
			if (finalNext.embed != null) {
				// 如果包含嵌入生成器，递归处理嵌入的生成器
				future = future.thenCompose(v -> finalNext.embed.generator.async(executor()).forEachAsync(consumer));
			}
			else {
				// 否则对当前数据应用消费者函数
				future = future
					.thenCompose(v -> finalNext.data.thenAcceptAsync(consumer, executor()).thenApply(x -> null));
			}
		}
		// 返回完成的future
		return future;
	}

	/**
	 * Collects elements from the AsyncGenerator asynchronously into a list.
	 * @param <R> the type of the result list
	 * @param result the result list to collect elements into
	 * @param consumer the consumer function for processing elements
	 * @return a CompletableFuture representing the completion of the collection process
	 */
	// 异步将AsyncGenerator的元素收集到列表中
	default <R extends List<E>> CompletableFuture<R> collectAsync(R result, BiConsumer<R, E> consumer) {
		// 初始化一个包含结果列表的已完成future
		CompletableFuture<R> future = completedFuture(result);
		// 循环处理每个数据元素
		for (AsyncGenerator.Data<E> next = next(); !next.isDone(); next = next()) {
			// 保存当前数据的最终引用
			final AsyncGenerator.Data<E> finalNext = next;
			// 组合future并处理数据
			future = future.thenCompose(res -> finalNext.data.thenApplyAsync(v -> {
				// 使用消费者处理结果和值
				consumer.accept(res, v);
				// 返回结果
				return res;
			}, executor()));
		}
		// 返回完成的future
		return future;
	}

}
