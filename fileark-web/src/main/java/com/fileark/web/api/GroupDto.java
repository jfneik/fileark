package com.fileark.web.api;

import com.fileark.core.port.DuplicateStore;

import java.util.List;

/** 重复组视图。 */
public record GroupDto(long id, String hash, String status, long totalSize,
                       List<FileEntryDto> members) {

    static GroupDto of(DuplicateStore.GroupView g) {
        return new GroupDto(g.id(), g.hash(), g.status().name(), g.totalSize(),
                g.members().stream().map(FileEntryDto::of).toList());
    }
}
