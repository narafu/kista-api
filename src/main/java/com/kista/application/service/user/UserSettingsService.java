package com.kista.application.service.user;

import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.in.GetUserSettingsQuery;
import com.kista.domain.port.in.UpdateBalanceCheckUseCase;
import com.kista.domain.port.in.UpdateNotificationPrefUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.UserSettingsPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class UserSettingsService implements GetUserSettingsQuery, UpdateNotificationPrefUseCase, UpdateBalanceCheckUseCase {

    private final UserSettingsPort userSettingsPort;
    private final AccountPort accountPort;
    private final StrategyPort strategyPort;

    @Override
    public UserSettings getByUserId(UUID userId) {
        // 저장된 설정이 없으면 기본값 반환 (balanceCheckEnabled=true, 빈 알림 prefs)
        return userSettingsPort.findOrDefault(userId);
    }

    @Override
    @Transactional
    public void update(UpdateNotificationPrefCommand command) {
        UserSettings current = getByUserId(command.userId());
        // 기존 prefs에 변경 항목만 덮어씀
        Map<NotificationType, Boolean> updatedPrefs = new HashMap<>(current.notificationPrefs());
        updatedPrefs.put(command.type(), command.enabled());
        userSettingsPort.save(new UserSettings(command.userId(), current.balanceCheckEnabled(), updatedPrefs));
        log.info("알림 설정 변경: userId={}, type={}, enabled={}", command.userId(), command.type(), command.enabled());
    }

    @Override
    @Transactional
    public void update(UpdateBalanceCheckCommand command) {
        UserSettings current = getByUserId(command.userId());
        boolean previous = current.balanceCheckEnabled();
        userSettingsPort.save(new UserSettings(command.userId(), command.enabled(), current.notificationPrefs()));
        log.info("잔고 검증 설정 변경: userId={}, {}→{}", command.userId(), previous, command.enabled());

        // 활성 전략 수 계산 — 잔고검증 전환 시 경고 로그 출력
        long activeCount = countActiveStrategies(command.userId());
        if (!previous && command.enabled() && activeCount > 0) {
            // OFF→ON 전환: 활성 전략 존재 시 시드 초과 가능성 경고
            log.warn("[잔고검증 OFF→ON] userId={} — 활성 전략 {}개. 시드가 실잔고 초과 시 다음 사이클에서 PAUSED됩니다.", command.userId(), activeCount);
        }
        if (previous && !command.enabled()) {
            // ON→OFF 전환: KIS 주문 거부 가능성 경고 (APBK0988)
            log.warn("[잔고검증 ON→OFF] userId={} — 활성 전략 {}개. 실잔고 초과 시드로 재등록 시 KIS 주문 거부 가능.", command.userId(), activeCount);
        }
    }

    // 사용자의 모든 계좌에서 ACTIVE 상태 전략 총 개수 반환
    private long countActiveStrategies(UUID userId) {
        return accountPort.findByUserId(userId).stream()
                .flatMap(account -> strategyPort.findByAccountId(account.id()).stream())
                .filter(strategy -> strategy.isActive())
                .count();
    }
}
