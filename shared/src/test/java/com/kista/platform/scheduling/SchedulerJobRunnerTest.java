package com.kista.platform.scheduling;

import com.kista.platform.scheduling.SchedulerLifecycleEvent.Phase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SchedulerJobRunnerTest {

    @Mock ApplicationEventPublisher events;
    SchedulerJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SchedulerJobRunner(events);
    }

    @Test
    void 정상_완료_시_STARTED와_COMPLETED_이벤트를_발행한다() throws InterruptedException {
        runner.run("장 개시 스케쥴러", List::of, contexts -> {});

        ArgumentCaptor<SchedulerLifecycleEvent> captor = ArgumentCaptor.forClass(SchedulerLifecycleEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(SchedulerLifecycleEvent::phase)
                .containsExactly(Phase.STARTED, Phase.COMPLETED);
        assertThat(captor.getAllValues()).allSatisfy(e -> assertThat(e.jobName()).isEqualTo("장 개시 스케쥴러"));
    }

    @Test
    void 일반_예외_시_FAILED_이벤트만_발행하고_COMPLETED는_발행하지_않는다() throws InterruptedException {
        runner.run("마감 매매 스케쥴러", List::of,
                contexts -> { throw new IllegalStateException("boom"); });

        ArgumentCaptor<SchedulerLifecycleEvent> captor = ArgumentCaptor.forClass(SchedulerLifecycleEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(SchedulerLifecycleEvent::phase)
                .containsExactly(Phase.STARTED, Phase.FAILED);
        assertThat(captor.getAllValues().get(1).errorMessage()).isEqualTo("boom");
    }

    @Test
    void 인터럽트_발생_시_FAILED_이벤트_발행_후_rethrow한다() {
        // 인터럽트를 삼키면 SchedulerLockService가 성공으로 간주해 락을 2~3h 유지 → 수동 복구 불가
        assertThatThrownBy(() -> runner.run("마감 매매 스케쥴러", List::of,
                contexts -> { throw new InterruptedException("배포 재시작"); }))
                .isInstanceOf(InterruptedException.class);

        ArgumentCaptor<SchedulerLifecycleEvent> captor = ArgumentCaptor.forClass(SchedulerLifecycleEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues().get(1).phase()).isEqualTo(Phase.FAILED);
    }

    @Test
    void Runnable_작업_예외_시_FAILED_이벤트만_발행한다() {
        runner.run("FearGreed 수집", () -> { throw new IllegalStateException("boom"); });

        ArgumentCaptor<SchedulerLifecycleEvent> captor = ArgumentCaptor.forClass(SchedulerLifecycleEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(SchedulerLifecycleEvent::phase)
                .containsExactly(Phase.STARTED, Phase.FAILED);
    }
}
