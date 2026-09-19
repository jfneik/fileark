package com.fileark.core.plan;

import com.fileark.core.dedup.DuplicateDetector;
import com.fileark.core.dedup.KeepStrategy;
import com.fileark.core.model.FileMeta;
import com.fileark.core.rule.ActionType;
import com.fileark.core.rule.PathTemplate;
import com.fileark.core.rule.Rule;
import com.fileark.core.rule.RuleAction;
import com.fileark.core.rule.RuleEngine;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 计划构建器。dry-run 与真实执行共用同一构建路径，
 * 差别只在「是否落库、是否写盘」——保证预览即所得。
 */
public final class Planner {

    private final RuleEngine ruleEngine;
    private final Clock clock;

    public Planner(RuleEngine ruleEngine, Clock clock) {
        this.ruleEngine = ruleEngine;
        this.clock = clock;
    }

    /** 重复组清理计划：除保留项外全部移入安全区。 */
    public Plan fromDuplicateGroup(DuplicateDetector.ConfirmedGroup group, KeepStrategy strategy,
                                   String description) {
        List<FileMeta> members = group.members();
        FileMeta keep = strategy.pick(members);
        List<Operation> ops = members.stream()
                .filter(f -> f.id() != keep.id())
                .sorted(Comparator.comparing(f -> f.path().toString()))
                .map(f -> Operation.pending(OperationType.DELETE_TO_QUARANTINE, f.path(), null,
                        f.size(), f.mtime()))
                .toList();
        return new Plan(0, PlanStatus.PLANNED, description, null, ops);
    }

    /** 规则整理计划：对一组文件逐个求值规则引擎，命中即产生操作。 */
    public Plan fromRules(List<FileMeta> files, List<Rule> rules, Path baseDir, String description) {
        List<Operation> ops = new ArrayList<>();
        for (FileMeta f : files) {
            Optional<RuleAction> match = ruleEngine.firstMatch(f, rules);
            if (match.isEmpty()) {
                continue;
            }
            RuleAction action = match.get();
            Operation op = switch (action.type()) {
                case MOVE -> {
                    Path dstDir = PathTemplate.renderDir(action.targetTemplate(), baseDir, f, clock);
                    yield Operation.pending(OperationType.MOVE, f.path(),
                            dstDir.resolve(f.path().getFileName()), f.size(), f.mtime());
                }
                case RENAME -> {
                    String newName = PathTemplate.renderName(action.targetTemplate(), f, clock);
                    yield Operation.pending(OperationType.RENAME, f.path(),
                            f.path().resolveSibling(newName), f.size(), f.mtime());
                }
                // TAG 仅做标记、不产生文件操作：跳过而不是让整个计划构建失败
                case TAG -> null;
            };
            if (op != null && (op.dst() == null || !op.dst().equals(op.src()))) {
                ops.add(op);
            }
        }
        return new Plan(0, PlanStatus.PLANNED, description, null, ops);
    }
}
