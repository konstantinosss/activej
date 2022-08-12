/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.activej.dataflow.calcite;

import io.activej.codegen.util.Primitives;
import io.activej.common.exception.ToDoException;
import io.activej.serializer.BinarySerializer;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.impl.AbstractTable;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DataflowTable<T> extends AbstractTable {
	private final Class<T> type;
	private final RecordFunction<T> recordFunction;
	private final BinarySerializer<RecordFunction<T>> recordFunctionSerializer;

	private RelDataType relDataType;

	private DataflowTable(Class<T> type, RecordFunction<T> recordFunction, BinarySerializer<RecordFunction<T>> recordFunctionSerializer) {
		this.type = type;
		this.recordFunction = recordFunction;
		this.recordFunctionSerializer = recordFunctionSerializer;
	}

	public static <T> DataflowTable<T> create(Class<T> cls, RecordFunction<T> recordFunction, BinarySerializer<RecordFunction<T>> recordFunctionSerializer) {
		return new DataflowTable<>(cls, recordFunction, recordFunctionSerializer);
	}

	@Override
	public RelDataType getRowType(RelDataTypeFactory typeFactory) {
		if (relDataType == null) {
			relDataType = toRowType((JavaTypeFactory) typeFactory, type);
		}
		return relDataType;
	}

	public Class<T> getType() {
		return type;
	}

	public RecordFunction<T> getRecordFunction() {
		return recordFunction;
	}

	public BinarySerializer<RecordFunction<T>> getRecordFunctionSerializer() {
		return recordFunctionSerializer;
	}

	private static RelDataType toRowType(JavaTypeFactory typeFactory, Type type) {
		if (type instanceof Class<?> cls) {
			if (cls.isPrimitive() || cls.isEnum() || Primitives.isWrapperType(cls) || cls == String.class) {
				return typeFactory.createJavaType(cls);
			}

			if (cls.isRecord()) {
				RecordComponent[] recordComponents = cls.getRecordComponents();
				List<RelDataType> types = new ArrayList<>(recordComponents.length);
				List<String> names = new ArrayList<>(recordComponents.length);

				for (RecordComponent recordComponent : recordComponents) {
					names.add(recordComponent.getName());
					types.add(toRowType(typeFactory, recordComponent.getGenericType()));
				}

				return typeFactory.createStructType(types, names);
			}

			if (cls.isArray()) {
				RelDataType elementType = toRowType(typeFactory, cls.getComponentType());
				return typeFactory.createArrayType(elementType, -1);
			}

			List<RelDataType> types = new ArrayList<>();
			List<String> names = new ArrayList<>();

			// TODO: either require RelDataType on table creation or use order from @Serialize annotation?
			for (Field field : cls.getFields()) {
				if (Modifier.isStatic(field.getModifiers())) continue;

				names.add(field.getName());
				types.add(toRowType(typeFactory, field.getGenericType()));
			}
			return typeFactory.createStructType(types, names);
		}

		if (type instanceof ParameterizedType parameterizedType) {
			Type rawType = parameterizedType.getRawType();
			if (rawType == List.class) {
				RelDataType elementType = toRowType(typeFactory, parameterizedType.getActualTypeArguments()[0]);
				return typeFactory.createArrayType(elementType, -1);
			}
			if (rawType == Map.class) {
				Type[] typeArguments = parameterizedType.getActualTypeArguments();
				RelDataType keyType = toRowType(typeFactory, typeArguments[0]);
				RelDataType valueType = toRowType(typeFactory, typeArguments[1]);
				return typeFactory.createMapType(keyType, valueType);
			}
		}

		if (type instanceof GenericArrayType genericArrayType) {
			RelDataType elementType = toRowType(typeFactory, genericArrayType.getGenericComponentType());
			return typeFactory.createArrayType(elementType, -1);
		}

		throw new ToDoException();
	}
}
