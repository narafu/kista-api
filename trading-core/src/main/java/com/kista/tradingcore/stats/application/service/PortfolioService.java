package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.stats.application.usecase.PortfolioUseCase;
import com.kista.tradingcore.stats.domain.model.PortfolioOrder;
import com.kista.tradingcore.stats.domain.model.PortfolioPosition;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class PortfolioService implements PortfolioUseCase {

    private final CyclePositionPort cyclePositionPort;
    private final OrderPort orderPort; // 거래 이력 조회
    private final AccountPort accountPort; // 계좌 → 통화 해석

    @Override
    public List<PortfolioPosition> getCurrent(UUID userId) {
        Map<UUID, Currency> currencies = currenciesByAccount(userId);
        // 전략별 최신 포지션에 계좌 통화를 붙인다
        return cyclePositionPort.findLatestPerStrategyByUser(userId).stream()
                .map(e -> new PortfolioPosition(e, requireCurrency(currencies, e.accountId(), "포지션 계좌를 찾을 수 없습니다.")))
                .toList();
    }

    @Override
    public List<PortfolioOrder> getHistory(UUID userId, LocalDate from, LocalDate to) {
        Map<UUID, Currency> currencies = currenciesByAccount(userId);
        return orderPort.findByUser(userId, from, to).stream()
                .map(o -> new PortfolioOrder(o, requireCurrency(currencies, o.accountId(), "주문 계좌를 찾을 수 없습니다.")))
                .toList();
    }

    // accountId → 계좌 통화 (Broker 견적 통화)
    private Map<UUID, Currency> currenciesByAccount(UUID userId) {
        return accountPort.findByUserId(userId).stream()
                .collect(Collectors.toMap(Account::id, a -> a.broker().getCurrency()));
    }

    private static Currency requireCurrency(Map<UUID, Currency> currencies, UUID accountId, String message) {
        Currency currency = accountId == null ? null : currencies.get(accountId);
        if (currency == null) throw new IllegalStateException(message);
        return currency;
    }
}
