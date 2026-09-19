package com.fileark.core.rule;

import com.fileark.core.exception.ErrorCode;
import com.fileark.core.exception.FileArkException;
import com.fileark.core.model.FileMeta;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 目标路径模板渲染。占位符：{type} {yyyy} {MM} {dd} {name} {ext}。
 * 产物不允许包含 ".."，且只允许相对模板（防目录穿越）。
 */
public final class PathTemplate {

    private static final DateTimeFormatter YYYY = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MM = DateTimeFormatter.ofPattern("MM");
    private static final DateTimeFormatter DD = DateTimeFormatter.ofPattern("dd");

    private PathTemplate() {
    }

    /** 渲染目录模板（MOVE 用），返回相对 baseDir 的目标目录。 */
    public static Path renderDir(String template, Path baseDir, FileMeta file, Clock clock) {
        String rel = render(template, baseDir, file, clock);
        return baseDir.resolve(rel);
    }

    /** 渲染文件名模板（RENAME 用），返回同目录新文件名。 */
    public static String renderName(String template, FileMeta file, Clock clock) {
        String name = file.path().getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return fill(template, file, clock)
                .replace("{name}", base)
                .replace("{ext}", ext);
    }

    private static String render(String template, Path baseDir, FileMeta file, Clock clock) {
        String rel = fill(template, file, clock)
                .replace("{name}", fileName(file))
                .replace("{ext}", ext(file));
        Path resolved = baseDir.resolve(rel).normalize();
        if (!resolved.startsWith(baseDir) || resolved.equals(baseDir)) {
            throw new FileArkException(ErrorCode.INVALID_PATH,
                    "模板渲染结果越界: " + template + " → " + rel);
        }
        return baseDir.relativize(resolved).toString();
    }

    private static String fill(String template, FileMeta file, Clock clock) {
        LocalDateTime t = LocalDateTime.now(clock);
        return template
                .replace("{type}", file.category().name().toLowerCase())
                .replace("{yyyy}", t.format(YYYY))
                .replace("{MM}", t.format(MM))
                .replace("{dd}", t.format(DD));
    }

    private static String fileName(FileMeta file) {
        String name = file.path().getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String ext(FileMeta file) {
        String name = file.path().getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }
}
