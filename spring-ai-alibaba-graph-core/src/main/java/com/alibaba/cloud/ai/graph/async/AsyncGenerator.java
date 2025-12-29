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

import com.alibaba.cloud.ai.graph.async.internal.UnmodifiableDeque;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * An asynchronous generator interface that allows generating asynchronous elements.
 *
 * @param <E> the type of elements. The generator will emit {@link CompletableFuture
 * CompletableFutures&lt;E&gt;} elements
 */
// 异步生成器接口，允许生成异步元素，已标记为废弃
@Deprecated
public interface AsyncGenerator<E> extends Iterable<E>, AsyncGeneratorOperators<E> {

	// 具有结果值的接口，用于获取异步操作的结果值
	interface HasResultValue {

		// 获取结果值的可选包装
		Optional<Object> resultValue();

	}

	// 静态方法，获取异步生成器的结果值
	static Optional<Object> resultValue(AsyncGenerator<?> generator) {
		// 检查生成器是否实现了HasResultValue接口
		if (generator instanceof HasResultValue withResult) {
			// 返回结果值
			return withResult.resultValue();
		}
		// 如果没有实现HasResultValue接口，返回空Optional
		return Optional.empty();
	}

	// 静态方法，获取迭代器的结果值
	static Optional<Object> resultValue(Iterator<?> iterator) {
		// 检查迭代器是否实现了HasResultValue接口
		if (iterator instanceof HasResultValue withResult) {
			// 返回结果值
			return withResult.resultValue();
		}
		// 如果没有实现HasResultValue接口，返回空Optional
		return Optional.empty();
	}

	/**
	 * An asynchronous generator decorator that allows retrieving the result value of the
	 * asynchronous operation, if any.
	 *
	 * @param <E> the type of elements in the generator
	 */
	// 异步生成器装饰器类，允许检索异步操作的结果值（如果存在）
	class WithResult<E> implements AsyncGenerator<E>, HasResultValue {

		// 委托的异步生成器
		protected final AsyncGenerator<E> delegate;

		// 结果值
		private Object resultValue;

		// 使用指定的异步生成器构造WithResult实例
		public WithResult(AsyncGenerator<E> delegate) {
			// 设置委托生成器
			this.delegate = delegate;
		}

		// 获取委托生成器的方法
		public AsyncGenerator<E> delegate() {
			// 返回委托生成器
			return delegate;
		}

		/**
		 * Retrieves the result value of the generator, if any.
		 * @return an {@link Optional} containing the result value if present, or an empty
		 * Optional if not
		 */
		// 获取生成器的结果值（如果存在）
		public Optional<Object> resultValue() {
			// 返回结果值的Optional包装
			return ofNullable(resultValue);
		};

		// 重写next方法，获取下一个数据元素
		@Override
		public final Data<E> next() {
			// 从委托生成器获取数据
			final Data<E> result = delegate.next();
			// 检查数据是否已完成
			if (result.isDone()) {
				// 如果已完成，保存结果值
				resultValue = result.resultValue;
			}
			// 返回数据
			return result;
		}

	}

	/**
	 * An asynchronous generator decorator that allows to generators composition embedding
	 * other generators.
	 *
	 * @param <E> the type of elements in the generator
	 */
	// 异步生成器装饰器类，允许生成器组合嵌入其他生成器
	class WithEmbed<E> implements AsyncGenerator<E>, HasResultValue {

		// 生成器栈，用于嵌套生成器
		protected final Deque<Embed<E>> generatorsStack = new ArrayDeque<>(2);

		// 返回值栈
		private final Deque<Data<E>> returnValueStack = new ArrayDeque<>(2);

		// 使用委托生成器和完成处理器构造WithEmbed实例
		public WithEmbed(AsyncGenerator<E> delegate, EmbedCompletionHandler onGeneratorDoneWithResult) {
			// 将新的Embed实例压入栈中
			generatorsStack.push(new Embed<>(delegate, onGeneratorDoneWithResult));
		}

		// 使用委托生成器构造WithEmbed实例（完成处理器为null）
		public WithEmbed(AsyncGenerator<E> delegate) {
			// 调用带完成处理器的构造函数，完成处理器设为null
			this(delegate, null);
		}

