package com.kista.application.service;

import com.kista.application.event.TradingCyclePausedEvent;
import com.kista.application.event.TradingCycleResumedEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.TradingCycleHistoryPort;
import com.kista.domain.port.out.TradingCyclePort;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TradingCycleService 단위 테스트")
class TradingCycleServiceTest {

    @Mock TradingCyclePort cyclePort;
    @Mock TradingCycleHistoryPort cycleHistoryPort;
    @Mock AccountPort accountPort;
    @Mock UserPort userPort;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks TradingCycleService tradingCycleService;

    private static final UUID CYCLE_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    // ACTIVE 상태 사이클 픽스처
    private static final TradingCycle ACTIVE_CYCLE = new TradingCycle(
            CYCLE_ID, ACCOUNT_ID, TradingCycle.Type.INFINITE, TradingCycle.Status.ACTIVE,
            TradingCycle.Ticker.SOXL, new BigDecimal("1000"), TradingCycle.CycleSeedType.NONE
    );

    // PAUSED 상태 사이클 픽스처 (pause() 후 저장되는 값)
    private static final TradingCycle PAUSED_CYCLE = new TradingCycle(
            CYCLE_ID, ACCOUNT_ID, TradingCycle.Type.INFINITE, TradingCycle.Status.PAUSED,
            TradingCycle.Ticker.SOXL, new BigDecimal("1000"), TradingCycle.CycleSeedType.NONE
    );

    // ACTIVE 상태 사이클 픽스처 (resume() 후 저장되는 값)
    private static final TradingCycle RESUMED_CYCLE = new TradingCycle(
            CYCLE_ID, ACCOUNT_ID, TradingCycle.Type.INFINITE, TradingCycle.Status.ACTIVE,
            TradingCycle.Ticker.SOXL, new BigDecimal("1000"), TradingCycle.CycleSeedType.NONE
    );

    private Account ownerAccount() {
        return new Account(ACCOUNT_ID, USER_ID, "테스트계좌",
                "74420614", "appKey", "appSecret", "01", Account.Broker.KIS);
    }

    private User activeUser() {
        return new User(USER_ID, "kakao123", "테스터",
                User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, NotificationChannel.TELEGRAM);
    }

    @Test
    @DisplayName("pause() 호출 시 TradingCyclePausedEvent가 발행된다")
    void pause_publishes_TradingCyclePausedEvent() {
        // given
        when(cyclePort.findByIdOrThrow(CYCLE_ID)).thenReturn(ACTIVE_CYCLE);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ownerAccount());
        when(cyclePort.save(any(TradingCycle.class))).thenReturn(PAUSED_CYCLE);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());

        // when
        tradingCycleService.pause(CYCLE_ID, USER_ID);

        // then
        verify(eventPublisher).publishEvent(any(TradingCyclePausedEvent.class));
        verify(cyclePort).save(any(TradingCycle.class));
    }

    @Test
    @DisplayName("resume() 호출 시 TradingCycleResumedEvent가 발행된다")
    void resume_publishes_TradingCycleResumedEvent() {
        // given
        when(cyclePort.findByIdOrThrow(CYCLE_ID)).thenReturn(PAUSED_CYCLE);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ownerAccount());
        when(cyclePort.save(any(TradingCycle.class))).thenReturn(RESUMED_CYCLE);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());

        // when
        tradingCycleService.resume(CYCLE_ID, USER_ID);

        // then
        verify(eventPublisher).publishEvent(any(TradingCycleResumedEvent.class));
        verify(cyclePort).save(any(TradingCycle.class));
    }

    @Test
    @DisplayName("pause() 호출 시 소유자가 아니면 SecurityException이 발생한다 (→ 403)")
    void pause_by_non_owner_throws_security_exception() {
        UUID otherUserId = UUID.randomUUID();
        when(cyclePort.findByIdOrThrow(CYCLE_ID)).thenReturn(ACTIVE_CYCLE);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ownerAccount());

        // ownerAccount()의 userId=USER_ID, 요청자=otherUserId → SecurityException
        assertThatThrownBy(() -> tradingCycleService.pause(CYCLE_ID, otherUserId))
                .isInstanceOf(SecurityException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("resume() 호출 시 소유자가 아니면 SecurityException이 발생한다 (→ 403)")
    void resume_by_non_owner_throws_security_exception() {
        UUID otherUserId = UUID.randomUUID();
        when(cyclePort.findByIdOrThrow(CYCLE_ID)).thenReturn(PAUSED_CYCLE);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> tradingCycleService.resume(CYCLE_ID, otherUserId))
                .isInstanceOf(SecurityException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }
}
