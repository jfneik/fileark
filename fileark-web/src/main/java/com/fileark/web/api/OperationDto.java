package com.fileark.web.api;

import com.fileark.core.plan.Operation;

/** 计划内单条操作的视图。 */
public record OperationDto(long id, String type, String src, String dst,
                           long expectedSize, String expectedMtime, String status, String error) {

    static OperationDto of(Operation o) {
        return new OperationDto(o.id(), o.type().name(), o.src().toString(),
                o.dst() == null ? null : o.dst().toString(), o.expectedSize(),
                o.expectedMtime() == null ? null : o.expectedMtime().toString(),
                o.status().name(), o.error());
    }
}
