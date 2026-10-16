package com.kista.tradingcore.trading.adapter.out;

import com.kista.tradingcore.account.application.port.output.AccountOpenOrderCancelPort;
import com.kista.tradingcore.account.application.usecase.AccountUseCase;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.kista.tradingcore.support.TradingFixtures.kisAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserTradingDataEraserTest {

    @Mock AccountOpenOrderCancelPort openOrderCancelPort;
    @Mock TradingErrorReportPort errorReportPort;
    @Mock CyclePositionPort cyclePositionPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock StrategyPort strategyPort;
    @Mock AccountUseCase accountUseCase;
    @Mock TradingUserProfilePort tradingUserProfilePort;

    @InjectMocks UserTradingDataEraser eraser;

    private final UUID userId = UUID.randomUUID();

    @Test
    void erase_cleansUpInDependencyOrder() {
        eraser.erase(userId);

        // 소프트 삭제 쿼리가 accounts를 조인하므로 계좌는 하위 데이터 뒤에, 복제본 tombstone은 마지막
        InOrder inOrder = inOrder(cyclePositionPort, strategyCyclePort, strategyPort, accountUseCase, tradingUserProfilePort);
        inOrder.verify(cyclePositionPort).deleteByUserId(userId);
        inOrder.verify(strategyCyclePort).deleteByUserId(userId);
        inOrder.verify(strategyPort).deleteByUserId(userId);
        inOrder.verify(accountUseCase).deleteAllByUserId(userId);
        inOrder.verify(tradingUserProfilePort).markDeleted(userId);
    }

    @Test
    void cancelOpenOrders_계좌마다_취소하고_성공이면_알리지_않는다() {
        Account account = kisAccount(UUID.randomUUID(), userId);
        when(accountUseCase.listByUser(userId)).thenReturn(List.of(account));

        eraser.cancelOpenOrders(userId);

        verify(openOrderCancelPort).cancelOpenOrders(account);
        verifyNoInteractions(errorReportPort);
    }

    @Test
    void cancelOpenOrders_실패는_계좌별로_알리고_다음_계좌를_계속한다() {
        Account failing = kisAccount(UUID.randomUUID(), userId);
        Account throwing = kisAccount(UUID.randomUUID(), userId);
        when(accountUseCase.listByUser(userId)).thenReturn(List.of(throwing, failing));
        when(openOrderCancelPort.cancelOpenOrders(throwing)).thenThrow(new IllegalStateException("broker down"));
        when(openOrderCancelPort.cancelOpenOrders(failing)).thenReturn(1);

        eraser.cancelOpenOrders(userId);

        verify(errorReportPort).reportError(argThat(e -> e.getMessage().contains(throwing.id().toString())));
        verify(errorReportPort).reportError(argThat(e -> e.getMessage().contains(failing.id().toString())));
    }

    // AFTER_COMMIT 콜백에서 호출되므로 아직 묶여 있는 재발행 트랜잭션에 합류하면 쓰기가 커밋되지 않는다 — 전파 속성을 잠근다
    @Test
    void 전파_속성은_취소_NOT_SUPPORTED_정리_REQUIRES_NEW다() throws NoSuchMethodException {
        assertThat(UserTradingDataEraser.class.getMethod("cancelOpenOrders", UUID.class).getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.NOT_SUPPORTED);
        assertThat(UserTradingDataEraser.class.getMethod("erase", UUID.class).getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }
}
