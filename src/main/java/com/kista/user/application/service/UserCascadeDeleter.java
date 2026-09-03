package com.kista.user.application.service;

import com.kista.user.application.event.UserDeletedEvent;
import com.kista.account.application.port.output.AccountPort;
import com.kista.user.application.port.output.BlacklistPort;
import com.kista.user.application.port.output.RefreshTokenPort;
import com.kista.user.application.port.output.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// UserService.deleteMe / AdminService.deleteUser 공통 cascade 삭제 진입점.
// trading(cyclePosition/strategyCycle)·finance(6종+그룹승계)·strategy-config(strategy)는
// UserDeletedEvent 발행 후 각 모듈이 독립 리스너로 자체 soft-delete한다(EPR 재시도 보장) —
// 모듈 경계를 넘는 직접 포트 호출을 없애 user↔trading·user↔finance·user↔strategy-config 순환을
// 제거했다. account는 아직 이벤트 전환 대상이 아니라 이번 스코프에서는 직접 호출을 유지한다.
@Component
@RequiredArgsConstructor
public class UserCascadeDeleter {

    private final AccountPort accountPort;
    private final UserPort userPort;
    private final RefreshTokenPort refreshTokenPort;
    private final BlacklistPort blacklistPort;
    private final ApplicationEventPublisher eventPublisher;

    private static final Duration AT_TTL = Duration.ofMinutes(15);

    public void deleteCascade(UUID userId) {
        accountPort.deleteByUserId(userId);

        userPort.delete(userId);
        refreshTokenPort.deleteAllByUserId(userId);
        blacklistPort.add(userId, AT_TTL);
        // 커밋 후 발행 — trading/finance/notify/strategy-config 리스너가 각자 소유 데이터를 독립적으로 정리
        eventPublisher.publishEvent(new UserDeletedEvent(userId));
    }
}
