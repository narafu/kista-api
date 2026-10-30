package com.kista.tradingcore.trading.application.service.support;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.Market;
import com.kista.tradingcore.broker.application.port.output.MarginPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

// 계좌 미배정 USD = 증권사 USD 매수가능금액 − 시드를 점유하는 전략(occupiesSeed)들의 최신 원장(usd_deposit) 합
// DCA처럼 시드가 없는 전략은 원장이 자기 몫이 아니므로 제외 — 전략 등록 폼 경고와 주식 DCA BUY 가용 판정의 공용 계산
@Component
@RequiredArgsConstructor
public class UnassignedUsdCalculator {

    private final StrategyPort strategyPort;
    private final CyclePositionPort cyclePositionPort;
    private final MarginPort marginPort;
    private final CycleOrderStrategies cycleStrategies;

    // MOCK은 마진이 고정값이라 무의미, 코인 계좌는 USD 매수가능금액 개념이 없다
    public static boolean applies(Broker broker) {
        return broker != Broker.MOCK && broker.getMarket() == Market.US_STOCK;
    }

    // 조회 실패는 예외로 전파, 증권사가 금액을 주지 않으면 null — 호출부가 정책(경고 생략/skip)을 정한다
    public BigDecimal available(UUID accountId, BrokerAccountRef ref) {
        BigDecimal reserved = strategyPort.findByAccountId(accountId).stream()
                .filter(s -> cycleStrategies.of(s.type()).occupiesSeed())
                .map(s -> cyclePositionPort.findLatestOneByStrategyId(s.id()).map(CyclePosition::usdDeposit).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal buyable = marginPort.getUsdBuyableAmount(ref);
        return buyable == null ? null : buyable.subtract(reserved);
    }
}