		// 获取结果值栈的不可修改视图
		public Deque<Data<E>> resultValues() {
			// 返回不可修改的双端队列包装器
			return new UnmodifiableDeque<>(returnValueStack);
		}

		// 获取结果值
		public Optional<Object> resultValue() {
			// 获取栈顶元素的结果值
			return ofNullable(returnValueStack.peek()).map(r -> r.resultValue);
		}

		// 清除之前的返回值（如果有的话）
		private void clearPreviousReturnsValuesIfAny() {
			// 检查返回值是否是前一次运行的值
			if (returnValueStack.size() > 1 && returnValueStack.size() == generatorsStack.size()) {
				// 清空返回值栈
				returnValueStack.clear();
			}
		}

		// private AsyncGenerator.WithResult<E> toGeneratorWithResult( AsyncGenerator<E>
		// generator ) {
		// return ( generator instanceof WithResult ) ?
		// (AsyncGenerator.WithResult<E>) generator :
		// new WithResult<>(generator);
		// }

		// 检查是否是最后一个生成器
		protected boolean isLastGenerator() {
			// 如果栈大小为1，表示是最后一个生成器
			return generatorsStack.size() == 1;
		}

		// 重写next方法，获取下一个数据元素
		@Override
		public Data<E> next() {
			// 检查生成器栈是否为空（防护检查）
			if (generatorsStack.isEmpty()) { // GUARD
				// 如果栈为空，抛出非法状态异常
				throw new IllegalStateException("no generator found!");
			}

			// 获取栈顶的嵌入对象
			final Embed<E> embed = generatorsStack.peek();
			// 从嵌入的生成器获取数据
			final Data<E> result = embed.generator.next();

			// 检查数据是否已完成
			if (result.isDone()) {
				// 清除之前的返回值（如果有的话）
				clearPreviousReturnsValuesIfAny();
				// 将结果压入返回值栈
				returnValueStack.push(result);
				// 检查完成处理器是否存在
				if (embed.onCompletion != null /* && result.resultValue != null */ ) {
					// 尝试执行完成处理器
					try {
						// 调用完成处理器，传入结果值
						embed.onCompletion.accept(result.resultValue);
					}
					// 捕获执行过程中的异常
					catch (Exception e) {
						// 返回错误数据
						return Data.error(e);
					}
				}
				// 检查是否是最后一个生成器
				if (isLastGenerator()) {
					// 如果是最后一个生成器，返回结果
					return result;
				}
				// 从栈中弹出当前生成器
				generatorsStack.pop();
				// 递归调用next方法
				return next();
			}
			// 检查数据是否包含嵌入对象
			if (result.embed != null) {
				// 检查嵌套深度是否超过限制
				if (generatorsStack.size() >= 2) {
					// 返回错误数据，表示不支持递归嵌套生成器
					return Data.error(new UnsupportedOperationException(
							"Currently recursive nested generators are not supported!"));
				}
				// 将嵌入的生成器压入栈中
				generatorsStack.push(result.embed);
				// 递归调用next方法
				return next();
			}

			// 返回结果
			return result;
		}

	}

	// 嵌入完成处理器函数式接口
	@FunctionalInterface
	interface EmbedCompletionHandler {

		// 接受对象并可能抛出异常的方法
		void accept(Object t) throws Exception;

	}

	// 嵌入类，用于包装生成器和完成处理器
	class Embed<E> {

		// 嵌入的异步生成器
		final AsyncGenerator<E> generator;

		// 完成处理器
		final EmbedCompletionHandler onCompletion;

		// 使用生成器和完成处理器构造嵌入实例
		public Embed(AsyncGenerator<E> generator, EmbedCompletionHandler onCompletion) {
			// 检查生成器是否为null，如果是则抛出异常
			Objects.requireNonNull(generator, "generator cannot be null");
			// 设置生成器
			this.generator = generator;
			// 设置完成处理器
			this.onCompletion = onCompletion;
		}

		// 获取嵌入的生成器
		public AsyncGenerator<E> getGenerator() {
			// 返回嵌入的生成器
			return generator;
		}

	}

	/**
	 * Represents a data element in the AsyncGenerator.
	 *
	 * @param <E> the type of the data element
	 */
	// 在AsyncGenerator中表示数据元素的类
	class Data<E> {

		// 数据的CompletableFuture
		final CompletableFuture<E> data;

		// 嵌入的生成器
		final Embed<E> embed;

