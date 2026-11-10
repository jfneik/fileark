package com.fileark.core.port;

import com.fileark.core.plan.Operation;
import com.fileark.core.plan.OperationStatus;
import com.fileark.core.plan.PlanStatus;

import java.util.List;
import java.util.Optional;

public interface PlanStore {

    /** 持久化草稿计划及其 PENDING 操作，回填 id 后返回。 */
    long create(PlanStatus status, String description, List<Operation> ops);

    Optional<PlanRow> findPlan(long id);

    List<PlanRow> listPlans();

    List<Operation> operationsOf(long planId);

    Optional<PlanRow> findExecutingPlan();

    void updatePlanStatus(long planId, PlanStatus status, String error);

    /** 条件状态迁移（CAS）：仅当当前状态为 expectedFrom 时更新为 to，返回是否抢占成功。 */
    boolean tryUpdatePlanStatus(long planId, PlanStatus expectedFrom, PlanStatus to, String error);

    void updateOperationStatus(long planId, long operationId, OperationStatus status, String error);

    record PlanRow(long id, PlanStatus status, String description, String error,
                   String createdAt, String executedAt, String rolledBackAt) {
    }
}
