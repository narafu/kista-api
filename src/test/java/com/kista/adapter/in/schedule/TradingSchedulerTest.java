package com.kista.adapter.in.schedule;

import com.kista.domain.port.in.ExecuteTradingUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingSchedulerTest {

    @Mock ExecuteTradingUseCase useCase;
    @InjectMocks TradingScheduler scheduler;

    @Test
    void run_callsUseCaseExecute() throws InterruptedException {
        scheduler.run();

        verify(useCase).execute();
    }

    @Test
    void run_interruptedException_restoresInterruptFlag() throws InterruptedException {
        doThrow(new InterruptedException("interrupted")).when(useCase).execute();

        scheduler.run();

        // interrupt 플래그 복구 여부 확인
        assert Thread.currentThread().isInterrupted();
        // 인터럽트 플래그 초기화 (다음 테스트에 영향 없도록)
        Thread.interrupted();
    }

    @Test
    void run_unexpectedException_doesNotPropagate() throws InterruptedException {
        doThrow(new RuntimeException("api error")).when(useCase).execute();

        // 예외가 외부로 전파되지 않아야 함
        scheduler.run();

        verify(useCase).execute();
    }
}
