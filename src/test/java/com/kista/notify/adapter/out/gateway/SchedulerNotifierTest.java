package com.kista.notify.adapter.out.gateway;

import com.kista.adapter.in.schedule.SchedulerLifecycleEvent;
import com.kista.notify.application.port.output.NotifyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SchedulerNotifierTest {

    @Mock NotifyPort notifyPort;

    @Test
    void STARTED는_시작_정보_알림() {
        new SchedulerNotifier(notifyPort).onSchedulerLifecycle(SchedulerLifecycleEvent.started("장 개시 스케쥴러"));
        verify(notifyPort).notifyInfo("장 개시 스케쥴러 시작");
    }

    @Test
    void COMPLETED는_완료_정보_알림() {
        new SchedulerNotifier(notifyPort).onSchedulerLifecycle(SchedulerLifecycleEvent.completed("장 개시 스케쥴러"));
        verify(notifyPort).notifyInfo("장 개시 스케쥴러 완료");
    }

    @Test
    void FAILED는_jobName_prefix를_붙여_오류_알림() {
        new SchedulerNotifier(notifyPort).onSchedulerLifecycle(
                SchedulerLifecycleEvent.failed("마감 매매 스케쥴러", new IllegalStateException("boom")));
        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        verify(notifyPort).notifyError(captor.capture());
        assertThat(captor.getValue().getMessage()).isEqualTo("[마감 매매 스케쥴러] boom");
    }
}
