package com.kista.trading.application.service;

import com.kista.sharedkernel.NotificationType;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.sharedkernel.MarketCloseEvent;
import com.kista.sharedkernel.MarketOpenEvent;
import com.kista.trading.application.port.output.TradingUserProfilePort;
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

    private final TradingUserProfilePort tradingUserProfilePort;
    private final ApplicationEventPublisher eventPublisher;

    void notifyMarketOpen() {
        notify(NotificationType.MARKET_ALERT, user -> eventPublisher.publishEvent(new MarketOpenEvent(user.userId())));
    }

    void notifyMarketClose() {
        notify(NotificationType.MARKET_ALERT, user -> eventPublisher.publishEvent(new MarketCloseEvent(user.userId())));
    }

    private void notify(NotificationType type, Consumer<TradingUserProfile> action) {
        // 배치 조회로 N+1 제거
        var users = tradingUserProfilePort.findAllActive();

        // 사용자별 알림을 virtual thread로 병렬 발송, Semaphore로 동시 발송 수 상한
        // try-with-resources close()가 제출된 모든 작업 완료까지 대기 → 호출자 동기 semantics 유지
        Semaphore limiter = new Semaphore(MAX_CONCURRENT_SENDS);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            users.forEach(user -> {
                if (user.isNotificationEnabled(type)) {
                    executor.submit(() -> sendWithLimit(limiter, user, action));
                }
            });
        }
    }

    // 세마포어 획득 후 발송, 사용자별 실패는 격리(다른 사용자 발송에 영향 없음)
    private void sendWithLimit(Semaphore limiter, TradingUserProfile user, Consumer<TradingUserProfile> action) {
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            action.accept(user);
        } catch (Exception e) {
            log.warn("[userId={}] 장 알림 발송 실패: {}", user.userId(), e.getMessage());
        } finally {
            limiter.release();
        }
    }
}
