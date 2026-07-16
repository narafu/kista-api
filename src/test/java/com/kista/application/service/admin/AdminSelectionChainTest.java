package com.kista.application.service.admin;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.UserPort;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminSelectionChain 단위 테스트")
class AdminSelectionChainTest {

    @Mock
    private UserPort userPort;
    @Mock
    private AccountPort accountPort;
    @Mock
    private StrategyPort strategyPort;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID strategyId = UUID.randomUUID();

    // 소속 관계가 일치하는 user/account/strategy 세트 생성
    private User user() {
        return DomainFixtures.activeUser(userId, User.NotificationChannel.NONE);
    }

    private Account account() {
        return DomainFixtures.kisAccount(accountId, userId);
    }

    private Strategy strategy() {
        return new Strategy(strategyId, accountId, Strategy.Type.INFINITE, Strategy.Status.ACTIVE,
                Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
    }

    // account/strategy 소속과 무관한 임의 계좌의 주문
    private Order order(UUID orderAccountId) {
        return new Order(UUID.randomUUID(), orderAccountId, UUID.randomUUID(), LocalDate.now(),
                Strategy.Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                10, BigDecimal.TEN, Order.OrderStatus.PLANNED, null, null, null);
    }

    @Test
    @DisplayName("3개 Port 조회 후 소속 관계가 일치하면 Selection을 반환한다")
    void resolveAndValidate_success() {
        User user = user();
        Account account = account();
        Strategy strategy = strategy();
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(account);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);

        AdminSelectionChain.Selection selection = AdminSelectionChain.resolveAndValidate(
                userPort, accountPort, strategyPort, userId, accountId, strategyId);

        assertThat(selection.user()).isEqualTo(user);
        assertThat(selection.account()).isEqualTo(account);
        assertThat(selection.strategy()).isEqualTo(strategy);
    }

    @Test
    @DisplayName("account.userId가 user.id와 다르면 IllegalArgumentException")
    void resolveAndValidate_accountNotBelongToUser() {
        User user = user();
        // account가 다른 사용자에 속함
        Account account = DomainFixtures.kisAccount(accountId, UUID.randomUUID());
        Strategy strategy = strategy();
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(account);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);

        assertThatThrownBy(() -> AdminSelectionChain.resolveAndValidate(
                userPort, accountPort, strategyPort, userId, accountId, strategyId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account가 user에 속하지 않습니다");
    }

    @Test
    @DisplayName("strategy.accountId가 account.id와 다르면 IllegalArgumentException")
    void resolveAndValidate_strategyNotBelongToAccount() {
        User user = user();
        Account account = account();
        // strategy가 다른 계좌에 속함
        Strategy strategy = new Strategy(strategyId, UUID.randomUUID(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(account);
        when(strategyPort.findByIdOrThrow(strategyId)).thenReturn(strategy);

        assertThatThrownBy(() -> AdminSelectionChain.resolveAndValidate(
                userPort, accountPort, strategyPort, userId, accountId, strategyId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy가 account에 속하지 않습니다");
    }

    @Test
    @DisplayName("4-인자 validate: order.accountId가 account.id와 다르면 IllegalArgumentException")
    void validateWithOrder_orderNotBelongToAccount() {
        User user = user();
        Account account = account();
        Strategy strategy = strategy();
        Order order = order(UUID.randomUUID());

        assertThatThrownBy(() -> AdminSelectionChain.validate(user, account, strategy, order))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order가 account에 속하지 않습니다");
    }

    @Test
    @DisplayName("4-인자 validate: 모든 소속 관계가 일치하면 예외 없이 통과한다")
    void validateWithOrder_success() {
        User user = user();
        Account account = account();
        Strategy strategy = strategy();
        Order order = order(accountId);

        assertThatCode(() -> AdminSelectionChain.validate(user, account, strategy, order))
                .doesNotThrowAnyException();
    }
}
