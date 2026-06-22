package com.kista.application.service.account;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossStockInfo;
import com.kista.domain.port.in.TossStatisticsUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.TosCandlePort;
import com.kista.domain.port.out.TossAccountListPort;
import com.kista.domain.port.out.TossExchangeRatePort;
import com.kista.domain.port.out.TossMarketCalendarPort;
import com.kista.domain.port.out.TossStockInfoPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class TossStatisticsService implements TossStatisticsUseCase {

    private final AccountPort accountPort;
    private final TosCandlePort tosCandlePort;
    private final TossStockInfoPort tossStockInfoPort;
    private final TossExchangeRatePort tossExchangeRatePort;
    private final TossMarketCalendarPort tossMarketCalendarPort;
    private final TossAccountListPort tossAccountListPort;

    @Override
    public List<TossCandle> getCandles(UUID accountId, UUID requesterId, Ticker ticker, String interval,
                                       LocalDate from, LocalDate to) {
        requireTossAccount(accountId, requesterId);
        return tosCandlePort.getCandles(ticker.name(), interval, from, to);
    }

    @Override
    public TossStockInfo getStockInfo(UUID accountId, UUID requesterId, Ticker ticker) {
        requireTossAccount(accountId, requesterId);
        return tossStockInfoPort.getStockInfo(ticker);
    }

    @Override
    public TossExchangeRate getExchangeRate(UUID accountId, UUID requesterId) {
        requireTossAccount(accountId, requesterId);
        return tossExchangeRatePort.getExchangeRate();
    }

    @Override
    public List<TossMarketSession> getMarketCalendar(UUID accountId, UUID requesterId,
                                                     LocalDate from, LocalDate to) {
        requireTossAccount(accountId, requesterId);
        return tossMarketCalendarPort.getMarketCalendar(from, to);
    }

    @Override
    public List<TossAccountInfo> getAccountList(UUID accountId, UUID requesterId) {
        Account account = requireTossAccount(accountId, requesterId);
        return tossAccountListPort.getAccountList(account);
    }

    // 소유권 + Toss 계좌 여부 검증 — Account 반환으로 호출자가 재사용 가능
    private Account requireTossAccount(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
        return account;
    }
}
