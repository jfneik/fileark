package com.fileark.web.api;

import com.fileark.core.dedup.KeepStrategy;
import com.fileark.core.model.FileMeta;
import com.fileark.core.plan.Operation;
import com.fileark.core.plan.Plan;
import com.fileark.core.port.DuplicateStore;
import com.fileark.core.port.PlanStore;

import java.util.List;

/** web 层 DTO 及 core ↔ JSON 映射（Path/Instant 一律转字符串，避免序列化歧义）。 */
public final class Dto {

    private Dto() {
    }

    public record FileEntryDto(long id, String path, long size, String mtime,
                               String mimeType, String category) {
        static FileEntryDto of(FileMeta f) {
            return new FileEntryDto(f.id(), f.path().toString(), f.size(),
                    f.mtime() == null ? null : f.mtime().toString(), f.mimeType(),
                    f.category() == null ? null : f.category().name());
        }
    }

    public record GroupDto(long id, String hash, String status, long totalSize,
                           List<FileEntryDto> members) {
        static GroupDto of(DuplicateStore.GroupView g) {
            return new GroupDto(g.id(), g.hash(), g.status().name(), g.totalSize(),
                    g.members().stream().map(FileEntryDto::of).toList());
        }
    }

    public record OperationDto(long id, String type, String src, String dst,
                               long expectedSize, String expectedMtime, String status, String error) {
        static OperationDto of(Operation o) {
            return new OperationDto(o.id(), o.type().name(), o.src().toString(),
                    o.dst() == null ? null : o.dst().toString(), o.expectedSize(),
                    o.expectedMtime() == null ? null : o.expectedMtime().toString(),
                    o.status().name(), o.error());
        }
    }

    public record PlanDto(long id, String status, String description, String error,
                          List<OperationDto> operations) {
        static PlanDto of(Plan p) {
            return new PlanDto(p.id(), p.status().name(), p.description(), p.error(),
                    p.operations().stream().map(OperationDto::of).toList());
        }
    }

    public record PlanRowDto(long id, String status, String description, String error,
                             String createdAt, String executedAt, String rolledBackAt) {
        static PlanRowDto of(PlanStore.PlanRow r) {
            return new PlanRowDto(r.id(), r.status().name(), r.description(), r.error(),
                    r.createdAt(), r.executedAt(), r.rolledBackAt());
        }
    }

    /** 计划创建/预览请求。kind=DUPLICATE_CLEANUP 时 refId 为重复组 id；
     *  kind=RULE_APPLY 时 refId 为规则 id，targetDir 为整理目标目录。 */
    public record CreatePlanRequest(String kind, Long refId, String keepStrategy, String targetDir) {

        public KeepStrategy keepStrategyOr(KeepStrategy fallback) {
            try {
                return keepStrategy == null ? fallback : KeepStrategy.valueOf(keepStrategy);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }
}
