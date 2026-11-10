package com.fileark.core.rule;

import com.fileark.core.model.Category;
import com.fileark.core.model.FileMeta;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));

    private final RuleEngine engine = new RuleEngine(CLOCK);

    private FileMeta file(String name, Category category, long size, Instant mtime) {
        return new FileMeta(0, Path.of("/data/downloads/" + name), size, mtime,
                "application/octet-stream", category);
    }

    @Test
    void firstMatchWinsByPriority() {
        Rule low = new Rule(1, "低优先级", 10, true,
                List.of(new Condition(FieldType.TYPE, Operator.EQ, "IMAGE")),
                new RuleAction(ActionType.MOVE, "{type}", null));
        Rule high = new Rule(2, "高优先级", 1, true,
                List.of(new Condition(FieldType.NAME_REGEX_FIELD, Operator.MATCHES, "cat")),
                new RuleAction(ActionType.MOVE, "{type}/cats", null));
        FileMeta f = file("cat.jpg", Category.IMAGE, 100, NOW.minusSeconds(60));

        Optional<RuleAction> match = engine.firstMatch(f, List.of(low, high));
        assertThat(match).isPresent();
        assertThat(match.get().targetTemplate()).isEqualTo("{type}/cats");
    }

    @Test
    void disabledRulesAreSkipped() {
        Rule disabled = new Rule(1, "停用", 1, false,
                List.of(new Condition(FieldType.TYPE, Operator.EQ, "IMAGE")),
                new RuleAction(ActionType.MOVE, "{type}", null));
        assertThat(engine.firstMatch(file("a.png", Category.IMAGE, 1, NOW), List.of(disabled)))
                .isEmpty();
    }

    @Test
    void ageDaysCondition() {
        Rule archive = new Rule(1, "归档旧文件", 1, true,
                List.of(new Condition(FieldType.AGE_DAYS, Operator.GTE, "30")),
                new RuleAction(ActionType.MOVE, "archive", null));
        FileMeta old = file("old.zip", Category.ARCHIVE, 10, NOW.minusSeconds(31L * 86400));
        FileMeta fresh = file("new.zip", Category.ARCHIVE, 10, NOW.minusSeconds(86400));
        assertThat(engine.firstMatch(old, List.of(archive))).isPresent();
        assertThat(engine.firstMatch(fresh, List.of(archive))).isEmpty();
    }

    @Test
    void sizeCondition() {
        Rule big = new Rule(1, "大文件", 1, true,
                List.of(new Condition(FieldType.SIZE_BYTES, Operator.GT, "1048576")),
                new RuleAction(ActionType.MOVE, "big", null));
        assertThat(engine.firstMatch(file("big.iso", Category.OTHER, 2_097_152, NOW), List.of(big)))
                .isPresent();
        assertThat(engine.firstMatch(file("small.txt", Category.OTHER, 1024, NOW), List.of(big)))
                .isEmpty();
    }

    @Test
    void allConditionsMustHold() {
        Rule both = new Rule(1, "组合条件", 1, true,
                List.of(new Condition(FieldType.TYPE, Operator.EQ, "VIDEO"),
                        new Condition(FieldType.SIZE_BYTES, Operator.GTE, "100")),
                new RuleAction(ActionType.MOVE, "video", null));
        assertThat(engine.firstMatch(file("m.mp4", Category.VIDEO, 150, NOW), List.of(both))).isPresent();
        assertThat(engine.firstMatch(file("m.mp4", Category.VIDEO, 50, NOW), List.of(both))).isEmpty();
        assertThat(engine.firstMatch(file("m.txt", Category.DOCUMENT, 150, NOW), List.of(both))).isEmpty();
    }

    @Test
    void pathTemplateRendersTimeAndCategory() {
        FileMeta f = file("report.pdf", Category.DOCUMENT, 10, NOW);
        Path dir = PathTemplate.renderDir("{type}/{yyyy}/{MM}", Path.of("/base"), f, CLOCK);
        // 用 Path 比较而非字符串：Windows 下分隔符是 \，macOS/Linux 是 /
        assertThat(dir).isEqualTo(Path.of("/base/document/2026/09"));
    }

    @Test
    void pathTemplateRejectsTraversal() {
        FileMeta f = file("x.txt", Category.OTHER, 1, NOW);
        org.junit.jupiter.api.Assertions.assertThrows(
                com.fileark.core.exception.FileArkException.class,
                () -> PathTemplate.renderDir("../escape", Path.of("/base"), f, CLOCK));
    }

    @Test
    void nameTemplateRendersParts() {
        FileMeta f = file("photo.JPG", Category.IMAGE, 1, NOW);
        assertThat(PathTemplate.renderName("{name}_整理{ext}", f, CLOCK)).isEqualTo("photo_整理.JPG");
    }
}
