package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.trading.application.service.support.UnassignedCashCalculator;
import com.kista.tradingcore.trading.application.service.support.UnassignedCashCalculator.SeedHolder;
import com.kista.tradingcore.trading.application.usecase.AccountLedgerTotalsUseCase;
import com.kista.tradingcore.trading.domain.model.AccountLedgerTotals;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 계좌 원장 합산 조회 — 외부 호출(시세·잔고)이 있어 @Transactional을 붙이지 않는다(읽기 포트가 각자 트랜잭션)
// 시세·잔고 실패는 fail-open: 해당 필드만 null (부분합은 내보내지 않는다)
@Service
@RequiredArgsConstructor
class AccountLedgerTotalsService implements AccountLedgerTotalsUseCase {

    private final AccountPort accountPort;               // 소유권 검증
    private final UnassignedCashCalculator calculator;   // 시드 점유 전략·원장 목록, 미배정 현금
    private final BrokerPricePort brokerPricePort;       // 주식 계좌 현재가
    private final CryptoExchanges cryptoExchanges;       // 코인 계좌 현재가

    @Override
    public AccountLedgerTotals get(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        Currency currency = account.broker().getCurrency(); // 계좌 통화 — 반올림 scale 기준
        List<SeedHolder> holders = calculator.seedHolders(accountId);
        BigDecimal reserved = UnassignedCashCalculator.reserved(holders);
        return new AccountLedgerTotals(currency, currency.round(reserved),
                evaluation(account, holders, currency), unassignedCash(account, reserved, currency));
    }

    // 종목별 보유 합 × 현재가 합 — 가격이 하나라도 빠지면 null, 보유가 없으면 시세 호출 없이 0
    private @Nullable BigDecimal evaluation(Account account, List<SeedHolder> holders, Currency currency) {
        Map<StrategyTicker, BigDecimal> holdingsByTicker = new EnumMap<>(StrategyTicker.class);
        for (SeedHolder h : holders) {
            if (h.holdings().signum() > 0) holdingsByTicker.merge(h.strategy().ticker(), h.holdings(), BigDecimal::add);
        }
        if (holdingsByTicker.isEmpty()) return BigDecimal.ZERO;
        List<StrategyTicker> tickers = List.copyOf(holdingsByTicker.keySet());
        String label = "원장 평가금 시세 조회: accountId=" + account.id();
        Map<StrategyTicker, BigDecimal> prices = BrokerCallGuard.tryGet(label, () -> account.broker().isCryptoExchange()
                ? cryptoExchanges.of(account.broker()).getCurrentPrices(tickers)
                : brokerPricePort.getPrices(tickers, account.toBrokerRef())).orElse(null);
        if (prices == null || !prices.keySet().containsAll(tickers)) return null;
        return currency.round(holdingsByTicker.entrySet().stream()
                .map(e -> e.getValue().multiply(prices.get(e.getKey())))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    // MOCK 계좌·조회 실패·증권사가 금액을 주지 않으면 null
    private @Nullable BigDecimal unassignedCash(Account account, BigDecimal reserved, Currency currency) {
        if (!UnassignedCashCalculator.applies(account.broker())) return null;
        return BrokerCallGuard.tryGet("원장 미배정 현금 조회: accountId=" + account.id(),
                        () -> calculator.available(account.toBrokerRef(), reserved))
                .map(currency::round).orElse(null);
    }
}
