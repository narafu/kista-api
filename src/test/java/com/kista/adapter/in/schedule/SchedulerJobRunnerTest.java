package com.kista.adapter.in.schedule;

import com.kista.domain.port.out.NotifyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SchedulerJobRunnerTest {

    @Mock NotifyPort notifyPort;
    SchedulerJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SchedulerJobRunner(notifyPort);
    }

    @Test
    void 인터럽트_발생_시_rethrow하여_호출측이_락을_해제할_수_있다() {
        // 인터럽트를 삼키면 SchedulerLockService가 성공으로 간주해 락을 2~3h 유지 → 수동 복구 불가
        assertThatThrownBy(() -> runner.run("마감 매매 스케쥴러", List::of,
                contexts -> { throw new InterruptedException("배포 재시작"); }))
                .isInstanceOf(InterruptedException.class);
        verify(notifyPort).notifyError(any(InterruptedException.class));
        verify(notifyPort, never()).notifyInfo("마감 매매 스케쥴러 완료");
    }

    @Test
    void 일반_예외_시_완료_알림을_보내지_않는다() throws InterruptedException {
        runner.run("마감 매매 스케쥴러", List::of,
                contexts -> { throw new IllegalStateException("boom"); });
        verify(notifyPort).notifyError(any(IllegalStateException.class));
        verify(notifyPort, never()).notifyInfo("마감 매매 스케쥴러 완료");
    }

    @Test
    void Runnable_작업_예외_시_완료_알림을_보내지_않는다() {
        // 현재 코드는 catch 후에도 무조건 "완료" 알림 발송 — 거짓 성공 보고
        runner.run("FearGreed 수집", () -> { throw new IllegalStateException("boom"); });
        verify(notifyPort).notifyError(any(IllegalStateException.class));
        verify(notifyPort, never()).notifyInfo("FearGreed 수집 완료");
    }

    @Test
    void 정상_완료_시_시작과_완료_알림을_모두_보낸다() throws InterruptedException {
        runner.run("장 개시 스케쥴러", List::of, contexts -> {});
        verify(notifyPort).notifyInfo("장 개시 스케쥴러 시작");
        verify(notifyPort).notifyInfo("장 개시 스케쥴러 완료");
    }
}
