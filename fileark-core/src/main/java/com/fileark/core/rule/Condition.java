package com.fileark.core.rule;

public record Condition(FieldType field, Operator op, String value) {
}
