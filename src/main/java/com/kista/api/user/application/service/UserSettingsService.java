package com.kista.api.user.application.service;

import com.kista.shared.kernel.NotificationType;
import com.kista.api.user.domain.model.UserSettings;
import com.kista.api.user.application.usecase.GetUserSettingsQuery;
import com.kista.api.user.application.usecase.UserSettingsUseCase;
import com.kista.api.user.application.port.output.ActiveStrategyCountPort;
import com.kista.api.user.application.port.output.UserSettingsPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class UserSettingsService implements GetUserSettingsQuery, UserSettingsUseCase {

    private final UserSettingsPort userSettingsPort;
    private final ActiveStrategyCountPort activeStrategyCountPort;
    private final UserNotifyProfilePublisher userNotifyProfilePublisher; // trading-core 캐시 동기화 이벤트 발행
    private final TransactionTemplate transactionTemplate; // updateBalanceCheck 저장·발행만 트랜잭션으로 묶는다(경고용 HTTP는 밖)

    @Override
    public UserSettings getByUserId(UUID userId) {
        // 저장된 설정이 없으면 기본값 반환 (balanceCheckEnabled=true, 빈 알림 prefs)
        return userSettingsPort.findOrDefault(userId);
    }

    @Override
    @Transactional
    public void updateNotificationPref(UUID userId, NotificationType type, boolean enabled) {
        UserSettings current = getByUserId(userId);
        // 기존 prefs에 변경 항목만 덮어씀
        Map<NotificationType, Boolean> updatedPrefs = new HashMap<>(current.notificationPrefs());
        updatedPrefs.put(type, enabled);
        UserSettings updated = current.withNotificationPrefs(updatedPrefs);
        userSettingsPort.save(updated);
        log.info("알림 설정 변경: userId={}, type={}, enabled={}", userId, type, enabled);
        userNotifyProfilePublisher.publishSettingsChanged(updated);
    }

    @Override
    @Transactional
    public void resyncNotifyProfile(UUID userId) {
        // 저장 없이 이벤트만 — 유실된 trading.user_notify_profile 복제본을 현재 값으로 다시 채운다(upsert라 멱등)
        userNotifyProfilePublisher.publishSettingsChanged(getByUserId(userId));
    }

    @Override
    // 메서드 트랜잭션 없음 — 아래 경고용 활성 전략 수가 trading-core 내부 HTTP 호출이라 트랜잭션 밖에 둔다.
    // 저장·복제본 이벤트 발행은 짧은 트랜잭션으로 묶는다 — 트랜잭션 밖에서 발행하면 Redis 발행 실패가 이미 저장된
    // 요청을 500으로 만들고, 안에서 발행해야 실패가 EPR FAILED로 남아 재발행 잡이 바로잡는다
    public void updateBalanceCheck(UUID userId, boolean enabled) {
        UserSettings current = getByUserId(userId);
        boolean previous = current.balanceCheckEnabled();
        UserSettings updated = current.withBalanceCheckEnabled(enabled);
        transactionTemplate.executeWithoutResult(status -> {
            userSettingsPort.save(updated);
            userNotifyProfilePublisher.publishSettingsChanged(updated);
        });
        log.info("잔고 검증 설정 변경: userId={}, {}→{}", userId, previous, enabled);

        if (previous != enabled) {
            warnActiveStrategies(userId, enabled);
        }
    }

    // 잔고검증 전환 시 활성 전략 경고 로그 — 조회 실패는 설정 변경을 막지 않는다(경고 전용)
    private void warnActiveStrategies(UUID userId, boolean enabled) {
        long activeCount;
        try {
            activeCount = activeStrategyCountPort.countActiveByUserId(userId);
        } catch (RuntimeException e) {
            log.warn("잔고검증 전환 경고용 활성 전략 수 조회 실패 — 경고 생략: userId={}, error={}", userId, e.getMessage());
            return;
        }
        if (enabled && activeCount > 0) {
            // OFF→ON 전환: 활성 전략 존재 시 시드 초과 가능성 경고
            log.warn("[잔고검증 OFF→ON] userId={} — 활성 전략 {}개. 시드가 실잔고 초과 시 다음 사이클에서 PAUSED됩니다.", userId, activeCount);
        }
        if (!enabled) {
            // ON→OFF 전환: KIS 주문 거부 가능성 경고 (APBK0988)
            log.warn("[잔고검증 ON→OFF] userId={} — 활성 전략 {}개. 실잔고 초과 시드로 재등록 시 KIS 주문 거부 가능.", userId, activeCount);
        }
    }

    @Override
    @Transactional
    public void updateStrategySuggestions(UUID userId, List<String> suggestions) {
        UserSettings current = getByUserId(userId);
        userSettingsPort.save(current.withStrategySuggestions(suggestions));
        log.info("운영전략 추천 목록 변경: userId={}, count={}", userId, suggestions.size());
    }
}