		// 结果值
		final Object resultValue;

		// 使用CompletableFuture、嵌入对象和结果值构造Data实例
		public Data(CompletableFuture<E> data, Embed<E> embed, Object resultValue) {
			// 设置数据
			this.data = data;
			// 设置嵌入对象
			this.embed = embed;
			// 设置结果值
			this.resultValue = resultValue;
		}

		// 获取数据的CompletableFuture
		public CompletableFuture<E> getData() {
			// 返回数据
			return data;
		}

		// 获取嵌入对象
		public Embed<E> getEmbed() {
			// 返回嵌入对象
			return embed;
		}

		// 获取结果值的Optional包装
		public Optional<Object> resultValue() {
			// 如果结果值为null，返回空Optional，否则返回结果值的Optional包装
			return resultValue == null ? Optional.empty() : Optional.of(resultValue);
		}

		// 检查数据是否已完成
		public boolean isDone() {
			// 如果data和embed都为null，表示已完成
			return data == null && embed == null;
		}

		// 检查数据是否出错
		public boolean isError() {
			// 如果data不为null且已完成异常，表示出错
			return data != null && data.isCompletedExceptionally();
		}

		// 使用CompletableFuture创建Data实例的静态方法
		public static <E> Data<E> of(CompletableFuture<E> data) {
			// 创建Data实例，嵌入对象和结果值设为null
			return new Data<>(data, null, null);
		}

		// 使用普通数据创建Data实例的静态方法
		public static <E> Data<E> of(E data) {
			// 使用completedFuture包装数据，嵌入对象和结果值设为null
			return new Data<>(completedFuture(data), null, null);
		}

		// 使用生成器和完成处理器创建组合Data实例的静态方法
		public static <E> Data<E> composeWith(AsyncGenerator<E> generator, EmbedCompletionHandler onCompletion) {
			// 创建Data实例，data和resultValue设为null，embed设为新创建的Embed对象
			return new Data<>(null, new Embed<>(generator, onCompletion), null);
		}

		// 创建完成状态的Data实例的静态方法
		public static <E> Data<E> done() {
			// 创建data、embed和resultValue都为null的Data实例
			return new Data<>(null, null, null);
		}

		// 创建带结果值的完成状态Data实例的静态方法
		public static <E> Data<E> done(Object resultValue) {
			// 创建data和embed为null，resultValue为指定值的Data实例
			return new Data<>(null, null, resultValue);
		}

		// 使用异常创建错误状态Data实例的静态方法
		public static <E> Data<E> error(Throwable exception) {
			// 创建一个新的CompletableFuture实例
			CompletableFuture<E> future = new CompletableFuture<>();
			// 将future设置为异常完成状态
			future.completeExceptionally(exception);
			// 返回包含异常future的Data实例
			return Data.of(future);
		}

	}

	// 默认方法，使用指定的执行器创建异步生成器操作符
	default AsyncGeneratorOperators<E> async(Executor executor) {
		// 返回一个匿名实现类
		return new AsyncGeneratorOperators<E>() {
			// 重写next方法，返回原始生成器的next结果
			@Override
			public Data<E> next() {
				// 调用原始AsyncGenerator的next方法
				return AsyncGenerator.this.next();
			}

			// 重写executor方法，返回指定的执行器
			@Override
			public Executor executor() {
				// 返回传入的执行器
				return executor;
			}
		};
	}

	/**
	 * Retrieves the next asynchronous element.
	 * @return the next element from the generator
	 */
	// 获取下一个异步元素
	Data<E> next();

	/**
	 * Converts the AsyncGenerator to a CompletableFuture.
	 * @return a CompletableFuture representing the completion of the AsyncGenerator
	 */
	// 将AsyncGenerator转换为CompletableFuture
	default CompletableFuture<Object> toCompletableFuture() {
		// 获取下一个数据
		final Data<E> next = next();
		// 检查数据是否已完成
		if (next.isDone()) {
			// 如果已完成，返回包含结果值的已完成future
			return completedFuture(next.resultValue);
		}
		// 否则将当前数据的future与递归调用toCompletableFuture连接
		return next.data.thenCompose(v -> toCompletableFuture());
	}

