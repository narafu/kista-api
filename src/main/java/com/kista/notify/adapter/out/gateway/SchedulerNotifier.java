package com.kista.notify.adapter.out.gateway;

import com.kista.adapter.in.schedule.SchedulerLifecycleEvent;
import com.kista.notify.application.port.output.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// SchedulerJobRunner가 발행하는 생명주기 이벤트를 구독해 기존 NotifyPort로 중계한다
// (MarketAlertNotifier/PrivacyAlertNotifier/StatsAlertNotifier와 동일 패턴 — 4번째 인스턴스).
// 스케쥴러는 @Transactional 밖에서 실행되므로 fallbackExecution=true로 발행 시점에 동기 실행되게 한다.
@Component
@RequiredArgsConstructor
public class SchedulerNotifier {

    private final NotifyPort notifyPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onSchedulerLifecycle(SchedulerLifecycleEvent event) {
        switch (event.phase()) {
            case STARTED -> notifyPort.notifyInfo(event.jobName() + " 시작");
            case COMPLETED -> notifyPort.notifyInfo(event.jobName() + " 완료");
            // 전체 스택은 SchedulerJobRunner.log.error가 남기므로 여기선 message만 래핑 (EPR 직렬화 안전)
            case FAILED -> notifyPort.notifyError(new RuntimeException("[" + event.jobName() + "] " + event.errorMessage()));
        }
    }
}
