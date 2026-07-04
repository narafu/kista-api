package com.kista.application.service.trading;

import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserPort;
import com.kista.domain.port.out.UserSettingsPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

// 장 이벤트(개장·마감) 사용자 알림 발송 — TradingService에서 분리
// ACTIVE 사용자 중 해당 NotificationType이 활성화된 사용자에게만 발송
@Component
@RequiredArgsConstructor
@Slf4j
class MarketEventNotifier {

    private final UserPort userPort;
    private final UserSettingsPort userSettingsPort;
    private final UserNotificationPort userNotificationPort;

    void notifyMarketOpen() {
        notify(NotificationType.MARKET_ALERT, user -> userNotificationPort.notifyMarketOpen(user));
    }

    void notifyMarketClose() {
        notify(NotificationType.MARKET_ALERT, user -> userNotificationPort.notifyMarketClose(user));
    }

    private void notify(NotificationType type, Consumer<User> action) {
        userPort.findAllByStatus(User.UserStatus.ACTIVE).forEach(user -> {
            UserSettings settings = userSettingsPort.findOrDefault(user.id());
            if (settings.isNotificationEnabled(type)) {
                try {
                    action.accept(user);
                } catch (Exception e) {
                    log.warn("[userId={}] 장 알림 발송 실패: {}", user.id(), e.getMessage());
                }
            }
        });
    }
}
