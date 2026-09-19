package com.fileark.web.service;

import com.fileark.core.orchestrator.ScanEventListener;
import com.fileark.web.sse.SseHub;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** 编排事件 → SSE 的桥接；每个扫描一个监听器，取消标志由外部持有。 */
@Component
public record SseBridge(SseHub hub) {

    public ScanEventListener listenerFor(long scanId, AtomicBoolean cancelFlag) {
        return new ScanEventListener() {
            @Override
            public void onPhase(String phase) {
                hub.send(scanId, "phase", phase);
            }

            @Override
            public void onProgress(long count, String current) {
                hub.send(scanId, "progress", new Progress(count, current));
            }

            @Override
            public void onSuspectedGroups(int total) {
                hub.send(scanId, "suspected", total);
            }

            @Override
            public void onGroupConfirmed(int index, int total, int members) {
                hub.send(scanId, "confirmed", new Confirmed(index, total, members));
            }

            @Override
            public boolean cancelled() {
                return cancelFlag.get();
            }
        };
    }

    public void complete(long scanId) {
        hub.complete(scanId);
    }

    public record Progress(long count, String current) {
    }

    public record Confirmed(int index, int total, int members) {
    }
}
