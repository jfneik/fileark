package com.fileark.web.api;

import com.fileark.core.model.FileMeta;

import java.util.List;

/** 重复组成员条目视图：Path/Instant 一律转字符串，避免序列化歧义。 */
public record FileEntryDto(long id, String path, long size, String mtime,
                           String mimeType, String category) {

    static FileEntryDto of(FileMeta f) {
        return new FileEntryDto(f.id(), f.path().toString(), f.size(),
                f.mtime() == null ? null : f.mtime().toString(), f.mimeType(),
                f.category() == null ? null : f.category().name());
    }
}
