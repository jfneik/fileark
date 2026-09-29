package com.fileark.core.plan;

/**
 * 计划状态机：
 * PLANNED → EXECUTING → EXECUTED（可 rollback → ROLLED_BACK）
 *                     ├→ FAILED（已成功条目自动逆序回滚；终态，重试 = 新建计划）
 *                     └→ 进程崩溃 → 重启恢复（RecoveryService）→ ROLLED_BACK / NEEDS_REVIEW
 * NEEDS_REVIEW --人工确认--> ACKNOWLEDGED（终态：用户已核对矛盾条目并处理）
 */
public enum PlanStatus {
    PLANNED, EXECUTING, EXECUTED, FAILED, ROLLED_BACK, NEEDS_REVIEW, ACKNOWLEDGED
}
