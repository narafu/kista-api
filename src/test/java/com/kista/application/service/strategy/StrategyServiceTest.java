package com.kista.application.service.strategy;

import com.kista.application.event.TradingCyclePausedEvent;
import com.kista.application.event.TradingCycleResumedEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StrategyService 단위 테스트")
class StrategyServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock AccountPort accountPort;
    @Mock UserPort userPort;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks StrategyService strategyService;

    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID  = UUID.randomUUID();
    private static final UUID USER_ID     = UUID.randomUUID();

    // ACTIVE 상태 전략 픽스처
    private static final Strategy ACTIVE_STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, Strategy.Type.INFINITE, Strategy.Status.ACTIVE,
            Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
    );

    // PAUSED 상태 전략 픽스처
    private static final Strategy PAUSED_STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, Strategy.Type.INFINITE, Strategy.Status.PAUSED,
            Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
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
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(PAUSED_STRATEGY);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());

        // when
        strategyService.pause(STRATEGY_ID, USER_ID);

        // then
        verify(eventPublisher).publishEvent(any(TradingCyclePausedEvent.class));
        verify(strategyPort).save(any(Strategy.class));
    }

    @Test
    @DisplayName("resume() 호출 시 TradingCycleResumedEvent가 발행된다")
    void resume_publishes_TradingCycleResumedEvent() {
        // given
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());
        when(strategyPort.save(any(Strategy.class))).thenReturn(ACTIVE_STRATEGY);
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());

        // when
        strategyService.resume(STRATEGY_ID, USER_ID);

        // then
        verify(eventPublisher).publishEvent(any(TradingCycleResumedEvent.class));
        verify(strategyPort).save(any(Strategy.class));
    }

    @Test
    @DisplayName("pause() 호출 시 소유자가 아니면 SecurityException이 발생한다 (→ 403)")
    void pause_by_non_owner_throws_security_exception() {
        UUID otherUserId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(ACTIVE_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, otherUserId))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        assertThatThrownBy(() -> strategyService.pause(STRATEGY_ID, otherUserId))
                .isInstanceOf(SecurityException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("resume() 호출 시 소유자가 아니면 SecurityException이 발생한다 (→ 403)")
    void resume_by_non_owner_throws_security_exception() {
        UUID otherUserId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(PAUSED_STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, otherUserId))
                .thenThrow(new SecurityException("소유자가 아닙니다"));

        assertThatThrownBy(() -> strategyService.resume(STRATEGY_ID, otherUserId))
                .isInstanceOf(SecurityException.class);

        verify(eventPublisher, never()).publishEvent(any());
    }
}
