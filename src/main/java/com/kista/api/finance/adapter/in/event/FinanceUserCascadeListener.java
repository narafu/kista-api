package com.kista.api.finance.adapter.in.event;

import com.kista.api.finance.application.usecase.FinanceUserDataEraseUseCase;
import com.kista.shared.kernel.UserDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 사용자 탈퇴 cascade — UserDeletedEvent 구독으로 finance 정리(FinanceUserDataEraseUseCase)를 실행한다(user↔finance 순환 해소).
// AFTER_COMMIT 시점엔 원본 트랜잭션이 종료돼 있으므로 REQUIRES_NEW로 새 트랜잭션을 연다 — 정리 전체가 한 트랜잭션.
// 클래스 FQCN·메서드명은 EPR listener_id다 — 옮기면 미완료 row가 고아가 된다(docker-infra.md "배포 직전 EPR 정리 런북").
@Component
@RequiredArgsConstructor
public class FinanceUserCascadeListener {

    private final FinanceUserDataEraseUseCase financeUserDataEraseUseCase; // 재무 기록·그룹 멤버십 정리

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserDeleted(UserDeletedEvent event) {
        financeUserDataEraseUseCase.eraseUserData(event.userId());
    }
}
