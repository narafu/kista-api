package com.kista.tradingcore.trading.adapter.out.persistence;

import tools.jackson.databind.ObjectMapper;
import com.kista.shared.kernel.NotificationChannel;
import com.kista.shared.kernel.UserNotifyProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Optional;

// trading.user_notify_profile 복제본 동기화 — user 모듈이 발행한 프로필 변경 이벤트를 받아 upsert 한다(탈퇴는 UserTradingDataEraser가 tombstone 처리).
// root가 Redis Stream으로 보낸 이벤트를 UserEventStreamBridge가 로컬 재발행한다 — 실패하면 publication이 FAILED로 남아 UserEventResubmitScheduler가 재발행.
// updated_at은 스냅샷 시각(occurredAt)이라 버전 역할을 한다 — 옛 스냅샷 재시도는 건너뛴다.
// fallbackExecution=true는 안전망이다 — 현재 발행 지점은 전부 트랜잭션 안이지만(login()의 ADMIN
// 승격도 AdminSeedPromoter로 분리됨), 누군가 트랜잭션 밖에서 발행을 추가하면 이 플래그가 없을 때
// 이벤트가 조용히 버려진다. 켜두면 대신 인라인 동기 실행이라 실패가 발행자까지 전파된다 —
// 복제본 동기화는 조용한 유실보다 시끄러운 실패가 낫다는 판단.
@Slf4j
@Component
@RequiredArgsConstructor
class UserNotifyProfileSyncListener {

    private final UserNotifyProfileJpaRepository repository;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onProfileChanged(UserNotifyProfileChangedEvent event) {
        Optional<UserNotifyProfileEntity> existing = repository.findById(event.userId());
        // 버전 가드 — 재시도·재발행된 옛 스냅샷이 더 새로운 복제본(또는 탈퇴 tombstone)을 덮지 않게 한다.
        // 한계: root 시각(occurredAt)과 trading 시각(tombstone)을 비교하므로 같은 호스트 시계에 기대고, 같은 사용자의 동시 두 변경이
        // 커밋 역순으로 도착하면 나중 커밋이 버려질 수 있다(발생 드묾 — 다음 변경이 바로잡는다).
        // occurredAt 없는 구버전 발행분은 비교 기준이 없어 그대로 적용한다
        if (event.occurredAt() != null && existing.isPresent() && existing.get().getUpdatedAt().isAfter(event.occurredAt())) {
            log.info("옛 프로필 스냅샷 무시 — userId={}, occurredAt={}, 복제본 updatedAt={}",
                    event.userId(), event.occurredAt(), existing.get().getUpdatedAt());
            return;
        }
        // 직렬화 실패는 잡지 않는다 — 예외를 삼키면 복제본이 조용히 낡고, 그대로 던지면 publication이 FAILED로 남아
        // UserEventResubmitScheduler가 재발행한다(Map<NotificationType,Boolean>이라 실패 가능성 자체가 없음)
        String prefsJson = objectMapper.writeValueAsString(event.notificationPrefs());
        // 채널 없는 이벤트(채널 복제 이전 root가 발행)는 저장된 채널을 지우지 않는다 — NULL이 되면 봇 연결만 보고 텔레그램을 다시 보내게 된다
        NotificationChannel channel = event.notificationChannel() != null
                ? event.notificationChannel()
                : existing.map(UserNotifyProfileEntity::getNotificationChannel).orElse(null);
        // updated_at = 스냅샷 시각(버전) — 구버전 발행분만 동기화 시각
        Instant version = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        // @Id 할당식이라 save()가 곧 upsert
        repository.save(new UserNotifyProfileEntity(
                event.userId(), prefsJson, event.active(),
                event.telegramBotToken(), event.chatId(), version, channel));
    }
}
