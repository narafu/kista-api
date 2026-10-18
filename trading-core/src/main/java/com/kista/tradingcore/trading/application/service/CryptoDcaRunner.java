package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.tradingcore.trading.application.port.output.TradingUserProfilePort;
import com.kista.tradingcore.trading.application.usecase.CryptoDcaExecutionUseCase;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.trading.domain.model.TradingUserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.stream.Collectors;

// 코인 DCA 틱 러너 — 계좌별로 sync → executeAccount, 한 계좌 실패가 다른 계좌를 막지 않는다
// 컨텍스트 조립은 BatchContextFactory와 같은 규칙(실패 전략은 reportError 후 제외)이나 그쪽은 코인을 제외하므로 재사용하지 않는다
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoDcaRunner implements CryptoDcaExecutionUseCase {

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final StrategyCyclePort strategyCyclePort;
    private final TradingUserProfilePort tradingUserProfilePort;
    private final StrategyDcaDetailPort strategyDcaDetailPort;
    private final CryptoExchanges cryptoExchanges;
    private final CryptoOrderSynchronizer synchronizer;
    private final CryptoDcaRoundExecutor roundExecutor;
    private final TradingErrorReportPort errorReportPort; // 조립·계좌 실행 실패 관리자 알림
    // ponytail: in-memory, resets on restart — persist if restart floods matter
    private final Map<String, LocalDate> lastReported = new ConcurrentHashMap<>(); // (대상|메시지) → 마지막 알림 KST 일자

    @Override
    public void runTick(LocalDateTime nowKst) {
        LocalDate today = nowKst.toLocalDate();
        lastReported.values().removeIf(d -> !d.equals(today)); // 지난 날짜 항목 정리
        // 코인 DCA 전략만 — 계좌 판정은 account 조회 후
        List<Strategy> dcaStrategies = strategyPort.findAllActive().stream()
                .filter(s -> s.type() == StrategyType.DCA).toList();
        if (dcaStrategies.isEmpty()) return;
        Map<UUID, TradingAccount> accountsById = accountPort.findAll().stream()
                .map(TradingAccount::from)
                .filter(a -> a.broker().getMarket() == Market.CRYPTO_KRW)
                .collect(Collectors.toMap(TradingAccount::id, a -> a));
        List<Strategy> targets = dcaStrategies.stream().filter(s -> accountsById.containsKey(s.accountId())).toList();
        if (targets.isEmpty()) return;

        var strategyIds = targets.stream().map(Strategy::id).toList();
        Map<UUID, StrategyCycle> cyclesById = strategyCyclePort.findLatestByStrategyIds(strategyIds);
        Map<UUID, DcaConfig> configsById = strategyDcaDetailPort.findActiveByStrategyIds(strategyIds);
        Map<UUID, TradingUserProfile> profiles = tradingUserProfilePort.findAllByUserIds(
                accountsById.values().stream().map(TradingAccount::userId).distinct().toList());

        // accountId 정렬 — 계좌 실행 순서 고정
        Map<UUID, List<BatchContext>> byAccount = new TreeMap<>();
        for (Strategy strategy : targets) {
            try {
                BatchContext ctx = buildContext(strategy, accountsById, cyclesById, configsById, profiles);
                if (ctx != null) byAccount.computeIfAbsent(strategy.accountId(), k -> new ArrayList<>()).add(ctx);
            } catch (RuntimeException e) {
                log.error("[strategyId={}] 코인 DCA 컨텍스트 조립 오류", strategy.id(), e);
                reportOncePerDay(strategy.id(), e, today);
            }
        }

        for (var entry : byAccount.entrySet()) {
            List<BatchContext> contexts = entry.getValue();
            // 이 계좌의 설정만 전달
            Map<UUID, DcaConfig> accountConfigs = contexts.stream()
                    .collect(Collectors.toMap(c -> c.strategy().id(), c -> configsById.get(c.strategy().id())));
            try {
                var exchange = cryptoExchanges.of(contexts.get(0).account().broker());
                synchronizer.sync(exchange, contexts, accountConfigs, nowKst); // 신규 실행 전 미확정 주문 복구
                roundExecutor.executeAccount(exchange, contexts, accountConfigs, nowKst);
            } catch (RuntimeException e) {
                log.error("[accountId={}] 코인 DCA 실행 오류", entry.getKey(), e);
                reportOncePerDay(entry.getKey(), e, today);
            }
        }
    }

    // 같은 (대상, 메시지)는 KST 하루 1회만 관리자 알림 — 10분 틱마다 반복 발송 방지
    private void reportOncePerDay(UUID target, RuntimeException e, LocalDate today) {
        String key = target + "|" + (e.getMessage() != null ? e.getMessage() : e.getClass().getName());
        if (!today.equals(lastReported.put(key, today))) errorReportPort.reportError(e);
    }

    // 조립 불가(사이클·설정·프로필 없음, 종료 사이클)는 예외 — 호출부가 알림 후 제외
    private BatchContext buildContext(Strategy strategy, Map<UUID, TradingAccount> accountsById,
                                      Map<UUID, StrategyCycle> cyclesById, Map<UUID, DcaConfig> configsById,
                                      Map<UUID, TradingUserProfile> profiles) {
        StrategyCycle cycle = cyclesById.get(strategy.id());
        if (cycle == null) throw new IllegalStateException("활성 사이클 없음: strategyId=" + strategy.id());
        if (cycle.endDate() != null) {
            throw new IllegalStateException("[좀비 사이클] 최신 사이클이 이미 종료됨(endDate=" + cycle.endDate()
                    + ") — 전략 확인 필요: strategyId=" + strategy.id());
        }
        if (!configsById.containsKey(strategy.id())) {
            throw new NoSuchElementException("DCA 설정 없음: strategyId=" + strategy.id());
        }
        TradingAccount account = accountsById.get(strategy.accountId());
        TradingUserProfile profile = profiles.get(account.userId());
        if (profile == null) throw new NoSuchElementException("사용자를 찾을 수 없습니다: " + account.userId());
        return new BatchContext(strategy, cycle, account, profile);
    }
}
