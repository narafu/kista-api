package com.kista.adapter.out.sse;

import com.kista.application.event.UserApprovedEvent;
import com.kista.application.event.UserRejectedEvent;
import com.kista.domain.model.order.TradeEvent;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.RealtimeNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class SseEmitterRegistry implements RealtimeNotificationPort {

    private final ConcurrentHashMap<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final TradeSseEmitterRegistry tradeSseEmitterRegistry; // 매매 알림 SSE 레지스트리

    // 사용자 SSE 연결 등록 — AuthController에서 호출
    public SseEmitter connect(UUID userId) {
        SseEmitter emitter = new SseEmitter(0L); // 타임아웃 없음
        emitters.put(userId, emitter);
        emitter.onCompletion(() -> emitters.remove(userId, emitter));
        emitter.onTimeout(() -> emitters.remove(userId, emitter));
        emitter.onError(e -> emitters.remove(userId, emitter));
        return emitter;
    }

    @Override
    public void notifyStatusChange(UUID userId, User.UserStatus status) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name("status").data(status.name()));
            emitter.complete();
        } catch (IOException e) {
            emitters.remove(userId, emitter);
        }
    }

    @Override
    public void notifyTrade(UUID userId, TradeEvent event) {
        tradeSseEmitterRegistry.send(userId, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserApproved(UserApprovedEvent event) {
        notifyStatusChange(event.user().id(), User.UserStatus.ACTIVE);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRejected(UserRejectedEvent event) {
        notifyStatusChange(event.user().id(), User.UserStatus.REJECTED);
    }
}
