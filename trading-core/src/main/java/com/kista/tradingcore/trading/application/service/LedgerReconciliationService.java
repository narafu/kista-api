package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.application.port.output.PortfolioPort;
import com.kista.tradingcore.broker.domain.model.PresentBalanceResult;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.tradingcore.trading.application.usecase.LedgerReconciliationUseCase;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.reconciliation.LedgerReconciliation;
import com.kista.tradingcore.trading.domain.model.reconciliation.LedgerReconciliation.Finding;
import com.kista.tradingcore.trading.domain.model.reconciliation.LedgerReconciliation.StrategyLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

// 전략 원장 대사 — 실잔고는 관리자 알림에만 쓰고 매매 판단·원장에는 절대 반영하지 않는다(쓰기 없음)
// 외부 브로커 호출이 있고 쓰기가 없어 트랜잭션을 열지 않는다
@Slf4j
@Service
@RequiredArgsConstructor
class LedgerReconciliationService implements LedgerReconciliationUseCase {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final PortfolioPort portfolioPort;            // 실계좌 총 잔고수량(미체결 매도 미차감)
    private final TradingErrorReportPort errorReportPort; // 관리자 운영 알림

    @Override
    public void reconcileAll() {
        // 비코인 계좌 → 전략 → 진행 중 사이클 → 최신 포지션 일괄 조회
        List<Account> accounts = accountPort.findAll().stream()
                .filter(a -> !a.broker().getMarket().isCrypto()).toList();
        Map<UUID, List<Strategy>> strategiesByAccount = strategyPort.findByAccountIds(accounts.stream().map(Account::id).toList());
        List<Strategy> strategies = strategiesByAccount.values().stream().flatMap(List::stream).toList();
        Map<UUID, StrategyCycle> cycles = strategyCyclePort.findLatestByStrategyIds(strategies.stream().map(Strategy::id).toList());
        Map<UUID, CyclePosition> positions = cyclePositionPort.findLatestByCycleIds(cycles.values().stream()
                .filter(c -> c.endDate() == null).map(StrategyCycle::id).toList());

        for (Account account : accounts) {
            List<StrategyLedger> ledgers = strategiesByAccount.getOrDefault(account.id(), List.of()).stream()
                    .map(s -> toLedger(s, cycles.get(s.id()), positions)).filter(Objects::nonNull).toList();
            if (ledgers.isEmpty()) continue; // 대조할 원장 없음 — 브로커 호출도 생략
            try {
                List<Finding> findings = LedgerReconciliation.check(ledgers, brokerHoldings(account));
                if (!findings.isEmpty()) {
                    errorReportPort.reportError(new IllegalStateException("[원장 대사] " + label(account) + "\n"
                            + findings.stream().map(Finding::message).collect(Collectors.joining("\n"))));
                }
            } catch (RuntimeException e) {
                // 한 계좌 조회 실패가 다른 계좌 검사를 막지 않는다
                log.warn("[원장 대사] 브로커 잔고 조회 실패: accountId={}", account.id(), e);
                errorReportPort.reportError(new IllegalStateException("[원장 대사] 브로커 잔고 조회 실패: " + label(account), e));
            }
        }
    }

    // 진행 중 사이클의 최신 포지션만 — 종료 사이클·포지션 없음은 제외(null)
    private static StrategyLedger toLedger(Strategy s, StrategyCycle cycle, Map<UUID, CyclePosition> positions) {
        if (cycle == null || cycle.endDate() != null) return null;
        CyclePosition p = positions.get(cycle.id());
        if (p == null) return null;
        return new StrategyLedger(s.id(), s.type(), s.ticker(), p.usdDeposit(), p.holdings());
    }

    // MOCK은 잔고가 원장 합에서 만들어져 바깥 기준이 없다 — null이면 불변식만 검사
    private Map<StrategyTicker, Integer> brokerHoldings(Account account) {
        if (account.broker() == Broker.MOCK) return null;
        return portfolioPort.getPresentBalance(account.toBrokerRef()).items().stream()
                .collect(Collectors.toMap(PresentBalanceResult.Item::ticker, PresentBalanceResult.Item::holdings, Integer::sum));
    }

    // 계좌번호는 싣지 않는다(마스킹 규칙) — 별칭과 id만
    private static String label(Account account) {
        return account.nickname() + " (accountId=" + account.id() + ")";
    }
}
