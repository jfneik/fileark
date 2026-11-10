package com.fileark.web.api;

import com.fileark.core.dedup.KeepStrategy;
import jakarta.validation.constraints.NotBlank;

/** 计划创建/预览请求。kind=DUPLICATE_CLEANUP 时 refId 为重复组 id；
 *  kind=RULE_APPLY 时 refId 为规则 id，targetDir 为整理目标目录。 */
public record CreatePlanRequest(@NotBlank String kind, Long refId, String keepStrategy,
                                String targetDir) {

    public KeepStrategy keepStrategyOr(KeepStrategy fallback) {
        try {
            return keepStrategy == null ? fallback : KeepStrategy.valueOf(keepStrategy);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
