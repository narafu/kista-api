package com.kista.trading.application.service;

import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.model.user.UserSettings;
import com.kista.trading.application.event.MarketCloseEvent;
import com.kista.trading.application.event.MarketOpenEvent;
import com.kista.application.port.output.UserPort;
import com.kista.application.port.output.UserSettingsPort;
import com.kista.support.DomainFixtures;
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

    @Mock UserPort userPort;
    @Mock UserSettingsPort userSettingsPort;
    @Mock ApplicationEventPublisher eventPublisher;

    MarketEventNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new MarketEventNotifier(userPort, userSettingsPort, eventPublisher);
    }

    // 사용자 알림이 순차가 아닌 virtual thread로 병렬 발송되는지 CyclicBarrier로 검증
    @Test
    void 사용자_알림을_병렬로_발송한다() throws Exception {
        int userCount = 5;
        List<User> users = IntStream.range(0, userCount)
                .mapToObj(i -> DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.TELEGRAM))
                .toList();
        Map<UUID, UserSettings> settingsMap = users.stream()
                .collect(java.util.stream.Collectors.toMap(User::id,
                        u -> new UserSettings(u.id(), true, Map.of(NotificationType.MARKET_ALERT, true), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS)));

        when(userPort.findAllByStatus(User.UserStatus.ACTIVE)).thenReturn(users);
        when(userSettingsPort.findOrDefaultByUserIds(any())).thenReturn(settingsMap);

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
        List<User> users = IntStream.range(0, userCount)
                .mapToObj(i -> DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.TELEGRAM))
                .toList();
        Map<UUID, UserSettings> settingsMap = users.stream()
                .collect(java.util.stream.Collectors.toMap(User::id,
                        u -> new UserSettings(u.id(), true, Map.of(NotificationType.MARKET_ALERT, true), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS)));

        when(userPort.findAllByStatus(User.UserStatus.ACTIVE)).thenReturn(users);
        when(userSettingsPort.findOrDefaultByUserIds(any())).thenReturn(settingsMap);

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
        User failingUser = DomainFixtures.activeUser(failingUserId, NotificationChannel.TELEGRAM);
        List<User> users = IntStream.range(0, 4)
                .mapToObj(i -> DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.TELEGRAM))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        users.add(failingUser);
        Map<UUID, UserSettings> settingsMap = users.stream()
                .collect(java.util.stream.Collectors.toMap(User::id,
                        u -> new UserSettings(u.id(), true, Map.of(NotificationType.MARKET_ALERT, true), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS)));

        when(userPort.findAllByStatus(User.UserStatus.ACTIVE)).thenReturn(users);
        when(userSettingsPort.findOrDefaultByUserIds(any())).thenReturn(settingsMap);

        doThrow(new RuntimeException("텔레그램 발송 실패")).when(eventPublisher).publishEvent(new MarketOpenEvent(failingUser.id()));

        notifier.notifyMarketOpen();

        // 실패한 사용자를 제외한 나머지 전원에게는 정상 발송됨
        verify(eventPublisher, times(users.size())).publishEvent(any(MarketOpenEvent.class));
    }

    // MARKET_ALERT 알림이 비활성화된 사용자는 발송 대상에서 제외되는지 검증
    @Test
    void 알림_비활성_사용자는_제외한다() {
        User enabledUser = DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.TELEGRAM);
        User disabledUser = DomainFixtures.activeUser(UUID.randomUUID(), NotificationChannel.TELEGRAM);
        List<User> users = List.of(enabledUser, disabledUser);
        Map<UUID, UserSettings> settingsMap = Map.of(
                enabledUser.id(), new UserSettings(enabledUser.id(), true, Map.of(NotificationType.MARKET_ALERT, true), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS),
                disabledUser.id(), new UserSettings(disabledUser.id(), true, Map.of(NotificationType.MARKET_ALERT, false), UserSettings.DEFAULT_STRATEGY_SUGGESTIONS)
        );

        when(userPort.findAllByStatus(User.UserStatus.ACTIVE)).thenReturn(users);
        when(userSettingsPort.findOrDefaultByUserIds(any())).thenReturn(settingsMap);

        notifier.notifyMarketOpen();

        verify(eventPublisher, times(1)).publishEvent(new MarketOpenEvent(enabledUser.id()));
        verify(eventPublisher, never()).publishEvent(new MarketOpenEvent(disabledUser.id()));
    }
}
