package com.fileark.core.plan;

import java.util.List;

/** 操作计划。dry-run 产出的草稿 id=0 且不落库。 */
public record Plan(long id, PlanStatus status, String description, String error,
                   List<Operation> operations) {
}
