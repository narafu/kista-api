package com.kista.trading.application.service;

import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.trading.application.event.MarketCloseEvent;
import com.kista.trading.application.event.MarketOpenEvent;
import com.kista.domain.port.out.UserPort;
import com.kista.domain.port.out.UserSettingsPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

// 장 이벤트(개장·마감) 사용자 알림 발송 — TradingService에서 분리
// ACTIVE 사용자 중 해당 NotificationType이 활성화된 사용자에게만 발송
@Component
@RequiredArgsConstructor
@Slf4j
class MarketEventNotifier {

    private static final int MAX_CONCURRENT_SENDS = 10; // Telegram 초당~30 rate limit 보호

    private final UserPort userPort;
    private final UserSettingsPort userSettingsPort;
    private final ApplicationEventPublisher eventPublisher;

    void notifyMarketOpen() {
        notify(NotificationType.MARKET_ALERT, user -> eventPublisher.publishEvent(new MarketOpenEvent(user)));
    }

    void notifyMarketClose() {
        notify(NotificationType.MARKET_ALERT, user -> eventPublisher.publishEvent(new MarketCloseEvent(user)));
    }

    private void notify(NotificationType type, Consumer<User> action) {
        // 배치 조회로 N+1 제거
        var users = userPort.findAllByStatus(User.UserStatus.ACTIVE);
        var userIds = users.stream().map(User::id).toList();
        var settingsMap = userSettingsPort.findOrDefaultByUserIds(userIds);

        // 사용자별 알림을 virtual thread로 병렬 발송, Semaphore로 동시 발송 수 상한
        // try-with-resources close()가 제출된 모든 작업 완료까지 대기 → 호출자 동기 semantics 유지
        Semaphore limiter = new Semaphore(MAX_CONCURRENT_SENDS);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            users.forEach(user -> {
                UserSettings settings = settingsMap.get(user.id());
                if (settings.isNotificationEnabled(type)) {
                    executor.submit(() -> sendWithLimit(limiter, user, action));
                }
            });
        }
    }

    // 세마포어 획득 후 발송, 사용자별 실패는 격리(다른 사용자 발송에 영향 없음)
    private void sendWithLimit(Semaphore limiter, User user, Consumer<User> action) {
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            action.accept(user);
        } catch (Exception e) {
            log.warn("[userId={}] 장 알림 발송 실패: {}", user.id(), e.getMessage());
        } finally {
            limiter.release();
        }
    }
}
