package com.kista.notify.adapter.out.gateway;

import com.kista.market.application.event.FearGreedFetchFailedEvent;
import com.kista.benchmark.application.event.BenchmarkAlertRaisedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// market/benchmark가 발행하는 외부 데이터 수집 실패 이벤트를 구독해 기존 NotifyPort.notifyError를 그대로 호출한다.
// (구 MarketAlertNotifier + StatsAlertNotifier 병합 — 두 리스너 모두 1-메서드 + NotifyPort 단일 호출로 동일했다)
// 두 서비스의 fetchAndSave()에 @Transactional이 없어 phase 미지정 + fallbackExecution=true로
// 트랜잭션이 있으면 커밋 후, 없으면 즉시 동기 실행되게 한다(SchedulerNotifier와 동일 이유).
@Component
@RequiredArgsConstructor
public class AlertNotifier {

    private final NotifyPort notifyPort; // 관리자 텔레그램 알림 발송 포트

    @TransactionalEventListener(fallbackExecution = true)
    public void onFearGreedFetchFailed(FearGreedFetchFailedEvent event) {
        notifyPort.notifyError(new RuntimeException(event.message()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onBenchmarkAlertRaised(BenchmarkAlertRaisedEvent event) {
        notifyPort.notifyError(new RuntimeException(event.message()));
    }
}
