package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PeriodProfitResult;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.kis.ReservationOrder;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.in.GetAccountStatisticsUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.KisDailyTransactionPort;
import com.kista.domain.port.out.KisExecutionPort;
import com.kista.domain.port.out.KisMarginPort;
import com.kista.domain.port.out.KisPortfolioPort;
import com.kista.domain.port.out.KisPricePort;
import com.kista.domain.port.out.KisProfitPort;
import com.kista.domain.port.out.KisReservationOrderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountStatisticsService implements GetAccountStatisticsUseCase {

    private final AccountRepository accountRepository;
    private final KisProfitPort kisProfitPort;
    private final KisExecutionPort kisExecutionPort;
    private final KisPortfolioPort kisPortfolioPort;
    private final KisMarginPort kisMarginPort;
    private final KisDailyTransactionPort kisDailyTransactionPort;
    private final KisReservationOrderPort kisReservationOrderPort;
    private final KisPricePort kisPricePort;

    @Override
    public PeriodProfitResult getPeriodProfit(UUID accountId, UUID requesterId,
                                               LocalDate from, LocalDate to) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        // KIS 예외는 그대로 전파 → 컨트롤러에서 503 처리
        return kisProfitPort.getPeriodProfit(account, from, to);
    }

    @Override
    public List<Execution> getTrades(UUID accountId, UUID requesterId,
                                      LocalDate from, LocalDate to) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return kisExecutionPort.getExecutions(from, to, account);
    }

    @Override
    public PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return kisPortfolioPort.getPresentBalance(account);
    }

    @Override
    public List<MarginItem> getMargin(UUID accountId, UUID requesterId) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return kisMarginPort.getMargin(account);
    }

    @Override
    public DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId,
                                                        LocalDate from, LocalDate to) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return kisDailyTransactionPort.getDailyTransactions(from, to, account);
    }

    @Override
    public List<ReservationOrder> getReservationOrders(UUID accountId, UUID requesterId,
                                                        LocalDate from, LocalDate to) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return kisReservationOrderPort.getReservationOrders(from, to, account);
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers) {
        Account account = accountRepository.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return kisPricePort.getPrices(tickers, account);
    }

}
