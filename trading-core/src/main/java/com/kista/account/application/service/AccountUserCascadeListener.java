package com.kista.account.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.sharedkernel.UserDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 사용자 탈퇴 cascade — UserCascadeDeleter(user)의 accountPort.deleteByUserId 직접 호출을 대체한다.
// trading(cyclePosition/strategyCycle)·finance·strategy cascade와 동일한 AFTER_COMMIT 이벤트 패턴
// (계좌 소프트 삭제가 동기 → 최종적 일관성으로 바뀐다. EPR이 실패분을 재시도한다.)
@Component
@RequiredArgsConstructor
class AccountUserCascadeListener {

    private final AccountPort accountPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserDeleted(UserDeletedEvent event) {
        accountPort.deleteByUserId(event.userId());
    }
}
