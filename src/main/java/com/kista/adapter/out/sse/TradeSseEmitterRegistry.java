package com.kista.adapter.out.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.model.TradeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// 매매 알림 SSE emitter 레지스트리 — status SSE와 분리된 Map으로 관리
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeSseEmitterRegistry {

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper; // Spring Boot 자동 등록 빈

    // 30분 timeout (매매 시간 커버)
    public SseEmitter connect(UUID userId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // 연결 해제 시 emitter 제거
        Runnable cleanup = () -> emitters.getOrDefault(userId, List.of()).remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // 초기 연결 확인 ping
        try {
            emitter.send(SseEmitter.event().name("ping").data("connected"));
        } catch (IOException e) {
            log.warn("Trade SSE initial ping failed userId={}", userId);
        }
        return emitter;
    }

    // 특정 사용자에게 매매 이벤트 전송
    public void send(UUID userId, TradeEvent event) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null || userEmitters.isEmpty()) return;

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("Trade SSE serialize error", e);
            return;
        }

        // 전송 실패한 emitter는 즉시 제거
        String finalPayload = payload;
        userEmitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("trade").data(finalPayload));
                return false;
            } catch (IOException ex) {
                return true;
            }
        });
    }
}
