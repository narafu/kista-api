package com.kista.notify.adapter.out.gateway;

import com.kista.market.application.event.FearGreedFetchFailedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// market이 발행하는 공포탐욕지수 수집 실패 이벤트를 구독해 기존 NotifyPort.notifyError를 그대로 호출한다.
// FearGreedService.fetchAndSave()에 @Transactional이 없어 phase 미지정 + fallbackExecution=true로
// 트랜잭션이 있으면 커밋 후, 없으면 즉시 동기 실행되게 한다(TradingAlertNotifier와 동일 이유).
@Component
@RequiredArgsConstructor
public class MarketAlertNotifier {

    private final NotifyPort notifyPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onFearGreedFetchFailed(FearGreedFetchFailedEvent event) {
        notifyPort.notifyError(new RuntimeException(event.message()));
    }
}
