package com.kista.user.application.service;

import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// ADMIN seed idempotent promote 전용 트랜잭션 경계.
// UserService.login()이 @Transactional(NOT_SUPPORTED)이라 그 안에서 직접 저장·발행하면
// UserNotifyProfileChangedEvent가 앰비언트 트랜잭션 없이 발행돼 fallbackExecution 리스너가
// 동기 실행되고, 리스너 예외가 로그인 요청까지 전파된다. 그 경우 role 저장은 이미 auto-commit돼
// 재시도해도 promote 분기가 no-op이라 복제본 row가 영영 안 생긴다.
// 별도 빈으로 분리해 REQUIRED 트랜잭션을 열면 register()와 동일하게 AFTER_COMMIT + EPR 재시도가 적용된다.
@Component
@RequiredArgsConstructor
class AdminSeedPromoter {

    private final UserPort userPort;
    private final UserNotifyProfilePublisher userNotifyProfilePublisher;

    @Transactional
    User promote(User user) {
        User promoted = userPort.save(user.withStatus(UserStatus.ACTIVE).withRole(UserRole.ADMIN));
        userNotifyProfilePublisher.publishStatusChanged(promoted);
        return promoted;
    }
}
