package com.kista.strategyconfig.application.service;

import com.kista.strategyconfig.application.port.output.StrategyPort;
import com.kista.user.application.event.UserDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 사용자 탈퇴 cascade — strategy-config 소유 데이터(strategy)를 독립적으로 soft-delete.
// UserCascadeDeleter가 StrategyPort를 직접 호출하던 것을 이벤트 구독으로 전환(user↔strategy-config 순환 해소).
// finance/trading의 기존 cascade 리스너와 동일 패턴 — 자기 데이터는 자기 모듈이 지운다.
@Component
@RequiredArgsConstructor
class StrategyUserCascadeListener {

    private final StrategyPort strategyPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserDeleted(UserDeletedEvent event) {
        strategyPort.deleteByUserId(event.userId());
    }
}
