package com.kista.trading.application.service.support;

import com.kista.sharedkernel.OrderStatus;
import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.domain.model.Order;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.trading.domain.model.Strategy;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
@DisplayName("SelectionChain 단위 테스트")
class SelectionChainTest {

    @Mock
    private AccountPort accountPort;
    @Mock
    private StrategyPort strategyPort;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID strategyId = UUID.randomUUID();

    private Account account() {
        return DomainFixtures.kisAccount(accountId, userId);
    }

    private Strategy strategy() {
        return new Strategy(strategyId, accountId, StrategyType.INFINITE, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }

    // account/strategy 소속과 무관한 임의 계좌의 주문
    private Order order(UUID orderAccountId) {
        return new Order(UUID.randomUUID(), orderAccountId, UUID.randomUUID(), LocalDate.now(),
                StrategyTicker.SOXL, OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY,
                10, BigDecimal.TEN, OrderStatus.PLANNED, null, null, null);
    }

    @Test
    @DisplayName("account가_userId에_속하지_않으면_예외")
    void account가_userId에_속하지_않으면_예외() {
        UUID otherUserId = UUID.randomUUID();

        AccountPort accountPort = mock(AccountPort.class);
        StrategyPort strategyPort = mock(StrategyPort.class);
        Account account = mock(Account.class);
        Strategy strategy = mock(Strategy.class);
        when(account.userId()).thenReturn(otherUserId);
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(account);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);

        assertThatThrownBy(() ->
                SelectionChain.resolveAndValidate(accountPort, strategyPort, accountId, strategyId, userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account가 user에 속하지 않습니다");
    }

    @Test
    @DisplayName("2개 Port 조회 후 소속 관계가 일치하면 Selection을 반환한다")
    void resolveAndValidate_success() {
        Account account = account();
        Strategy strategy = strategy();
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(account);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);

        SelectionChain.Selection selection = SelectionChain.resolveAndValidate(
                accountPort, strategyPort, accountId, strategyId, userId);

        assertThat(selection.account()).isEqualTo(account);
        assertThat(selection.strategy()).isEqualTo(strategy);
    }

    @Test
    @DisplayName("strategy.accountId가 account.id와 다르면 IllegalArgumentException")
    void resolveAndValidate_strategyNotBelongToAccount() {
        Account account = account();
        // strategy가 다른 계좌에 속함
        Strategy strategy = new Strategy(strategyId, UUID.randomUUID(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(account);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);

        assertThatThrownBy(() -> SelectionChain.resolveAndValidate(
                accountPort, strategyPort, accountId, strategyId, userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy가 account에 속하지 않습니다");
    }

    @Test
    @DisplayName("4-인자 validate: order.accountId가 account.id와 다르면 IllegalArgumentException")
    void validateWithOrder_orderNotBelongToAccount() {
        Account account = account();
        Strategy strategy = strategy();
        Order order = order(UUID.randomUUID());

        assertThatThrownBy(() -> SelectionChain.validate(userId, account, strategy, order))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order가 account에 속하지 않습니다");
    }

    @Test
    @DisplayName("4-인자 validate: 모든 소속 관계가 일치하면 예외 없이 통과한다")
    void validateWithOrder_success() {
        Account account = account();
        Strategy strategy = strategy();
        Order order = order(accountId);

        assertThatCode(() -> SelectionChain.validate(userId, account, strategy, order))
                .doesNotThrowAnyException();
    }
}

