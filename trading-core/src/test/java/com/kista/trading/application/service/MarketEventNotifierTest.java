package com.kista.trading.application.service;

import com.kista.sharedkernel.NotificationType;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.sharedkernel.MarketCloseEvent;
import com.kista.sharedkernel.MarketOpenEvent;
import com.kista.trading.application.port.output.TradingUserProfilePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
class MarketEventNotifierTest {

    @Mock TradingUserProfilePort tradingUserProfilePort;
    @Mock ApplicationEventPublisher eventPublisher;

    MarketEventNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new MarketEventNotifier(tradingUserProfilePort, eventPublisher);
    }

    private static TradingUserProfile activeProfile(UUID userId, boolean marketAlertEnabled) {
        return new TradingUserProfile(userId, Map.of(NotificationType.MARKET_ALERT, marketAlertEnabled), true, null, null);
    }

    // 사용자 알림이 순차가 아닌 virtual thread로 병렬 발송되는지 CyclicBarrier로 검증
    @Test
    void 사용자_알림을_병렬로_발송한다() throws Exception {
        int userCount = 5;
        List<TradingUserProfile> users = IntStream.range(0, userCount)
                .mapToObj(i -> activeProfile(UUID.randomUUID(), true))
                .toList();

        when(tradingUserProfilePort.findAllActive()).thenReturn(users);

        // 모든 스레드가 barrier에 동시 도달해야만 통과 — 순차 실행이면 타임아웃되어 실패 기록됨
        // (구현이 사용자별 예외를 격리하므로, barrier 타임아웃 예외 자체는 notify() 밖으로 전파되지 않음 — 별도 리스트로 성공 여부를 직접 관측)
        CyclicBarrier barrier = new CyclicBarrier(userCount);
        java.util.List<Boolean> barrierResults = new java.util.concurrent.CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            try {
                barrier.await(3, TimeUnit.SECONDS);
                barrierResults.add(true);
            } catch (Exception e) {
                barrierResults.add(false);
            }
            return null;
        }).when(eventPublisher).publishEvent(any(MarketOpenEvent.class));

        notifier.notifyMarketOpen();

        verify(eventPublisher, times(userCount)).publishEvent(any(MarketOpenEvent.class));
        assertThat(barrierResults).hasSize(userCount).allMatch(Boolean::booleanValue);
    }

    // Semaphore(10) 상한을 넘지 않는지 동시 실행 수 관찰로 검증
    @Test
    void 동시_발송_수는_세마포어_상한을_넘지_않는다() throws Exception {
        int userCount = 30;
        List<TradingUserProfile> users = IntStream.range(0, userCount)
                .mapToObj(i -> activeProfile(UUID.randomUUID(), true))
                .toList();

        when(tradingUserProfilePort.findAllActive()).thenReturn(users);

        AtomicInteger current = new AtomicInteger(0);
        AtomicInteger max = new AtomicInteger(0);
        doAnswer(invocation -> {
            int now = current.incrementAndGet();
            max.updateAndGet(prev -> Math.max(prev, now));
            Thread.sleep(20); // 동시성 관찰 창 확보
            current.decrementAndGet();
            return null;
        }).when(eventPublisher).publishEvent(any(MarketCloseEvent.class));

        notifier.notifyMarketClose();

        assertThat(max.get()).isLessThanOrEqualTo(10);
        verify(eventPublisher, times(userCount)).publishEvent(any(MarketCloseEvent.class));
    }

    // 한 사용자 발송 실패가 다른 사용자 발송을 막지 않는지 검증 (실패 격리)
    @Test
    void 일부_사용자_실패가_나머지_발송을_막지_않는다() {
        UUID failingUserId = UUID.randomUUID();
        TradingUserProfile failingUser = activeProfile(failingUserId, true);
        List<TradingUserProfile> users = IntStream.range(0, 4)
                .mapToObj(i -> activeProfile(UUID.randomUUID(), true))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        users.add(failingUser);

        when(tradingUserProfilePort.findAllActive()).thenReturn(users);

        doThrow(new RuntimeException("텔레그램 발송 실패")).when(eventPublisher).publishEvent(new MarketOpenEvent(failingUser.userId()));

        notifier.notifyMarketOpen();

        // 실패한 사용자를 제외한 나머지 전원에게는 정상 발송됨
        verify(eventPublisher, times(users.size())).publishEvent(any(MarketOpenEvent.class));
    }

    // MARKET_ALERT 알림이 비활성화된 사용자는 발송 대상에서 제외되는지 검증
    @Test
    void 알림_비활성_사용자는_제외한다() {
        TradingUserProfile enabledUser = activeProfile(UUID.randomUUID(), true);
        TradingUserProfile disabledUser = activeProfile(UUID.randomUUID(), false);
        List<TradingUserProfile> users = List.of(enabledUser, disabledUser);

        when(tradingUserProfilePort.findAllActive()).thenReturn(users);

        notifier.notifyMarketOpen();

        verify(eventPublisher, times(1)).publishEvent(new MarketOpenEvent(enabledUser.userId()));
        verify(eventPublisher, never()).publishEvent(new MarketOpenEvent(disabledUser.userId()));
    }
}
