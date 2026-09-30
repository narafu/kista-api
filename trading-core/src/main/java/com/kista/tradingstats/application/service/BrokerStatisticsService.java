package com.kista.tradingstats.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.broker.application.port.output.BrokerStatisticsPort;
import com.kista.broker.application.service.BrokerStatisticsPorts;
import com.kista.broker.domain.model.BrokerAccountInfo;
import com.kista.broker.domain.model.BrokerCandle;
import com.kista.broker.domain.model.BrokerStockInfo;
import com.kista.broker.domain.model.ExchangeRateQuote;
import com.kista.broker.domain.model.MarketCalendarDay;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.tradingstats.application.usecase.BrokerStatisticsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class BrokerStatisticsService implements BrokerStatisticsUseCase {

    private static final Broker EXCHANGE_RATE_BROKER = Broker.TOSS; // 환율은 계좌 무관 공개 API — Toss가 유일한 제공자라 고정, 제공자가 늘면 정책으로 승격

    private final AccountPort accountPort;
    private final BrokerStatisticsPorts statisticsPorts; // 증권사별 통계 포트 레지스트리 — 지원 증권사만 등록됨(현재 TOSS)

    @Override
    public List<BrokerCandle> getCandles(UUID accountId, UUID requesterId, StrategyTicker ticker, String interval,
                                         LocalDate from, LocalDate to) {
        return resolve(accountId, requesterId, "CandlePort").port().getCandles(ticker.name(), interval, from, to);
    }

    @Override
    public BrokerStockInfo getStockInfo(UUID accountId, UUID requesterId, StrategyTicker ticker) {
        return resolve(accountId, requesterId, "StockInfoPort").port().getStockInfo(ticker);
    }

    @Override
    public ExchangeRateQuote getExchangeRate(UUID accountId, UUID requesterId) {
        return resolve(accountId, requesterId, "ExchangeRatePort").port().getExchangeRate();
    }

    @Override
    public ExchangeRateQuote currentExchangeRate() {
        // 계좌가 없으므로 환율 제공 증권사를 상수로 고정해 통계 포트를 고른다
        return statisticsPorts.find(EXCHANGE_RATE_BROKER)
                .orElseThrow(() -> new IllegalStateException(EXCHANGE_RATE_BROKER + " 통계 포트가 등록되어 있지 않아 환율을 조회할 수 없습니다"))
                .getExchangeRate();
    }

    @Override
    public List<MarketCalendarDay> getMarketCalendar(UUID accountId, UUID requesterId,
                                                     LocalDate from, LocalDate to) {
        return resolve(accountId, requesterId, "BrokerMarketCalendarPort").port().getMarketCalendar(from, to);
    }

    @Override
    public List<BrokerAccountInfo> getAccountList(UUID accountId, UUID requesterId) {
        Resolved resolved = resolve(accountId, requesterId, "BrokerAccountPort");
        return resolved.port().getAccountList(resolved.account().toBrokerRef());
    }

    // 소유권 검증 + 증권사 지원 가드 — 통계 capability가 없는 증권사 계좌로 호출 시 IllegalArgumentException → 400
    private Resolved resolve(UUID accountId, UUID requesterId, String capability) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        BrokerStatisticsPort port = statisticsPorts.find(account.broker())
                .orElseThrow(() -> new IllegalArgumentException(account.broker() + " 브로커는 " + capability + "를 지원하지 않습니다"));
        return new Resolved(account, port);
    }

    // 소유권 검증을 통과한 계좌와 그 증권사의 통계 포트 묶음
    private record Resolved(Account account, BrokerStatisticsPort port) {}
}
