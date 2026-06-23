package com.kista.application.service.account;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossStockInfo;
import com.kista.domain.port.in.TossStatisticsUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.broker.BrokerAccountPort;
import com.kista.domain.port.out.broker.CandlePort;
import com.kista.domain.port.out.broker.ExchangeRatePort;
import com.kista.domain.port.out.broker.MarketCalendarPort;
import com.kista.domain.port.out.broker.StockInfoPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class TossStatisticsService implements TossStatisticsUseCase {

    private final AccountPort accountPort;
    private final BrokerAdapterRegistry registry;

    @Override
    public List<TossCandle> getCandles(UUID accountId, UUID requesterId, Ticker ticker, String interval,
                                       LocalDate from, LocalDate to) {
        Account account = requireAccount(accountId, requesterId);
        return registry.require(account, CandlePort.class).getCandles(ticker.name(), interval, from, to);
    }

    @Override
    public TossStockInfo getStockInfo(UUID accountId, UUID requesterId, Ticker ticker) {
        Account account = requireAccount(accountId, requesterId);
        return registry.require(account, StockInfoPort.class).getStockInfo(ticker);
    }

    @Override
    public TossExchangeRate getExchangeRate(UUID accountId, UUID requesterId) {
        Account account = requireAccount(accountId, requesterId);
        return registry.require(account, ExchangeRatePort.class).getExchangeRate();
    }

    @Override
    public List<TossMarketSession> getMarketCalendar(UUID accountId, UUID requesterId,
                                                     LocalDate from, LocalDate to) {
        Account account = requireAccount(accountId, requesterId);
        return registry.require(account, MarketCalendarPort.class).getMarketCalendar(from, to);
    }

    @Override
    public List<TossAccountInfo> getAccountList(UUID accountId, UUID requesterId) {
        Account account = requireAccount(accountId, requesterId);
        return registry.require(account, BrokerAccountPort.class).getAccountList(account);
    }

    // 소유권 검증 — KIS 계좌로 Toss 전용 기능 호출 시 registry.require()에서 IllegalArgumentException → 400
    private Account requireAccount(UUID accountId, UUID requesterId) {
        return accountPort.requireOwnedAccount(accountId, requesterId);
    }
}
