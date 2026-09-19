package com.fileark.core.model;

import org.apache.tika.Tika;

import java.io.IOException;
import java.nio.file.Path;

/** 基于魔数的类型识别（Tika facade）。 */
public final class FileClassify {

    private static final Tika TIKA = new Tika();

    private FileClassify() {
    }

    public record Detected(String mimeType, Category category) {
    }

    public static Detected detect(Path file) {
        String mime;
        try {
            mime = TIKA.detect(file);
        } catch (IOException e) {
            mime = null;
        }
        return new Detected(mime, Category.fromMime(mime));
    }
}
