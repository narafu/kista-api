package com.kista.user.application.service;

import com.kista.user.application.event.ApprovalRequirementDisabledEvent;
import com.kista.sharedkernel.UserStatus;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.usecase.UserUseCase;
import com.kista.user.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 승인 설정 OFF 전환 cascade — admin이 UserUseCase를 직접 호출하던 것을 이벤트 구독으로 전환(admin↔user 빈 순환 해소).
// AFTER_COMMIT 시점엔 원본 트랜잭션이 종료돼 있으므로 REQUIRES_NEW로 새 트랜잭션을 연다.
@Component
@RequiredArgsConstructor
class ApprovalRequirementDisabledListener {

    private final UserPort userPort;
    private final UserUseCase userUseCase;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onApprovalRequirementDisabled(ApprovalRequirementDisabledEvent event) {
        userPort.findAllByStatus(UserStatus.PENDING).stream()
                .map(User::id)
                .forEach(userUseCase::approve);
    }
}
