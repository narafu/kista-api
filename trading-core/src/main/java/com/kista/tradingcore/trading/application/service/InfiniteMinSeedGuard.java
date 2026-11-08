package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.trading.application.port.output.StrategyInfiniteDetailPort;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyInfiniteDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

// INFINITE 최소 시드 검사 — 등록·시드 수정·인출이 공유한다. 최소액 산출(전일종가 HTTP)과 비교를 나눈 이유: HTTP는 @Transactional 밖에서 먼저 하고 비교는 어디서든 한다
@Component
@RequiredArgsConstructor
class InfiniteMinSeedGuard {

    private final BrokerPricePort brokerPricePort;
    private final CycleOrderStrategies cycleStrategies;               // minRequiredDeposit 산출
    private final StrategyInfiniteDetailPort strategyInfiniteDetailPort; // 기존 전략의 divisionCount

    // 최소 시드(전일종가×분할×2) — 전일종가 조회 실패는 null(fail-open: 검사 건너뜀). knownPrice가 있으면 재조회하지 않는다. 트랜잭션 밖에서 호출한다
    BigDecimal minSeed(BrokerAccountRef broker, StrategyTicker ticker, int divisionCount, BigDecimal knownPrice) {
        BigDecimal price = knownPrice != null ? knownPrice
                : BrokerCallGuard.tryGet("최소 시드 검증용 전일종가 조회: ticker=" + ticker,
                        () -> brokerPricePort.getPrevClose(ticker, broker)).orElse(null);
        if (price == null) return null;
        return cycleStrategies.of(StrategyType.INFINITE).minRequiredDeposit(price, null, divisionCount);
    }

    // 기존 전략용 — INFINITE가 아니거나 활성 버전 상세가 없으면 null(검사 없음)
    BigDecimal minSeed(Strategy strategy, BrokerAccountRef broker) {
        if (!strategy.isInfinite()) return null;
        return strategyInfiniteDetailPort.findActiveByStrategyId(strategy.id())
                .map(StrategyInfiniteDetail::divisionCount)
                .map(division -> minSeed(broker, strategy.ticker(), division, null))
                .orElse(null);
    }

    // min이 null(검사 건너뜀)이면 통과, 시드가 min 미만이면 거부
    static void requireAtLeast(BigDecimal min, BigDecimal seed) {
        if (min != null && seed.compareTo(min) < 0) {
            // 금액 표기는 kista-ui fmtUsd와 같은 #,##0.00 (JVM 로케일 무관)
            throw new IllegalArgumentException("최소 투자금 $%s 이상이 필요합니다.".formatted(String.format(Locale.US, "%,.2f", min)));
        }
    }
}
