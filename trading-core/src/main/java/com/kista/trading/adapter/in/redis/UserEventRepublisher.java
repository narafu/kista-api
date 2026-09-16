package com.kista.trading.adapter.in.redis;

import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// UserEventStreamBridge에서 분리된 별도 빈 — @Transactional 메서드를 self-invocation으로
// 호출하면 Spring 프록시가 우회돼 트랜잭션이 전혀 열리지 않는다(같은 클래스 안에서
// this.method() 호출은 프록시를 거치지 않음). 호출자(UserEventStreamBridge)와 다른 빈으로
// 분리해 프록시를 정상적으로 경유하게 한다 — AFTER_COMMIT phase 리스너(UserCascadeListener 등,
// fallbackExecution 없음)가 실제로 발화하려면 여기서 실제 트랜잭션이 열려야 한다.
@Component
@RequiredArgsConstructor
class UserEventRepublisher {

    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    void republishUserDeleted(UserDeletedEvent event) {
        eventPublisher.publishEvent(event);
    }

    @Transactional
    void republishProfileChanged(UserNotifyProfileChangedEvent event) {
        eventPublisher.publishEvent(event);
    }
}
