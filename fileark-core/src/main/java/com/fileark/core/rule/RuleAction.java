package com.fileark.core.rule;

/**
 * 动作定义。MOVE 时 targetTemplate 为相对 baseDir 的目录模板；
 * RENAME 时为新文件名模板（不含路径）。
 */
public record RuleAction(ActionType type, String targetTemplate, String tag) {
}
