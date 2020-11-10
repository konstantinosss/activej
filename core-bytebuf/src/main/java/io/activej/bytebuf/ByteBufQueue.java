/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.bytebuf;

import io.activej.common.ApplicationSettings;
import io.activej.common.Checks;
import io.activej.common.exception.UncheckedException;
import io.activej.common.exception.parse.InvalidSizeException;
import io.activej.common.exception.parse.ParseException;
import io.activej.common.recycle.Recyclable;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.function.Consumer;
import java.util.stream.Collector;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.Checks.checkState;
import static io.activej.common.collection.CollectionUtils.emptyIterator;
import static java.lang.System.arraycopy;

/**
 * Represents a circular FIFO queue of ByteBufs optimized
 * for efficient work with multiple ByteBufs.
 * <p>
 * There are <i>first</i> and <i>last</i> indexes which
 * represent which ByteBuf of the queue is currently
 * the first and the last to be taken.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public final class ByteBufQueue implements Recyclable {
	private static final boolean CHECK = Checks.isEnabled(ByteBufQueue.class);

	private static final int DEFAULT_CAPACITY = 8;
	/**
	 * If set, nullifies bytebufs when they are taken out of queue. Set this setting ON if you need more control over memory.
	 * For example, it is reasonable to enable this setting if you explicitly clear {@link ByteBufPool}
	 */
	private static final boolean NULLIFY_ON_TAKE_OUT = ApplicationSettings.getBoolean(ByteBufQueue.class, "nullifyOnTakeOut", ByteBufPool.USE_WATCHDOG);

	private ByteBuf[] bufs;

	private int first = 0;
	private int last = 0;

	/**
	 * Returns a ByteBufQueue whose capacity is 8.
	 */
	public ByteBufQueue() {
		this(DEFAULT_CAPACITY);
	}

	public ByteBufQueue(int capacity) {
		this.bufs = new ByteBuf[capacity];
	}

	private static final Collector<ByteBuf, ByteBufQueue, ByteBuf> COLLECTOR = Collector.of(
			ByteBufQueue::new,
			ByteBufQueue::add,
			(bufs1, bufs2) -> {
				throw new UnsupportedOperationException("Parallel collection of byte bufs is not supported");
			},
			ByteBufQueue::takeRemaining);

	/**
	 * Accumulates input {@code ByteBuf}s into {@code ByteBufQueue} and then transforms
	 * accumulated result into another {@code ByteBuf}.
	 *
	 * @return a {@link Collector} described with ByteBuf, ByteBufQueue and a resulting ByteBuf
	 */
	public static Collector<ByteBuf, ByteBufQueue, ByteBuf> collector() {
		return COLLECTOR;
	}

	public static Collector<ByteBuf, ByteBufQueue, ByteBuf> collector(int maxSize) {
		return Collector.of(
				ByteBufQueue::new,
				(queue, buf) -> {
					int size = buf.readRemaining();
					if (size > maxSize || queue.hasRemainingBytes(maxSize - size + 1)) {
						queue.recycle();
						buf.recycle();
						throw new UncheckedException(new InvalidSizeException(ByteBufQueue.class,
								"ByteBufQueue exceeds maximum size of " + maxSize + " bytes"));
					}
					queue.add(buf);
				},
				(bufs1, bufs2) -> {
					throw new UnsupportedOperationException("Parallel collection of byte bufs is not supported");
				},
				ByteBufQueue::takeRemaining);
	}

	private int next(int i) {
		return (i + 1) % bufs.length;
	}

	private void grow() {
		ByteBuf[] newBufs = new ByteBuf[bufs.length * 2];
		arraycopy(bufs, last, newBufs, 0, bufs.length - last);
		arraycopy(bufs, 0, newBufs, bufs.length - last, last);
		first = 0;
		last = bufs.length;
		bufs = newBufs;
	}

	/**
	 * Adds provided ByteBuf to this ByteBufQueue.
	 * If this ByteBuf hasn't readable bytes, it won't
	 * be added to the queue and will be recycled.
	 * <p>
	 * The added ByteBuf is set at the current {@code last}
	 * position of the queue. Then {@code last} index is
	 * increased by 1 or set to the value 0 if it has run
	 * a full circle of the queue.
	 * <p>
	 * If {@code last} and {@code first} indexes become the same,
	 * this ByteBufQueue size will be doubled.
	 *
	 * @param buf the ByteBuf to be added to the queue
	 */
	public void add(@NotNull ByteBuf buf) {
		if (!buf.canRead()) {
			buf.recycle();
			return;
		}

		bufs[last] = buf;
		last = next(last);
		if (last == first) {
			grow();
		}
	}

	public void addAll(@NotNull Iterable<ByteBuf> byteBufs) {
		for (ByteBuf buf : byteBufs) {
			add(buf);
		}
	}

	/**
	 * Returns the first ByteBuf of the queue if the queue is not empty.
	 * Then {@code first} index is increased by 1 or set to the value 0
	 * if it has run a full circle of the queue.
	 *
	 * @return the first ByteBuf of this {@code ByteBufQueue}
	 */
	@NotNull
	public ByteBuf take() {
		if (CHECK) checkState(hasRemaining(), "No bufs to take");
		ByteBuf buf = bufs[first];
		if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
		first = next(first);
		return buf;
	}

	/**
	 * Returns the first ByteBuf of the queue if the queue is not empty
	 * otherwise returns {@code null}.
	 *
	 * @return the first ByteBuf of this {@code ByteBufQueue}. If the queue is
	 * empty, returns null
	 * @see #take()
	 */
	@Nullable
	public ByteBuf poll() {
		if (hasRemaining()) {
			return take();
		}
		return null;
	}

	/**
	 * Creates and returns a {@link io.activej.bytebuf.ByteBuf.ByteBufSlice}
	 * which contains {@code size} bytes from queue's first ByteBuf if the
	 * latter contains too many bytes.
	 * <p>
	 * Otherwise creates and returns a ByteBuf which contains all
	 * bytes from the first ByteBuf in the queue. Then {@code first}
	 * index is increased by 1 or set to the value 0 if it has run
	 * a full circle of the queue.
	 *
	 * @param size number of bytes to be returned
	 * @return ByteBuf with result bytes
	 */
	@NotNull
	public ByteBuf takeAtMost(int size) {
		if (isEmpty() || size == 0) return ByteBuf.empty();
		ByteBuf buf = bufs[first];
		if (size >= buf.readRemaining()) {
			if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
			first = next(first);
			return buf;
		}
		ByteBuf result = buf.slice(size);
		buf.moveHead(size);
		return result;
	}

	/**
	 * Creates and returns a ByteBuf which contains at least {@code size}
	 * bytes from queue's first ByteBuf if the latter contains enough bytes.
	 * Then {@code first} index is increased by 1 or set to the value 0 if it
	 * has run a full circle of the queue.
	 * <p>
	 * Otherwise a new ByteBuf is allocated from the {@link ByteBufPool} with
	 * {@code size} bytes which contains all data from the queue's first ByteBuf.
	 *
	 * @param size the minimum size of returned ByteBuf
	 * @return a ByteBuf which contains at least {@code size} bytes
	 */
	@NotNull
	public ByteBuf takeAtLeast(int size) {
		if (CHECK) checkArgument(hasRemainingBytes(size), () -> "Queue does not have " + size + " bufs");
		if (size == 0) return ByteBuf.empty();
		ByteBuf buf = bufs[first];
		if (buf.readRemaining() >= size) {
			if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
			first = next(first);
			return buf;
		}
		ByteBuf result = ByteBufPool.allocate(size);
		drainTo(result.array(), 0, size);
		result.moveTail(size);
		return result;
	}

	@NotNull
	public ByteBuf takeAtLeast(int size, @NotNull Consumer<ByteBuf> recycledBufs) {
		if (CHECK) checkArgument(hasRemainingBytes(size), () -> "Queue does not have " + size + " bufs");
		if (size == 0) return ByteBuf.empty();
		ByteBuf buf = bufs[first];
		if (buf.readRemaining() >= size) {
			if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
			first = next(first);
			return buf;
		}
		ByteBuf result = ByteBufPool.allocate(size);
		drainTo(result.array(), 0, size, recycledBufs);
		result.moveTail(size);
		return result;
	}

	/**
	 * Creates and returns a ByteBuf which contains all bytes from the
	 * queue's first ByteBuf if the latter contains {@code exactSize} of bytes.
	 * Then {@code first} index is increased by 1 or set to the value 0 if it
	 * has run a full circle of the queue.
	 * <p>
	 * Otherwise creates and returns a ByteBuf of {@code exactSize} which
	 * contains all bytes from queue's first ByteBuf.
	 *
	 * @param exactSize the size of returned ByteBuf
	 * @return ByteBuf with {@code exactSize} bytes
	 */
	@NotNull
	public ByteBuf takeExactSize(int exactSize) {
		if (CHECK) checkArgument(hasRemainingBytes(exactSize), () -> "Queue does not have " + exactSize + " bufs");
		if (exactSize == 0) return ByteBuf.empty();
		ByteBuf buf = bufs[first];
		if (buf.readRemaining() == exactSize) {
			if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
			first = next(first);
			return buf;
		} else if (exactSize < buf.readRemaining()) {
			ByteBuf result = buf.slice(exactSize);
			buf.moveHead(exactSize);
			return result;
		}
		ByteBuf result = ByteBufPool.allocate(exactSize);
		drainTo(result.array(), 0, exactSize);
		result.moveTail(exactSize);
		return result;
	}

	@NotNull
	public ByteBuf takeExactSize(int exactSize, @NotNull Consumer<ByteBuf> recycledBufs) {
		if (CHECK) checkArgument(hasRemainingBytes(exactSize), () -> "Queue does not have " + exactSize + " bufs");
		if (exactSize == 0) return ByteBuf.empty();
		ByteBuf buf = bufs[first];
		if (buf.readRemaining() == exactSize) {
			if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
			first = next(first);
			return buf;
		} else if (exactSize < buf.readRemaining()) {
			ByteBuf result = buf.slice(exactSize);
			buf.moveHead(exactSize);
			return result;
		}
		ByteBuf result = ByteBufPool.allocate(exactSize);
		drainTo(result.array(), 0, exactSize, recycledBufs);
		result.moveTail(exactSize);
		return result;
	}

	/**
	 * Consumes the first ByteBuf of the queue to the provided consumer
	 * if the ByteBuf has at least {@code size} bytes available for reading.
	 * If after consuming ByteBuf has no readable bytes left, it is recycled
	 * and {@code first} index is increased by 1 or set to the value 0 if it
	 * has run a full circle of the queue.
	 * <p>
	 * If the first ByteBuf of the queue doesn't have enough bytes available
	 * for reading, a new ByteBuf with {@code size} bytes is created, it contains
	 * all data from the queue's first ByteBuf. This new ByteBuf is consumed and
	 * then recycled.
	 *
	 * @param size     the size of the ByteBuf to be consumed
	 * @param consumer a consumer for the ByteBuf
	 */
	public void consume(int size, @NotNull Consumer<ByteBuf> consumer) {
		if (CHECK) checkArgument(hasRemainingBytes(size), () -> "Queue does not have " + size + " bufs");
		ByteBuf buf = bufs[first];
		if (buf.readRemaining() >= size) {
			int newPos = buf.head() + size;
			consumer.accept(buf);
			buf.head(newPos);
			if (!buf.canRead()) {
				if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
				first = next(first);
				buf.recycle();
			}
		} else {
			buf = ByteBufPool.allocate(size);
			drainTo(buf, size);
			try {
				consumer.accept(buf);
			} finally {
				buf.recycle();
			}
		}
	}

	/**
	 * Creates and returns a ByteBuf with all remaining bytes of the queue.
	 *
	 * @return ByteBuf with all remaining bytes
	 */
	@NotNull
	public ByteBuf takeRemaining() {
		return takeExactSize(remainingBytes());
	}

	/**
	 * Returns the first ByteBuf of this queue if the queue is not empty.
	 * Otherwise returns null.
	 *
	 * @return the first ByteBuf of the queue or {@code null}
	 */
	@Contract(pure = true)
	public ByteBuf peekBuf() {
		return hasRemaining() ? bufs[first] : null;
	}

	/**
	 * Returns the ByteBuf of the given index relatively to the {@code first}
	 * index (head) of the queue.
	 *
	 * @param n index of the ByteBuf to return (relatively to the head of the queue)
	 * @return a ByteBuf of the given index
	 */
	@NotNull
	@Contract(pure = true)
	public ByteBuf peekBuf(int n) {
		if (CHECK) checkArgument(n <= remainingBufs(), "Index exceeds queue size");
		return bufs[(first + n) % bufs.length];
	}

	public int peekTo(@NotNull byte[] dest, int destOffset, int maxSize) {
		int s = maxSize;
		int first = this.first;
		while (first != this.last) {
			ByteBuf buf = bufs[first];
			int remaining = buf.readRemaining();
			if (s < remaining) {
				arraycopy(buf.array(), buf.head(), dest, destOffset, s);
				return maxSize;
			} else {
				arraycopy(buf.array(), buf.head(), dest, destOffset, remaining);
				first = next(first);
				s -= remaining;
				destOffset += remaining;
			}
		}
		return maxSize - s;
	}

	/**
	 * Returns the number of ByteBufs in this queue.
	 */
	@Contract(pure = true)
	public int remainingBufs() {
		return (bufs.length + (last - first)) % bufs.length;
	}

	/**
	 * Returns the number of bytes in this queue.
	 */
	@Contract(pure = true)
	public int remainingBytes() {
		int result = 0;
		for (int i = first; i != last; i = next(i)) {
			result += bufs[i].readRemaining();
		}
		return result;
	}

	@Contract(pure = true)
	public boolean isEmpty() {
		return !hasRemaining();
	}

	/**
	 * Checks if this queue is empty.
	 *
	 * @return true only if there is at least one element remains in this queue
	 */
	@Contract(pure = true)
	public boolean hasRemaining() {
		return first != last;
	}

	/**
	 * Checks if this queue has at least {@code remaining} bytes.
	 *
	 * @param remaining number of bytes to be checked
	 * @return true if the queue contains at least {@code remaining} bytes
	 */
	@Contract(pure = true)
	public boolean hasRemainingBytes(int remaining) {
		if (CHECK) checkArgument(remaining >= 0, "Cannot check for negative bytes");
		if (remaining == 0) return true;
		for (int i = first; i != last; i = next(i)) {
			int bufRemaining = bufs[i].readRemaining();
			if (bufRemaining >= remaining)
				return true;
			remaining -= bufRemaining;
		}
		return false;
	}

	/**
	 * Returns the first byte of the first ByteBuf of this queue
	 * and increases {@link ByteBuf#head()} of the ByteBuf. If there are no
	 * readable bytes left after the operation, this ByteBuf will be recycled.
	 */
	public byte getByte() {
		if (CHECK) checkState(hasRemaining(), "No bytes to get");
		ByteBuf buf = bufs[first];
		if (CHECK) checkState(buf.canRead(), "Empty buf is found in queue");
		byte result = buf.get();
		if (!buf.canRead()) {
			bufs[first].recycle();
			if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
			first = next(first);
		}
		return result;
	}

	/**
	 * Returns the first byte from this queue without any recycling.
	 *
	 * @see #getByte().
	 */
	@Contract(pure = true)
	public byte peekByte() {
		if (CHECK) checkState(hasRemaining(), "No bytes to peek");
		ByteBuf buf = bufs[first];
		return buf.peek();
	}

	/**
	 * Returns the byte from this queue of the given index
	 * (not necessarily from the first ByteBuf of the queue).
	 *
	 * @param index the index at which the bytes will be returned
	 */
	@Contract(pure = true)
	public byte peekByte(int index) {
		if (CHECK) checkState(hasRemainingBytes(index + 1), "Index exceeds the number of bytes in queue");
		for (int i = first; ; i = next(i)) {
			ByteBuf buf = bufs[i];
			if (index < buf.readRemaining())
				return buf.peek(index);
			index -= buf.readRemaining();
		}
	}

	public void setByte(int index, byte b) {
		if (CHECK) checkArgument(hasRemainingBytes(index + 1), "Index exceeds queue bytes length");
		for (int i = first; ; i = next(i)) {
			ByteBuf buf = bufs[i];
			if (index < buf.readRemaining()) {
				buf.array[buf.head + index] = b;
				return;
			}
			index -= buf.readRemaining();
		}
	}

	/**
	 * Removes {@code maxSize} bytes from this queue.
	 *
	 * @param maxSize number of bytes to be removed
	 * @return number of removed bytes
	 */
	public int skip(int maxSize) {
		int s = maxSize;
		while (hasRemaining()) {
			ByteBuf buf = bufs[first];
			int remaining = buf.readRemaining();
			if (s < remaining) {
				buf.moveHead(s);
				return maxSize;
			} else {
				buf.recycle();
				if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
				first = next(first);
				s -= remaining;
			}
		}
		return maxSize - s;
	}

	public int skip(int maxSize, @NotNull Consumer<ByteBuf> recycledBufs) {
		int s = maxSize;
		while (hasRemaining()) {
			ByteBuf buf = bufs[first];
			int remaining = buf.readRemaining();
			if (s < remaining) {
				buf.moveHead(s);
				return maxSize;
			} else {
				recycledBufs.accept(buf);
				buf.recycle();
				if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
				first = next(first);
				s -= remaining;
			}
		}
		return maxSize - s;
	}

	/**
	 * Adds {@code maxSize} bytes from this queue to {@code dest} if queue
	 * contains more than {@code maxSize} bytes. Otherwise adds all
	 * bytes from queue to {@code dest}. In both cases increases queue's
	 * position to the number of drained bytes.
	 *
	 * @param dest       array to drain to
	 * @param destOffset start position for adding to dest
	 * @param maxSize    number of bytes for adding
	 * @return number of drained bytes
	 */
	public int drainTo(@NotNull byte[] dest, int destOffset, int maxSize) {
		int s = maxSize;
		while (hasRemaining()) {
			ByteBuf buf = bufs[first];
			int remaining = buf.readRemaining();
			if (s < remaining) {
				arraycopy(buf.array(), buf.head(), dest, destOffset, s);
				buf.moveHead(s);
				return maxSize;
			} else {
				arraycopy(buf.array(), buf.head(), dest, destOffset, remaining);
				buf.recycle();
				if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
				first = next(first);
				s -= remaining;
				destOffset += remaining;
			}
		}
		return maxSize - s;
	}

	public int drainTo(@NotNull byte[] dest, int destOffset, int maxSize, @NotNull Consumer<ByteBuf> recycledBufs) {
		int s = maxSize;
		while (hasRemaining()) {
			ByteBuf buf = bufs[first];
			int remaining = buf.readRemaining();
			if (s < remaining) {
				arraycopy(buf.array(), buf.head(), dest, destOffset, s);
				buf.moveHead(s);
				return maxSize;
			} else {
				arraycopy(buf.array(), buf.head(), dest, destOffset, remaining);
				recycledBufs.accept(buf);
				buf.recycle();
				if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
				first = next(first);
				s -= remaining;
				destOffset += remaining;
			}
		}
		return maxSize - s;
	}

	/**
	 * Adds {@code maxSize} bytes from this queue to ByteBuf {@code dest} if
	 * queue contains more than {@code maxSize} bytes. Otherwise adds all
	 * bytes from queue to dest. In both cases increases queue's position to
	 * number of drained bytes.
	 *
	 * @param dest    {@code ByteBuf} for draining
	 * @param maxSize number of bytes for adding
	 * @return number of drained bytes
	 */
	public int drainTo(@NotNull ByteBuf dest, int maxSize) {
		int actualSize = drainTo(dest.array(), dest.tail(), maxSize);
		dest.moveTail(actualSize);
		return actualSize;
	}

	/**
	 * Adds as much bytes to {@code dest} as it can store. If queue doesn't
	 * contain enough bytes - adds all byte from queue.
	 * Increases queue's position to number of drained bytes.
	 *
	 * @param dest ByteBuf for draining
	 * @return number of drained bytes
	 */
	public int drainTo(@NotNull ByteBuf dest) {
		return drainTo(dest, dest.writeRemaining());
	}

	/**
	 * Copies all bytes from this queue to {@code dest}, and removes it from this queue.
	 *
	 * @param dest ByteBufQueue  for draining
	 * @return number of adding bytes
	 */
	public int drainTo(@NotNull ByteBufQueue dest) {
		int size = 0;
		while (hasRemaining()) {
			ByteBuf buf = take();
			dest.add(buf);
			size += buf.readRemaining();
		}
		return size;
	}

	/**
	 * Adds to ByteBufQueue {@code dest} {@code maxSize} bytes from this queue.
	 * If this queue doesn't contain enough bytes, adds all bytes from this queue.
	 *
	 * @param dest    {@code ByteBufQueue} for draining
	 * @param maxSize number of bytes for adding
	 * @return number of added elements
	 */
	public int drainTo(@NotNull ByteBufQueue dest, int maxSize) {
		int s = maxSize;
		while (s != 0 && hasRemaining()) {
			ByteBuf buf = takeAtMost(s);
			dest.add(buf);
			s -= buf.readRemaining();
		}
		return maxSize - s;
	}

	public interface ByteScanner {
		boolean consume(int index, byte value) throws ParseException;
	}

	public interface ByteParser<T> {
		T parse(int index, byte value) throws ParseException;
	}

	public int scanBytes(ByteScanner byteScanner) throws ParseException {
		int scanned = 0;
		for (int n = first; n != last; n = next(n)) {
			ByteBuf buf = bufs[n];
			byte[] array = buf.array();
			int tail = buf.tail();
			for (int i = buf.head(); i != tail; i++) {
				if (byteScanner.consume(scanned, array[i])) {
					return scanned;
				}
				scanned++;
			}
		}
		return -1;
	}

	public int scanBytes(int offset, ByteScanner byteScanner) throws ParseException {
		ByteBuf buf = null;
		int i = 0;
		int n;
		int scanned = 0;
		for (n = first; n != last; n = next(n)) {
			buf = bufs[n];
			int readRemaining = buf.readRemaining();
			if (offset < readRemaining) {
				i = buf.head() + offset;
				scanned += offset;
				break;
			}
			offset -= readRemaining;
			scanned += readRemaining;
		}
		while (n != last) {
			byte[] array = buf.array();
			int tail = buf.tail();
			for (; i != tail; i++) {
				if (byteScanner.consume(scanned, array[i])) {
					return scanned;
				}
				scanned++;
			}
			n = next(n);
			if (n == last) break;
			buf = bufs[n];
			i = buf.head();
		}
		return -1;
	}

	public <T> T parseBytes(ByteParser<T> byteParser) throws ParseException {
		int parsed = 0;
		for (int n = first; n != last; n = next(n)) {
			ByteBuf buf = bufs[n];
			byte[] array = buf.array();
			int tail = buf.tail();
			for (int i = buf.head(); i != tail; i++) {
				T result = byteParser.parse(parsed++, array[i]);
				if (result != null) {
					for (; first != n; first = next(first)) {
						bufs[first].recycle();
						if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
					}
					if (i == tail - 1) {
						buf.recycle();
						if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
						first = next(first);
					} else {
						buf.head(i + 1);
					}
					return result;
				}
			}
		}
		return null;
	}

	public <T> T parseBytes(ByteParser<T> byteParser, Consumer<ByteBuf> recycledBufs) throws ParseException {
		int parsed = 0;
		for (int n = first; n != last; n = next(n)) {
			ByteBuf buf = bufs[n];
			byte[] array = buf.array();
			int tail = buf.tail();
			for (int i = buf.head(); i != tail; i++) {
				T result = byteParser.parse(parsed++, array[i]);
				if (result != null) {
					for (; first != n; first = next(first)) {
						ByteBuf bufToRecycle = bufs[first];
						recycledBufs.accept(bufToRecycle);
						bufToRecycle.recycle();
						if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
					}
					if (i == tail - 1) {
						recycledBufs.accept(buf);
						buf.recycle();
						if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
						first = next(first);
					} else {
						buf.head(i + 1);
					}
					return result;
				}
			}
		}
		return null;
	}

	@NotNull
	public Iterator<ByteBuf> asIterator() {
		if (!hasRemaining()) return emptyIterator();
		ByteBufIterator iterator = new ByteBufIterator(this);
		first = last = 0;
		bufs = null;
		return iterator;
	}

	public static class ByteBufIterator implements Iterator<ByteBuf> {
		ByteBuf[] bufs;
		int first;
		final int last;

		private ByteBufIterator(@NotNull ByteBufQueue queue) {
			bufs = queue.bufs;
			first = queue.first;
			last = queue.last;
		}

		@Override
		public boolean hasNext() {
			return first != last;
		}

		@Override
		@NotNull
		public ByteBuf next() {
			ByteBuf buf = bufs[first];
			if (NULLIFY_ON_TAKE_OUT) bufs[first] = null;
			first = (first + 1) % bufs.length;
			return buf;
		}

		public boolean isRecycled() {
			return bufs == null;
		}
	}

	public boolean isRecycled() {
		return bufs == null;
	}

	/**
	 * Recycles all ByteBufs of this queue and sets
	 * {@code first} and {@code last} indexes to 0.
	 */
	@Override
	public void recycle() {
		for (; first != last; first = next(first)) {
			bufs[first].recycle();
		}
		bufs = null;
	}

	@Override
	public String toString() {
		return "bufs:" + remainingBufs() + " bytes:" + remainingBytes();
	}
}
