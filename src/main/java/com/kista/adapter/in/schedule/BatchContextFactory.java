package com.kista.adapter.in.schedule;

import com.kista.common.CycleLookups;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// 전략 목록 → BatchContext 목록 변환 — 조회 실패한 전략은 skip + notifyError
@Slf4j
@Component
@RequiredArgsConstructor
class BatchContextFactory {

    private final AccountPort accountPort;
    private final StrategyCyclePort strategyCyclePort;
    private final UserPort userPort;
    private final NotifyPort notifyPort;

    // 전략별 현재 사이클·계좌·사용자 조회 — 조회 실패한 전략은 skip + notifyError
    List<BatchContext> buildAll(List<Strategy> strategies) {
        List<BatchContext> contexts = new ArrayList<>();
        for (Strategy strategy : strategies) {
            try {
                StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
                Account account = accountPort.findByIdOrThrow(strategy.accountId());
                User user = userPort.findByIdOrThrow(account.userId());
                contexts.add(new BatchContext(strategy, currentCycle, account, user));
            } catch (Exception e) {
                log.error("[strategyId={}] 컨텍스트 조회 오류: {}", strategy.id(), e.getMessage(), e);
                notifyPort.notifyError(e);
            }
        }
        return contexts;
    }
}
