package com.kista.user.application.service;

import com.kista.sharedkernel.NotificationType;
import com.kista.user.domain.model.UserSettings;
import com.kista.user.application.usecase.GetUserSettingsQuery;
import com.kista.user.application.usecase.UserSettingsUseCase;
import com.kista.user.application.port.output.ActiveStrategyCountPort;
import com.kista.user.application.port.output.UserSettingsPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public void updateBalanceCheck(UUID userId, boolean enabled) {
        UserSettings current = getByUserId(userId);
        boolean previous = current.balanceCheckEnabled();
        UserSettings updated = current.withBalanceCheckEnabled(enabled);
        userSettingsPort.save(updated);
        log.info("잔고 검증 설정 변경: userId={}, {}→{}", userId, previous, enabled);
        userNotifyProfilePublisher.publishSettingsChanged(updated);

        // 활성 전략 수 계산 — 잔고검증 전환 시 경고 로그 출력
        long activeCount = activeStrategyCountPort.countActiveByUserId(userId);
        if (!previous && enabled && activeCount > 0) {
            // OFF→ON 전환: 활성 전략 존재 시 시드 초과 가능성 경고
            log.warn("[잔고검증 OFF→ON] userId={} — 활성 전략 {}개. 시드가 실잔고 초과 시 다음 사이클에서 PAUSED됩니다.", userId, activeCount);
        }
        if (previous && !enabled) {
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