	/**
	 * Returns a sequential Stream with the elements of this AsyncGenerator. Each
	 * CompletableFuture is resolved and then make available to the stream.
	 * @return a Stream of elements from the AsyncGenerator
	 */
	// 返回包含此AsyncGenerator元素的顺序流，每个CompletableFuture都会被解析然后提供给流
	default Stream<E> stream() {
		// 使用Spliterators创建流，从迭代器获取元素
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED), false);
	}

	/**
	 * Returns an iterator over the elements of this AsyncGenerator. Each call to [next](file://D:\Work\github\spring-ai-alibaba\spring-ai-alibaba-graph-core\src\main\java\com\alibaba\cloud\ai\graph\state\StateSnapshot.java#L31-L33)
	 * retrieves the next "resolved" asynchronous element from the generator.
	 * @return an iterator over the elements of this AsyncGenerator
	 */
	// 返回此AsyncGenerator元素的迭代器，每次调用next都会从生成器获取下一个"已解析"的异步元素
	default Iterator<E> iterator() {
		// 返回内部迭代器实例
		return new InternalIterator<E>(this);
	}

	/**
	 * Returns an empty AsyncGenerator.
	 * @param <E> the type of elements
	 * @return an empty AsyncGenerator
	 */
	// 返回空的AsyncGenerator
	static <E> AsyncGenerator<E> empty() {
		// 返回Data.done方法引用的生成器
		return Data::done;
	}

	/**
	 * create a generator, mapping each element to an asynchronous counterpart.
	 * @param <E> the type of elements in the collection
	 * @param <U> the type of elements in the CompletableFuture
	 * @param iterator the elements iterator
	 * @param mapFunction the function to map elements to {@link CompletableFuture}
	 * @return an AsyncGenerator instance with mapped elements
	 */
	// 创建生成器，将每个元素映射到异步对应项
	static <E, U> AsyncGenerator<U> map(Iterator<E> iterator, Function<E, CompletableFuture<U>> mapFunction) {
		// 返回一个生成器实例
		return () -> {
			// 检查迭代器是否还有元素
			if (!iterator.hasNext()) {
				// 如果没有更多元素，返回完成状态
				return Data.done();
			}
			// 否则获取下一个元素，应用映射函数并包装为Data
			return Data.of(mapFunction.apply(iterator.next()));
		};
	}

	/**
	 * Collects asynchronous elements from an iterator.
	 * @param <E> the type of elements in the iterator
	 * @param <U> the type of elements in the CompletableFuture
	 * @param iterator the iterator containing elements to collect
	 * @param consumer the function to consume elements and add them to the accumulator
	 * @return an AsyncGenerator instance with collected elements
	 */
	// 从迭代器收集异步元素
	static <E, U> AsyncGenerator<U> collect(Iterator<E> iterator,
			BiConsumer<E, Consumer<CompletableFuture<U>>> consumer) {
		// 创建用于收集CompletableFuture的列表
		final List<CompletableFuture<U>> accumulator = new ArrayList<>();

		// 创建添加元素的消费者
		final Consumer<CompletableFuture<U>> addElement = accumulator::add;
		// 遍历迭代器
		while (iterator.hasNext()) {
			// 使用消费者处理当前元素
			consumer.accept(iterator.next(), addElement);
		}

		// 获取收集结果的迭代器
		final Iterator<CompletableFuture<U>> it = accumulator.iterator();
		// 返回生成器实例
		return () -> {
			// 检查是否有更多元素
			if (!it.hasNext()) {
				// 如果没有更多元素，返回完成状态
				return Data.done();
			}
			// 否则返回下一个CompletableFuture
			return Data.of(it.next());
		};
	}

	/**
	 * create a generator, mapping each element to an asynchronous counterpart.
	 * @param <E> the type of elements in the collection
	 * @param <U> the type of elements in the CompletableFuture
	 * @param collection the collection of elements to map
	 * @param mapFunction the function to map elements to CompletableFuture
	 * @return an AsyncGenerator instance with mapped elements
	 */
	// 创建生成器，将集合中的每个元素映射到异步对应项
	static <E, U> AsyncGenerator<U> map(Collection<E> collection, Function<E, CompletableFuture<U>> mapFunction) {
		// 检查集合是否为null或空
		if (collection == null || collection.isEmpty()) {
			// 如果集合为空，返回空生成器
			return empty();
		}
		// 否则使用集合的迭代器进行映射
		return map(collection.iterator(), mapFunction);
	}

	/**
	 * Collects asynchronous elements from a collection.
	 * @param <E> the type of elements in the iterator
	 * @param <U> the type of elements in the CompletableFuture
	 * @param collection the iterator containing elements to collect
	 * @param consumer the function to consume elements and add them to the accumulator
	 * @return an AsyncGenerator instance with collected elements
	 */
	// 从集合收集异步元素
	static <E, U> AsyncGenerator<U> collect(Collection<E> collection,
			BiConsumer<E, Consumer<CompletableFuture<U>>> consumer) {
		// 检查集合是否为null或空
		if (collection == null || collection.isEmpty()) {
			// 如果集合为空，返回空生成器
			return empty();
		}
		// 否则使用集合的迭代器进行收集
		return collect(collection.iterator(), consumer);
	}

	/**
	 * Creates an AsyncGenerator from a Project Reactor Flux. This method provides
	 * backward compatibility for converting reactive streams to the AsyncGenerator
	 * interface.
	 * @param <E> the type of elements in the Flux
	 * @param flux the Flux to convert
	 * @return an AsyncGenerator that wraps the Flux
	 */
	// 从Project Reactor Flux创建AsyncGenerator，此方法提供将响应式流转换为AsyncGenerator接口的向后兼容性
	static <E> AsyncGenerator<E> fromFlux(reactor.core.publisher.Flux<E> flux) {
		// 检查flux是否为null
		Objects.requireNonNull(flux, "flux cannot be null");

		// Convert Flux to Iterator using blocking approach for simplicity
		// This maintains compatibility with the existing AsyncGenerator pattern
		// 将Flux转换为Iterator，使用阻塞方式以简化实现，这保持了与现有AsyncGenerator模式的兼容性
		final Iterator<E> iterator = flux.toIterable().iterator();

		// 返回生成器实例
		return () -> {
			// 检查迭代器是否还有元素
			if (!iterator.hasNext()) {
				// 如果没有更多元素，返回完成状态
				return Data.done();
			}
			// 尝试获取下一个元素
			try {
				// 获取下一个元素
				E element = iterator.next();
				// 返回包含元素的Data实例
				return Data.of(element);
			}
			// 捕获执行过程中的异常
			catch (Exception e) {
				// 返回包含异常的错误数据
				return Data.error(e);
			}
		};
	}

}

