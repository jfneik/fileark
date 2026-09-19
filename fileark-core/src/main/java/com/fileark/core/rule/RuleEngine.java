package com.fileark.core.rule;

import com.fileark.core.model.FileMeta;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 规则求值：按 priority 升序遍历，首条命中即生效（短路，保证可预测性）。
 * 条件之间为 AND。
 */
public final class RuleEngine {

    private final Clock clock;

    public RuleEngine(Clock clock) {
        this.clock = clock;
    }

    public Optional<RuleAction> firstMatch(FileMeta file, List<Rule> rules) {
        return rules.stream()
                .filter(Rule::enabled)
                .sorted(Comparator.comparingInt(Rule::priority))
                .filter(r -> r.conditions().stream().allMatch(c -> matches(c, file)))
                .map(Rule::action)
                .findFirst();
    }

    boolean matches(Condition c, FileMeta f) {
        return switch (c.field()) {
            case TYPE -> compare(c, f.category().name());
            case SIZE_BYTES -> compare(c, Long.toString(f.size()));
            case AGE_DAYS -> compare(c, Long.toString(ageDays(f)));
            case NAME_REGEX_FIELD -> compare(c, f.path().getFileName().toString());
            case SRC_PREFIX -> compare(c, f.path().toString());
        };
    }

    private long ageDays(FileMeta f) {
        return Duration.between(f.mtime(), clock.instant()).toDays();
    }

    private boolean compare(Condition c, String actual) {
        String expected = c.value();
        return switch (c.op()) {
            case EQ -> actual.equals(expected);
            case NEQ -> !actual.equals(expected);
            case MATCHES -> Pattern.compile(expected).matcher(actual).find();
            case GT, GTE, LT, LTE -> compareNumeric(c.op(), actual, expected);
        };
    }

    private boolean compareNumeric(Operator op, String actual, String expected) {
        try {
            double a = Double.parseDouble(actual);
            double e = Double.parseDouble(expected);
            return switch (op) {
                case GT -> a > e;
                case GTE -> a >= e;
                case LT -> a < e;
                case LTE -> a <= e;
                default -> false;
            };
        } catch (NumberFormatException ex) {
            int cmp = actual.compareTo(expected);
            return switch (op) {
                case GT -> cmp > 0;
                case GTE -> cmp >= 0;
                case LT -> cmp < 0;
                case LTE -> cmp <= 0;
                default -> false;
            };
        }
    }
}
