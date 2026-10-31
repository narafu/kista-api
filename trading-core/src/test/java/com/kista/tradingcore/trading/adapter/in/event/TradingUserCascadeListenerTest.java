package com.kista.tradingcore.trading.adapter.in.event;

import com.kista.shared.kernel.UserDeletedEvent;
import com.kista.tradingcore.trading.application.usecase.UserTradingDataEraseUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class TradingUserCascadeListenerTest {

    @Mock UserTradingDataEraseUseCase eraser;
    @InjectMocks TradingUserCascadeListener listener;

    private final UUID userId = UUID.randomUUID();

    @Test
    void 미체결_취소_후_데이터를_정리한다() {
        listener.onUserDeleted(new UserDeletedEvent(userId));

        // 계좌가 soft-delete되면 취소 대상을 찾을 수 없으므로 취소(전략 PAUSED 포함)가 먼저
        InOrder inOrder = inOrder(eraser);
        inOrder.verify(eraser).cancelOpenOrders(userId);
        inOrder.verify(eraser).erase(userId);
    }

    @Test
    void 정리_실패는_다시_던져_publication을_FAILED로_남긴다() {
        doThrow(new IllegalStateException("db down")).when(eraser).erase(userId);

        assertThatThrownBy(() -> listener.onUserDeleted(new UserDeletedEvent(userId)))
                .isInstanceOf(IllegalStateException.class).hasMessage("db down");
    }
}
