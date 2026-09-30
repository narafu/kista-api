package com.kista.trading.stats.application.service;

import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.broker.domain.model.toss.*;
import com.kista.trading.stats.application.usecase.TossStatisticsUseCase;
import com.kista.account.application.port.output.AccountPort;
import com.kista.broker.application.port.output.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class TossStatisticsService implements TossStatisticsUseCase {

    private final AccountPort accountPort;
    private final CandlePort candlePort;                         // Toss 전용 Port — 단일 구현(TossBrokerAdapter) 직접 주입
    private final StockInfoPort stockInfoPort;
    private final ExchangeRatePort exchangeRatePort;
    private final BrokerMarketCalendarPort brokerMarketCalendarPort;
    private final BrokerAccountPort brokerAccountPort;

    @Override
    public List<TossCandle> getCandles(UUID accountId, UUID requesterId, StrategyTicker ticker, String interval,
                                       LocalDate from, LocalDate to) {
        requireTossAccount(accountId, requesterId, "CandlePort");
        return candlePort.getCandles(ticker.name(), interval, from, to);
    }

    @Override
    public TossStockInfo getStockInfo(UUID accountId, UUID requesterId, StrategyTicker ticker) {
        requireTossAccount(accountId, requesterId, "StockInfoPort");
        return stockInfoPort.getStockInfo(ticker);
    }

    @Override
    public TossExchangeRate getExchangeRate(UUID accountId, UUID requesterId) {
        requireTossAccount(accountId, requesterId, "ExchangeRatePort");
        return exchangeRatePort.getExchangeRate();
    }

    @Override
    public List<TossMarketSession> getMarketCalendar(UUID accountId, UUID requesterId,
                                                     LocalDate from, LocalDate to) {
        requireTossAccount(accountId, requesterId, "BrokerMarketCalendarPort");
        return brokerMarketCalendarPort.getMarketCalendar(from, to);
    }

    @Override
    public List<TossAccountInfo> getAccountList(UUID accountId, UUID requesterId) {
        Account account = requireTossAccount(accountId, requesterId, "BrokerAccountPort");
        return brokerAccountPort.getAccountList(account.toBrokerRef());
    }

    // 소유권 검증 + Toss 계좌 가드 — 다른 브로커 계좌로 Toss 전용 기능 호출 시 IllegalArgumentException → 400
    private Account requireTossAccount(UUID accountId, UUID requesterId, String portName) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (account.broker() != Broker.TOSS) {
            throw new IllegalArgumentException(account.broker() + " 브로커는 " + portName + "를 지원하지 않습니다");
        }
        return account;
    }
}
