package com.kista.application.service.user;

import com.kista.application.event.UserDeletedEvent;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// UserService.deleteMe / AdminService.deleteUser 공통 cascade 삭제 — 포지션 → 사이클 → 전략 → 계좌 → 사용자 순 (FK CASCADE 대체)
@Component
@RequiredArgsConstructor
public class UserCascadeDeleter {

    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final AccountPort accountPort;
    private final AssetPort assetPort;                       // 자동매매와 무관한 개인 자산 기록 — 계좌와 독립적으로 정리
    private final AssetMonthlyCheckPort assetMonthlyCheckPort;
    private final UserPort userPort;
    private final RefreshTokenPort refreshTokenPort; // 모든 RT 삭제
    private final BlacklistPort blacklistPort;        // 남은 AT 즉시 차단
    private final ApplicationEventPublisher eventPublisher;

    private static final Duration AT_TTL = Duration.ofMinutes(15); // AT 만료까지 차단 유지

    public void deleteCascade(UUID userId) {
        // CyclePosition → StrategyCycle → Strategy → Account → User 순서로 소프트 삭제
        cyclePositionPort.deleteByUserId(userId);
        strategyCyclePort.deleteByUserId(userId);
        strategyPort.deleteByUserId(userId);
        accountPort.deleteByUserId(userId);
        assetPort.deleteByUserId(userId);
        assetMonthlyCheckPort.deleteByUserId(userId);
        userPort.delete(userId);
        // 인증 정리 — RT 전체 삭제 후 AT 즉시 차단
        refreshTokenPort.deleteAllByUserId(userId);
        blacklistPort.add(userId, AT_TTL);
        // 삭제 완료 이벤트 발행 (트랜잭션 커밋 후 리스너 실행)
        eventPublisher.publishEvent(new UserDeletedEvent(userId));
    }
}
