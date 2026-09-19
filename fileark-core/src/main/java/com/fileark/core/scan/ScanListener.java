package com.fileark.core.scan;

import java.nio.file.Path;

/** 扫描进度回调；cancelled() 是协作式取消的检查点，由外部持有取消标志。 */
public interface ScanListener {

    ScanListener NOOP = new ScanListener() {
    };

    default void onProgress(Path current, int count) {
    }

    default boolean cancelled() {
        return false;
    }
}
