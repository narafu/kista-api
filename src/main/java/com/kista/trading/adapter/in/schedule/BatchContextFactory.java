package com.kista.trading.adapter.in.schedule;

import com.kista.common.CycleLookups;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.user.domain.model.User;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.TradingErrorReportPort;
import com.kista.user.application.port.output.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

// 전략 목록 → BatchContext 목록 변환 — 조회 실패한 전략은 skip + notifyError
@Slf4j
@Component
@RequiredArgsConstructor
class BatchContextFactory {

    private final AccountPort accountPort;
    private final StrategyCyclePort strategyCyclePort;
    private final UserPort userPort;
    private final TradingErrorReportPort errorReportPort; // 조회 실패 관리자 알림 (출력 포트 경유 — application.event/service 미직접의존)

    // 전략별 현재 사이클·계좌·사용자 조회 — 조회 실패한 전략은 skip + notifyError
    List<BatchContext> buildAll(List<Strategy> strategies) {
        // 계좌·사용자·사이클은 전략 수만큼 개별 조회하지 않고 배치로 1회씩 조회해 N+1 제거
        Map<UUID, Account> accountsById = accountPort.findAll().stream()
                .collect(Collectors.toMap(Account::id, a -> a));
        Map<UUID, User> usersById = userPort.findAll().stream()
                .collect(Collectors.toMap(User::id, u -> u));
        var strategyIds = strategies.stream().map(Strategy::id).toList();
        Map<UUID, StrategyCycle> cyclesById = strategyCyclePort.findLatestByStrategyIds(strategyIds);

        List<BatchContext> contexts = new ArrayList<>();
        for (Strategy strategy : strategies) {
            try {
                StrategyCycle currentCycle = cyclesById.get(strategy.id());
                if (currentCycle == null) {
                    throw new IllegalStateException("활성 사이클 없음: strategyId=" + strategy.id());
                }
                // 종료된 사이클 재선택 차단 — rotation 실패(잔고 조회 오류 등) 시 새 사이클 없이 종료 사이클만 남는 좀비 상태
                if (currentCycle.endDate() != null) {
                    IllegalStateException zombie = new IllegalStateException(
                            "[좀비 사이클] 최신 사이클이 이미 종료됨(endDate=" + currentCycle.endDate()
                                    + ") — rotation 실패 추정, 전략 확인 후 수동 재등록 필요: strategyId=" + strategy.id());
                    log.error(zombie.getMessage());
                    errorReportPort.reportError(zombie);
                    continue;
                }
                Account account = accountsById.get(strategy.accountId());
                if (account == null) {
                    throw new NoSuchElementException("계좌를 찾을 수 없습니다: " + strategy.accountId());
                }
                User user = usersById.get(account.userId());
                if (user == null) {
                    throw new NoSuchElementException("사용자를 찾을 수 없습니다: " + account.userId());
                }
                contexts.add(new BatchContext(strategy, currentCycle, account, user));
            } catch (Exception e) {
                log.error("[strategyId={}] 컨텍스트 조회 오류: {}", strategy.id(), e.getMessage(), e);
                errorReportPort.reportError(e);
            }
        }
        return contexts;
    }
}
