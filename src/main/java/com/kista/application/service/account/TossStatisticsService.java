package com.kista.application.service.account;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.broker.domain.model.toss.*;
import com.kista.application.usecase.TossStatisticsUseCase;
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
    private final BrokerAdapterRegistry registry;

    @Override
    public List<TossCandle> getCandles(UUID accountId, UUID requesterId, StrategyTicker ticker, String interval,
                                       LocalDate from, LocalDate to) {
        Account account = requireAccount(accountId, requesterId);
        return registry.require(toBrokerRef(account), CandlePort.class).getCandles(ticker.name(), interval, from, to);
    }

    @Override
    public TossStockInfo getStockInfo(UUID accountId, UUID requesterId, StrategyTicker ticker) {
        Account account = requireAccount(accountId, requesterId);
        return registry.require(toBrokerRef(account), StockInfoPort.class).getStockInfo(ticker);
    }

    @Override
    public TossExchangeRate getExchangeRate(UUID accountId, UUID requesterId) {
        Account account = requireAccount(accountId, requesterId);
        return registry.require(toBrokerRef(account), ExchangeRatePort.class).getExchangeRate();
    }

    @Override
    public List<TossMarketSession> getMarketCalendar(UUID accountId, UUID requesterId,
                                                     LocalDate from, LocalDate to) {
        Account account = requireAccount(accountId, requesterId);
        return registry.require(toBrokerRef(account), BrokerMarketCalendarPort.class).getMarketCalendar(from, to);
    }

    @Override
    public List<TossAccountInfo> getAccountList(UUID accountId, UUID requesterId) {
        Account account = requireAccount(accountId, requesterId);
        return registry.require(toBrokerRef(account), BrokerAccountPort.class).getAccountList(toBrokerRef(account));
    }

    // 소유권 검증 — KIS 계좌로 Toss 전용 기능 호출 시 registry.require()에서 IllegalArgumentException → 400
    private Account requireAccount(UUID accountId, UUID requesterId) {
        return accountPort.requireOwnedAccount(accountId, requesterId);
    }

    // broker 모듈 순환 방지 — Account → BrokerAccountRef 변환 (broker는 Account를 직접 참조하지 않음)
    // Account.Broker → BrokerAccountRef.Broker는 상수명 byte-identical이라 valueOf(name())으로 매핑
    private static BrokerAccountRef toBrokerRef(Account account) {
        return new BrokerAccountRef(
                account.id(), account.appKey(), account.secretKey(),
                account.accountNo(), account.brokerAccountCode(),
                BrokerAccountRef.Broker.valueOf(account.broker().name()));
    }
}
