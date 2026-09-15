package com.kista.user.application.service;

import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.user.application.port.output.BlacklistPort;
import com.kista.user.application.port.output.RefreshTokenPort;
import com.kista.user.application.port.output.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// UserService.deleteMe / AdminService.deleteUser 공통 cascade 삭제 진입점.
// trading(cyclePosition/strategyCycle)·finance(6종+그룹승계)·strategy(strategy)·account(accounts)는
// UserDeletedEvent 발행 후 각 모듈이 독립 리스너로 자체 soft-delete한다(EPR 재시도 보장) —
// 모듈 경계를 넘는 직접 포트 호출을 없애 user↔trading·user↔finance·user↔account 순환을 제거했다.
// account cascade는 AccountUserCascadeListener(trading-core)가 AFTER_COMMIT으로 처리하므로
// 탈퇴 응답 시점엔 아직 미완료일 수 있다(동기 → 최종적 일관성 전환).
@Component
@RequiredArgsConstructor
public class UserCascadeDeleter {

    private final UserPort userPort;
    private final RefreshTokenPort refreshTokenPort;
    private final BlacklistPort blacklistPort;
    private final ApplicationEventPublisher eventPublisher;

    private static final Duration AT_TTL = Duration.ofMinutes(15);

    public void deleteCascade(UUID userId) {
        userPort.delete(userId);
        refreshTokenPort.deleteAllByUserId(userId);
        blacklistPort.add(userId, AT_TTL);
        // 커밋 후 발행 — trading/finance/notify/strategy-config 리스너가 각자 소유 데이터를 독립적으로 정리
        eventPublisher.publishEvent(new UserDeletedEvent(userId));
    }
}
