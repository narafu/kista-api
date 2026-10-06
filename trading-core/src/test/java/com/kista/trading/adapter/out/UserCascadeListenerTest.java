package com.kista.trading.adapter.out;

import com.kista.account.application.usecase.AccountUseCase;
import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.application.port.output.TradingUserProfilePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class UserCascadeListenerTest {

    @Mock CyclePositionPort cyclePositionPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock StrategyPort strategyPort;
    @Mock AccountUseCase accountUseCase;
    @Mock TradingUserProfilePort tradingUserProfilePort;

    @InjectMocks UserCascadeListener listener;

    @Test
    void onUserDeleted_cleansUpInDependencyOrder() {
        UUID userId = UUID.randomUUID();

        listener.onUserDeleted(new UserDeletedEvent(userId));

        // 소프트 삭제 쿼리가 accounts를 조인하므로 계좌는 하위 데이터 뒤에, 복제본 프로필은 마지막
        InOrder inOrder = inOrder(cyclePositionPort, strategyCyclePort, strategyPort, accountUseCase, tradingUserProfilePort);
        inOrder.verify(cyclePositionPort).deleteByUserId(userId);
        inOrder.verify(strategyCyclePort).deleteByUserId(userId);
        inOrder.verify(strategyPort).deleteByUserId(userId);
        inOrder.verify(accountUseCase).deleteAllByUserId(userId);
        inOrder.verify(tradingUserProfilePort).deleteByUserId(userId);
    }
}
