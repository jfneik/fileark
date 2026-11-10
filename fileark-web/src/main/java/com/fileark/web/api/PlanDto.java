package com.fileark.web.api;

import com.fileark.core.plan.Plan;

import java.util.List;

/** 计划完整视图（含操作明细）。 */
public record PlanDto(long id, String status, String description, String error,
                      List<OperationDto> operations) {

    static PlanDto of(Plan p) {
        return new PlanDto(p.id(), p.status().name(), p.description(), p.error(),
                p.operations().stream().map(OperationDto::of).toList());
    }
}
