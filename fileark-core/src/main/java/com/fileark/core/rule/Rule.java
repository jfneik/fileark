package com.fileark.core.rule;

import java.util.List;

public record Rule(long id, String name, int priority, boolean enabled,
                   List<Condition> conditions, RuleAction action) {
}
