package com.kista.api.notify.adapter.out.gateway;

import com.kista.shared.platform.scheduling.SchedulerLifecycleEvent;
import com.kista.api.notify.application.port.output.NotifyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SchedulerNotifierTest {

    @Mock NotifyPort notifyPort;

    @Test
    void STARTED는_시작_정보_알림() {
        new SchedulerNotifier(notifyPort).onSchedulerLifecycle(SchedulerLifecycleEvent.started("공포탐욕지수 수집 스케쥴러"));
        verify(notifyPort).notifyInfo("공포탐욕지수 수집 스케쥴러 시작");
    }

    @Test
    void COMPLETED는_완료_정보_알림() {
        new SchedulerNotifier(notifyPort).onSchedulerLifecycle(SchedulerLifecycleEvent.completed("공포탐욕지수 수집 스케쥴러"));
        verify(notifyPort).notifyInfo("공포탐욕지수 수집 스케쥴러 완료");
    }

    @Test
    void FAILED는_jobName_prefix를_붙여_오류_알림() {
        new SchedulerNotifier(notifyPort).onSchedulerLifecycle(
                SchedulerLifecycleEvent.failed("공포탐욕지수 수집 스케쥴러", new IllegalStateException("boom")));
        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        verify(notifyPort).notifyError(captor.capture());
        assertThat(captor.getValue().getMessage()).isEqualTo("[공포탐욕지수 수집 스케쥴러] boom");
    }

    @Test
    void 매매_잡은_시작_완료_실패_모두_알리지_않는다() {
        SchedulerNotifier notifier = new SchedulerNotifier(notifyPort);

        notifier.onSchedulerLifecycle(SchedulerLifecycleEvent.started("장 개시 스케쥴러"));
        notifier.onSchedulerLifecycle(SchedulerLifecycleEvent.completed("마감 매매 스케쥴러"));
        notifier.onSchedulerLifecycle(SchedulerLifecycleEvent.failed("마감 리포트 재개", new IllegalStateException("boom")));

        verifyNoInteractions(notifyPort);
    }
}
