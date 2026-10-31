package com.kista.tradingcore.trading.adapter.out.persistence;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.kista.shared.kernel.NotificationChannel;
import com.kista.shared.kernel.NotificationType;
import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import com.kista.tradingcore.trading.domain.model.TradingUserProfileChange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// TradingUserProfilePort 구현 — user 모듈을 직접 참조하던 web의 TradingUserProfileAdapter를 대체한다.
// trading-core 소유 복제 테이블(trading.user_notify_profile)만 읽으므로 root(:api) 컴파일 의존이 0이다.
@Component
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
class UserNotifyProfilePersistenceAdapter implements TradingUserProfilePort {

    private static final TypeReference<Map<NotificationType, Boolean>> PREFS_TYPE = new TypeReference<>() {};

    private final UserNotifyProfileJpaRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<TradingUserProfile> findByUserId(UUID userId) {
        return repository.findById(userId).map(this::toProfile);
    }

    @Override
    public Map<UUID, TradingUserProfile> findAllByUserIds(List<UUID> userIds) {
        return repository.findAllByUserIdIn(userIds).stream()
                .map(this::toProfile)
                .collect(Collectors.toMap(TradingUserProfile::userId, Function.identity()));
    }

    @Override
    public List<TradingUserProfile> findAllActive() {
        return repository.findAllByActiveTrue().stream().map(this::toProfile).toList();
    }

    private TradingUserProfile toProfile(UserNotifyProfileEntity entity) {
        return new TradingUserProfile(entity.getUserId(), readPrefs(entity),
                entity.getTelegramBotToken(), entity.getChatId(), entity.getNotificationChannel());
    }

    // 복제본이 손상돼도 알림 판정만 기본값(전부 활성)으로 떨어지고 매매 자체는 계속되도록 격리한다
    private Map<NotificationType, Boolean> readPrefs(UserNotifyProfileEntity entity) {
        try {
            return objectMapper.readValue(entity.getNotificationPrefsJson(), PREFS_TYPE);
        } catch (Exception e) {
            log.warn("[userId={}] 알림 설정 복제본 역직렬화 실패 — 기본값(전부 활성)으로 대체: {}",
                    entity.getUserId(), e.getMessage());
            return Map.of();
        }
    }

    @Override
    @Transactional // 클래스 기본 readOnly 해제 — UserNotifyProfileSyncListener(REQUIRES_NEW) 트랜잭션에 합류
    public void applyChange(TradingUserProfileChange change) {
        Optional<UserNotifyProfileEntity> existing = repository.findById(change.userId());
        // 버전 가드 — 재시도·재발행된 옛 스냅샷이 더 새로운 복제본(또는 탈퇴 tombstone)을 덮지 않게 한다.
        // 한계: root 시각(occurredAt)과 trading 시각(tombstone)을 비교하므로 같은 호스트 시계에 기대고, 같은 사용자의 동시 두 변경이
        // 커밋 역순으로 도착하면 나중 커밋이 버려질 수 있다(발생 드묾 — 다음 변경이 바로잡는다).
        // occurredAt 없는 구버전 발행분은 비교 기준이 없어 그대로 적용한다
        if (change.occurredAt() != null && existing.isPresent() && existing.get().getUpdatedAt().isAfter(change.occurredAt())) {
            log.info("옛 프로필 스냅샷 무시 — userId={}, occurredAt={}, 복제본 updatedAt={}",
                    change.userId(), change.occurredAt(), existing.get().getUpdatedAt());
            return;
        }
        // 직렬화 실패는 잡지 않는다 — 예외를 삼키면 복제본이 조용히 낡고, 그대로 던지면 publication이 FAILED로 남아
        // UserEventResubmitScheduler가 재발행한다(Map<NotificationType,Boolean>이라 실패 가능성 자체가 없음)
        String prefsJson = objectMapper.writeValueAsString(change.notificationPrefs());
        // updated_at = 스냅샷 시각(버전) — 구버전 발행분만 동기화 시각
        Instant version = change.occurredAt() != null ? change.occurredAt() : Instant.now();
        // 기존 행은 필드 갱신, 없으면 신규 생성 — @Id 할당식이라 save()가 곧 upsert
        UserNotifyProfileEntity entity = existing.orElseGet(() -> new UserNotifyProfileEntity(
                change.userId(), prefsJson, change.active(),
                change.telegramBotToken(), change.chatId(), version, change.notificationChannel()));
        entity.applyChange(prefsJson, change.active(), change.telegramBotToken(), change.chatId(), version, change.notificationChannel());
        repository.save(entity);
    }

    @Override
    @Transactional // 클래스 기본 readOnly 해제 — 탈퇴 정리 트랜잭션(UserTradingDataEraseService)에 합류
    public void markDeleted(UUID userId) {
        // 행이 없어도 tombstone을 남긴다 — updated_at(trading 시각)이 탈퇴 전 root에서 만든 프로필 스냅샷(occurredAt)보다 늦어
        // applyChange 버전 가드가 늦게 도착한 스냅샷을 거른다
        NotificationChannel channel = repository.findById(userId).map(UserNotifyProfileEntity::getNotificationChannel).orElse(null);
        repository.save(new UserNotifyProfileEntity(userId, "{}", false, null, null, Instant.now(), channel));
    }
}
