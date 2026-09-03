package com.kista.notify.adapter.out.gateway;

import com.kista.privacy.application.event.PrivacyAlertRaisedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// privacy가 발행하는 FIDA 기준 매매표 검증 경보를 구독해 기존 NotifyPort로 중계한다(MarketAlertNotifier와 동일 패턴).
// PrivacyService.executeFidaOrder()가 @Transactional 없이 이벤트 발행 직후 예외를 던지거나 저장을 진행하므로
// fallbackExecution=true로 트랜잭션이 없으면 발행 시점에 동기 실행되게 한다.
@Component
@RequiredArgsConstructor
public class PrivacyAlertNotifier {

    private final NotifyPort notifyPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onPrivacyAlert(PrivacyAlertRaisedEvent event) {
        // BLOCKING은 관리자 오류 채널, WARNING은 정보 채널로 라우팅
        if (event.severity() == PrivacyAlertRaisedEvent.Severity.BLOCKING) {
            notifyPort.notifyError(new RuntimeException(event.message()));
        } else {
            notifyPort.notifyInfo(event.message());
        }
    }
}
