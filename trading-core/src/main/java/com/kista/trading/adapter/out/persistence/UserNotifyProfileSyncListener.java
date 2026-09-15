package com.kista.trading.adapter.out.persistence;

import tools.jackson.databind.ObjectMapper;
import com.kista.platform.crypto.AesCryptoService;
import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

// kista.user_notify_profile 복제본 동기화 — user 모듈이 발행한 이벤트만 받아 upsert/delete 한다.
// DB 분리(4단계) 전까지는 같은 DB 위 Modulith EPR 경유(실패 시 재기동 때 재시도).
// fallbackExecution=true는 안전망이다 — 현재 발행 지점은 전부 트랜잭션 안이지만(login()의 ADMIN
// 승격도 AdminSeedPromoter로 분리됨), 누군가 트랜잭션 밖에서 발행을 추가하면 이 플래그가 없을 때
// 이벤트가 조용히 버려진다. 켜두면 대신 인라인 동기 실행이라 실패가 발행자까지 전파된다 —
// 복제본 동기화는 조용한 유실보다 시끄러운 실패가 낫다는 판단.
@Component
@RequiredArgsConstructor
class UserNotifyProfileSyncListener {

    private final UserNotifyProfileJpaRepository repository;
    private final ObjectMapper objectMapper;
    private final AesCryptoService crypto; // telegramBotToken 암호화 — persistence 경계에서만 사용

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onProfileChanged(UserNotifyProfileChangedEvent event) {
        // 직렬화 실패는 잡지 않는다 — 예외를 삼키면 복제본이 조용히 낡고, 그대로 던지면 EPR이
        // incomplete로 기록해 재기동 시 재시도한다(Map<NotificationType,Boolean>이라 실패 가능성 자체가 없음)
        String prefsJson = objectMapper.writeValueAsString(event.notificationPrefs());
        // persistence 경계에서 telegramBotToken 암호화 — null이면 그대로 null 유지
        String encryptedToken = event.telegramBotToken() == null ? null : crypto.encrypt(event.telegramBotToken());
        // @Id 할당식이라 save()가 곧 upsert
        repository.save(new UserNotifyProfileEntity(
                event.userId(), prefsJson, event.balanceCheckEnabled(), event.active(),
                encryptedToken, event.chatId(), Instant.now()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserDeleted(UserDeletedEvent event) {
        repository.deleteById(event.userId());
    }
}
