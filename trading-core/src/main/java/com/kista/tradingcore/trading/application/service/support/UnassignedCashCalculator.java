package com.kista.tradingcore.trading.application.service.support;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.application.port.output.MarginPort;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

// 계좌 미배정 현금(계좌 통화) = 주문 가능 현금 − 계좌 전략들의 최신 원장(usd_deposit) 합 — 전략 등록 폼 가용 예수금 경고용
// 주문 가능 현금: 주식은 증권사 USD 매수가능금액, 코인은 거래소 견적 통화 주문 가능 잔고(접수 주문 잠금분 제외)
// 미체결 BUY는 각 전략 원장 예수금이 체결 전까지 그대로 들고 있어 따로 빼지 않는다 — 접수(PLACED)분은 증권사·거래소 쪽에서도 빠져 보수적으로 이중 차감된다
@Component
@RequiredArgsConstructor
public class UnassignedCashCalculator {

    private final StrategyPort strategyPort;
    private final CyclePositionPort cyclePositionPort;
    private final MarginPort marginPort;
    private final CryptoExchanges cryptoExchanges; // 코인 계좌 견적 통화 잔고

    // MOCK은 잔고가 고정값이라 무의미
    public static boolean applies(Broker broker) {
        return broker != Broker.MOCK;
    }

    // 조회 실패는 예외로 전파, 증권사가 금액을 주지 않으면 null — 호출부가 정책(경고 생략/skip)을 정한다
    public BigDecimal available(UUID accountId, BrokerAccountRef ref) {
        BigDecimal reserved = strategyPort.findByAccountId(accountId).stream()
                .map(s -> cyclePositionPort.findLatestOneByStrategyId(s.id()).map(CyclePosition::usdDeposit).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal buyable = ref.broker().isCryptoExchange()
                ? cryptoExchanges.of(ref.broker()).getBalance(ref).quoteAvailable()
                : marginPort.getUsdBuyableAmount(ref);
        return buyable == null ? null : buyable.subtract(reserved);
    }
}
