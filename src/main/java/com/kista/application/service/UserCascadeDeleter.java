package com.kista.application.service;

import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.TradingCyclePort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

// UserService.deleteMe / AdminService.deleteUser 공통 cascade 삭제 — 사이클 → 계좌 → 사용자 순 (FK CASCADE 대체)
@Component
@RequiredArgsConstructor
class UserCascadeDeleter {

    private final TradingCyclePort cyclePort;
    private final AccountPort accountPort;
    private final UserPort userPort;

    void deleteCascade(UUID userId) {
        cyclePort.deleteByUserId(userId);
        accountPort.deleteByUserId(userId);
        userPort.delete(userId);
    }
}
