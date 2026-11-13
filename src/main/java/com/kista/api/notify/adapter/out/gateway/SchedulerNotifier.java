package com.kista.api.notify.adapter.out.gateway;

import com.kista.shared.platform.scheduling.SchedulerLifecycleEvent;
import com.kista.api.notify.application.port.output.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

// SchedulerJobRunner가 발행하는 생명주기 이벤트를 구독해 기존 NotifyPort로 중계한다
// (AlertNotifier/PrivacyAlertNotifier와 동일 패턴).
// 스케쥴러는 @Transactional 밖에서 실행되므로 fallbackExecution=true로 발행 시점에 동기 실행되게 한다.
@Component
@RequiredArgsConstructor
public class SchedulerNotifier {

    // trading-core 잡 이름 — 지금은 trading-core에 이 리스너가 없어 매매 잡 시작·완료·실패 알림이 나가지 않는다.
    // 두 애플리케이션을 한 컨텍스트로 합쳐도 그 동작을 유지하려고 이 잡들은 건너뛴다(지침서 05 P7). 알림을 받고 싶으면 여기서 뺀다.
    // trading-core에 잡이 생기거나 이름이 바뀌면 SchedulerNotifierTradingJobsTest가 실패한다
    static final Set<String> TRADING_JOBS = Set.of(
            "장 개시 스케쥴러", "장 개시 스케쥴러 수동",
            "마감 매매 스케쥴러", "마감 매매 스케쥴러 수동", "마감 리포트 재개",
            "기동 시 캘린더 초기 적재", "연간 캘린더 갱신 스케쥴러", "월간 캘린더 갱신 스케쥴러");

    private final NotifyPort notifyPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onSchedulerLifecycle(SchedulerLifecycleEvent event) {
        if (TRADING_JOBS.contains(event.jobName())) return;
        switch (event.phase()) {
            case STARTED -> notifyPort.notifyInfo(event.jobName() + " 시작");
            case COMPLETED -> notifyPort.notifyInfo(event.jobName() + " 완료");
            // 전체 스택은 SchedulerJobRunner.log.error가 남기므로 여기선 message만 래핑 (EPR 직렬화 안전)
            case FAILED -> notifyPort.notifyError(new RuntimeException("[" + event.jobName() + "] " + event.errorMessage()));
        }
    }
}
