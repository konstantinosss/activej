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

package io.activej.async.function;

import io.activej.common.function.BiFunctionEx;
import io.activej.promise.Promise;
import org.jetbrains.annotations.NotNull;

import static io.activej.common.exception.FatalErrorHandlers.handleError;

@FunctionalInterface
public interface AsyncBiFunction<T, U, R> {
	Promise<R> apply(T t, U u);

	/**
	 * Wraps a {@link BiFunctionEx} interface.
	 *
	 * @param function a {@link BiFunctionEx}
	 * @return {@link AsyncBiFunction} that works on top of {@link BiFunctionEx} interface
	 */
	static <T, U, R> @NotNull AsyncBiFunction<T, U, R> of(@NotNull BiFunctionEx<? super T, ? super U, ? extends R> function) {
		return (t, u) -> {
			try {
				return Promise.of(function.apply(t, u));
			} catch (Exception e) {
				handleError(e, function);
				return Promise.ofException(e);
			}
		};
	}
}
