package com.fileark.web.api;

import com.fileark.core.port.PlanStore;

/** 计划列表行视图（不含操作明细）。 */
public record PlanRowDto(long id, String status, String description, String error,
                         String createdAt, String executedAt, String rolledBackAt) {

    static PlanRowDto of(PlanStore.PlanRow r) {
        return new PlanRowDto(r.id(), r.status().name(), r.description(), r.error(),
                r.createdAt(), r.executedAt(), r.rolledBackAt());
    }
}
