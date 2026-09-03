package com.kista.notify.adapter.out.gateway;

import com.kista.stats.application.event.StatsAlertRaisedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// stats가 발행하는 KB Land 벤치마크 수집 실패 이벤트를 구독해 기존 NotifyPort.notifyError를 그대로 호출한다.
// 두 서비스의 fetchAndSave()에 @Transactional이 없어 phase 미지정 + fallbackExecution=true로
// 트랜잭션이 있으면 커밋 후, 없으면 즉시 동기 실행되게 한다(MarketAlertNotifier와 동일 이유).
@Component
@RequiredArgsConstructor
public class StatsAlertNotifier {

    private final NotifyPort notifyPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onStatsAlertRaised(StatsAlertRaisedEvent event) {
        notifyPort.notifyError(new RuntimeException(event.message()));
    }
}
