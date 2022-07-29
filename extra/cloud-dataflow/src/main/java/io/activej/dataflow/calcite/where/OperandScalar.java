package io.activej.dataflow.calcite.where;

import io.activej.dataflow.calcite.Value;
import io.activej.record.Record;
import io.activej.record.RecordScheme;
import org.apache.calcite.rex.RexDynamicParam;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class OperandScalar implements Operand {

	private final Value value;

	public OperandScalar(Value value) {
		this.value = value;
	}

	@Override
	public <T> T getValue(Record record) {
		//noinspection unchecked
		return (T) value.getValue();
	}

	@Override
	public Type getFieldType(RecordScheme original) {
		return value.getType();
	}

	@Override
	public String getFieldName(RecordScheme original) {
		return value.isMaterialized() ? Objects.toString(value.getValue()) : "?";
	}

	@Override
	public Operand materialize(List<Object> params) {
		return new OperandScalar(value.materialize(params));
	}

	@Override
	public List<RexDynamicParam> getParams() {
		return value.isMaterialized() ?
				Collections.emptyList() :
				Collections.singletonList(value.getDynamicParam());
	}

	public Value getValue() {
		return value;
	}

	@Override
	public String toString() {
		return "OperandScalar[" +
				"value=" + value + ']';
	}

}
