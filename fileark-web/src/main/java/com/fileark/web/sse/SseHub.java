package com.fileark.web.sse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** SSE 订阅注册表：扫描进度按 scanId 广播给所有订阅者。 */
@Component
public class SseHub {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(long scanId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = emitters.computeIfAbsent(scanId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));
        return emitter;
    }

    public void send(long scanId, String event, Object data) {
        List<SseEmitter> list = emitters.get(scanId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);
            }
        }
    }

    public void complete(long scanId) {
        List<SseEmitter> list = emitters.remove(scanId);
        if (list != null) {
            list.forEach(SseEmitter::complete);
        }
    }
}
