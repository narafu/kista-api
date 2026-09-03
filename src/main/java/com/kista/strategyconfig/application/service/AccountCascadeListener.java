package com.kista.strategyconfig.application.service;

import com.kista.account.application.event.AccountDeletedEvent;
import com.kista.strategyconfig.application.port.output.StrategyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 계좌 삭제 cascade — strategy-config 소유 데이터(strategy)를 독립적으로 soft-delete.
// AccountService가 직접 포트를 호출하던 것을 이벤트 구독으로 전환(account↔strategy-config 순환 해소).
// AFTER_COMMIT 시점엔 원본 트랜잭션이 종료돼 있으므로, @Modifying soft-delete 쿼리를 위해
// REQUIRES_NEW로 새 트랜잭션을 연다.
@Component
@RequiredArgsConstructor
public class AccountCascadeListener {

    private final StrategyPort strategyPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAccountDeleted(AccountDeletedEvent event) {
        strategyPort.deleteByAccountId(event.accountId());
    }
}