// 内部迭代器类，已标记为废弃
@Deprecated
class InternalIterator<E> implements Iterator<E>, AsyncGenerator.HasResultValue {

	// 委托的异步生成器
	private final AsyncGenerator<E> delegate;

	// 当前获取的数据的原子引用
	final AtomicReference<AsyncGenerator.Data<E>> currentFetchedData;

	// 使用指定的异步生成器构造内部迭代器
	public InternalIterator(AsyncGenerator<E> delegate) {
		// 设置委托生成器
		this.delegate = delegate;
		// 初始化当前获取数据的引用，获取第一个数据
		currentFetchedData = new AtomicReference<>(delegate.next());
	}

	// 重写hasNext方法，检查是否还有更多元素
	@Override
	public boolean hasNext() {
		// 获取当前数据
		final var value = currentFetchedData.get();
		// 检查数据是否不为null且未完成
		return value != null && !value.isDone();
	}

	// 重写next方法，获取下一个元素
	@Override
	public E next() {
		// 获取当前数据
		var next = currentFetchedData.get();

		// 检查数据是否为null或已完成
		if (next == null || next.isDone()) {
			// 如果没有更多元素，抛出非法状态异常
			throw new IllegalStateException("no more elements into iterator");
		}

		// 检查数据是否没有错误
		if (!next.isError()) {
			// 更新当前获取的数据引用为下一个数据
			currentFetchedData.set(delegate.next());
		}

		// 等待数据的CompletableFuture完成并返回结果
		return next.data.join();
	}

	// 重写resultValue方法，获取结果值
	@Override
	public Optional<Object> resultValue() {
		// 检查委托生成器是否实现了HasResultValue接口
		if (delegate instanceof AsyncGenerator.HasResultValue withResult) {
			// 返回委托生成器的结果值
			return withResult.resultValue();
		}
		// 如果没有实现HasResultValue接口，返回空Optional
		return Optional.empty();
	}

};
