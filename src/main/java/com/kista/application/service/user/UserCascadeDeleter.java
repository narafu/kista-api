package com.kista.application.service.user;

import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

// UserService.deleteMe / AdminService.deleteUser 공통 cascade 삭제 — 포지션 → 사이클 → 전략 → 계좌 → 사용자 순 (FK CASCADE 대체)
@Component
@RequiredArgsConstructor
public class UserCascadeDeleter {

    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final AccountPort accountPort;
    private final UserPort userPort;

    public void deleteCascade(UUID userId) {
        // CyclePosition → StrategyCycle → Strategy → Account → User 순서로 소프트 삭제
        cyclePositionPort.deleteByUserId(userId);
        strategyCyclePort.deleteByUserId(userId);
        strategyPort.deleteByUserId(userId);
        accountPort.deleteByUserId(userId);
        userPort.delete(userId);
    }
}
