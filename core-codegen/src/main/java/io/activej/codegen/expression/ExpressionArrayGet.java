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

package io.activej.codegen.expression;

import io.activej.codegen.Context;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

final class ExpressionArrayGet implements Expression {
	private final Expression array;
	private final Expression index;

	ExpressionArrayGet(Expression array, Expression index) {
		this.array = array;
		this.index = index;
	}

	@Override
	public Type load(Context ctx) {
		GeneratorAdapter g = ctx.getGeneratorAdapter();
		Type arrayType = array.load(ctx);
		if (arrayType == null) {
			throw new IllegalArgumentException("Cannot access a 'throw' expression as an array");
		}
		Type type = Type.getType(arrayType.getDescriptor().substring(1));
		if (index.load(ctx) == null) {
			throw new IllegalArgumentException("Cannot use a 'throw' expression as an index of an array");
		}
		g.arrayLoad(type);
		return type;
	}
}
