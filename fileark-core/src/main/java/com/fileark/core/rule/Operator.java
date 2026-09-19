package com.fileark.core.rule;

/** 条件操作符；数值比较对可解析值生效，否则退化为字符串比较。 */
public enum Operator {
    EQ, NEQ, GT, GTE, LT, LTE, MATCHES
}
