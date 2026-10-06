package com.kista.user.application.service;

import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.user.application.port.output.BlacklistPort;
import com.kista.user.application.port.output.RefreshTokenPort;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.user.domain.auth.TokenConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// UserService.deleteMe / AdminService.deleteUser 공통 cascade 삭제 진입점.
// trading(cyclePosition/strategyCycle)·finance(6종+그룹승계)·strategy(strategy)·account(accounts)는
// UserDeletedEvent 발행 후 각 모듈이 독립 리스너로 자체 soft-delete한다(EPR 재시도 보장) —
// 모듈 경계를 넘는 직접 포트 호출을 없애 user↔trading·user↔finance·user↔account 순환을 제거했다.
// trading-core 쪽 cascade(계좌 포함)는 trading UserCascadeListener가 AFTER_COMMIT으로 처리하므로
// 탈퇴 응답 시점엔 아직 미완료일 수 있다(동기 → 최종적 일관성 전환).
@Component
@RequiredArgsConstructor
public class UserCascadeDeleter {

    private final UserPort userPort;
    private final UserSettingsPort userSettingsPort; // 탈퇴 시 설정·알림 선호도 하드 삭제 (user 소유라 이벤트 없이 같은 트랜잭션)
    private final RefreshTokenPort refreshTokenPort;
    private final BlacklistPort blacklistPort;
    private final ApplicationEventPublisher eventPublisher;

    private static final Duration AT_TTL = TokenConstants.AT_TTL; // AT 수명 전체 — 탈퇴 전 발급된 AT가 만료될 때까지 차단

    public void deleteCascade(UUID userId) {
        userPort.delete(userId);
        userSettingsPort.deleteByUserId(userId);
        refreshTokenPort.deleteAllByUserId(userId);
        blacklistPort.add(userId, AT_TTL);
        // 커밋 후 발행 — trading/finance/notify/strategy-config 리스너가 각자 소유 데이터를 독립적으로 정리
        eventPublisher.publishEvent(new UserDeletedEvent(userId));
    }
}
