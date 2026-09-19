package com.fileark.core.orchestrator;

import java.nio.file.Path;

/** 扫描编排的事件回调；cancelled() 为协作式取消检查点。 */
public interface ScanEventListener {

    ScanEventListener NOOP = new ScanEventListener() {
    };

    default void onPhase(String phase) {
    }

    default void onProgress(long count, String current) {
    }

    default void onSuspectedGroups(int total) {
    }

    default void onGroupConfirmed(int index, int total, int members) {
    }

    default boolean cancelled() {
        return false;
    }
}
