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
package com.alibaba.cloud.ai.graph.async.internal;

import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;

@Deprecated
// 不可修改的双端队列实现类，实现了Deque接口
public class UnmodifiableDeque<T> implements Deque<T> {

	// 底层的双端队列委托对象
	private final Deque<T> deque;

	// 使用指定的双端队列构造不可修改的双端队列包装器
	public UnmodifiableDeque(Deque<T> deque) {
		// 将传入的双端队列赋值给委托对象
		this.deque = deque;
	}

	// 重写add方法，添加元素操作不被支持
	@Override
	public boolean add(T t) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写offer方法，提供元素到队列尾部操作不被支持
	@Override
	public boolean offer(T t) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写remove方法，移除并返回队列头部元素操作不被支持
	@Override
	public T remove() {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写poll方法，获取并移除队列头部元素操作不被支持
	@Override
	public T poll() {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写element方法，获取但不移除队列头部元素（不支持修改操作）
	@Override
	public T element() {
		// 调用底层双端队列的element方法
		return deque.element();
	}

	// 重写peek方法，获取但不移除队列头部元素（不支持修改操作）
	@Override
	public T peek() {
		// 调用底层双端队列的peek方法
		return deque.peek();
	}

	// 重写addFirst方法，添加元素到队列头部操作不被支持
	@Override
	public void addFirst(T t) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写addLast方法，添加元素到队列尾部操作不被支持
	@Override
	public void addLast(T t) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写offerFirst方法，添加元素到队列头部操作不被支持
	@Override
	public boolean offerFirst(T t) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写offerLast方法，添加元素到队列尾部操作不被支持
	@Override
	public boolean offerLast(T t) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写removeFirst方法，移除队列头部元素操作不被支持
	@Override
	public T removeFirst() {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写removeLast方法，移除队列尾部元素操作不被支持
	@Override
	public T removeLast() {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写pollFirst方法，获取并移除队列头部元素操作不被支持
	@Override
	public T pollFirst() {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写pollLast方法，获取并移除队列尾部元素操作不被支持
	@Override
	public T pollLast() {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写getFirst方法，获取队列头部元素（不支持修改操作）
	@Override
	public T getFirst() {
		// 调用底层双端队列的getFirst方法
		return deque.getFirst();
	}

	// 重写getLast方法，获取队列尾部元素（不支持修改操作）
	@Override
	public T getLast() {
		// 调用底层双端队列的getLast方法
		return deque.getLast();
	}

	// 重写peekFirst方法，获取队列头部元素但不移除（不支持修改操作）
	@Override
	public T peekFirst() {
		// 调用底层双端队列的peekFirst方法
		return deque.peekFirst();
	}

	// 重写peekLast方法，获取队列尾部元素但不移除（不支持修改操作）
	@Override
	public T peekLast() {
		// 调用底层双端队列的peekLast方法
		return deque.peekLast();
	}

	// 重写removeFirstOccurrence方法，移除第一次出现的指定元素操作不被支持
	@Override
	public boolean removeFirstOccurrence(Object o) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写removeLastOccurrence方法，移除最后一次出现的指定元素操作不被支持
	@Override
	public boolean removeLastOccurrence(Object o) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写addAll方法，添加所有元素操作不被支持
	@Override
	public boolean addAll(Collection<? extends T> c) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写clear方法，清空队列操作不被支持
	@Override
	public void clear() {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写retainAll方法，保留指定集合中的元素操作不被支持
	@Override
	public boolean retainAll(Collection<?> c) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写removeAll方法，移除指定集合中的所有元素操作不被支持
	@Override
	public boolean removeAll(Collection<?> c) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写containsAll方法，检查是否包含指定集合中的所有元素（不支持修改操作）
	@Override
	public boolean containsAll(Collection<?> c) {
		// 调用底层双端队列的containsAll方法
		return deque.containsAll(c);
	}

	// 重写contains方法，检查是否包含指定元素（不支持修改操作）
	@Override
	public boolean contains(Object o) {
		// 调用底层双端队列的contains方法
		return deque.contains(o);
	}

	// 重写size方法，获取队列大小（不支持修改操作）
	@Override
	public int size() {
		// 调用底层双端队列的size方法
		return deque.size();
	}

	// 重写isEmpty方法，检查队列是否为空（不支持修改操作）
	@Override
	public boolean isEmpty() {
		// 调用底层双端队列的isEmpty方法
		return deque.isEmpty();
	}

	// 重写iterator方法，获取正向迭代器（不支持修改操作）
	@Override
	public Iterator<T> iterator() {
		// 调用底层双端队列的iterator方法
		return deque.iterator();
	}

	// 重写toArray方法，转换为对象数组（不支持修改操作）
	@Override
	public Object[] toArray() {
		// 调用底层双端队列的toArray方法
		return deque.toArray();
	}

	// 重写toArray方法，转换为指定类型数组（不支持修改操作）
	@Override
	public <T1> T1[] toArray(T1[] a) {
		// 调用底层双端队列的toArray方法
		return deque.toArray(a);
	}

	// 重写descendingIterator方法，获取反向迭代器（不支持修改操作）
	@Override
	public Iterator<T> descendingIterator() {
		// 调用底层双端队列的descendingIterator方法
		return deque.descendingIterator();
	}

	// 重写push方法，压入元素到队列头部操作不被支持
	@Override
	public void push(T t) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写pop方法，弹出队列头部元素操作不被支持
	@Override
	public T pop() {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

	// 重写remove方法，移除指定元素操作不被支持
	@Override
	public boolean remove(Object o) {
		// 抛出不支持操作异常
		throw new UnsupportedOperationException();
	}

}
