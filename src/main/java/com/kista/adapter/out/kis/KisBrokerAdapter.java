package com.kista.adapter.out.kis;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.KisDailyTransactionPort;
import com.kista.domain.port.out.KisExecutionPort;
import com.kista.domain.port.out.KisMarginPort;
import com.kista.domain.port.out.KisPortfolioPort;
import com.kista.domain.port.out.KisSellableQuantityPort;
import com.kista.domain.port.out.broker.BrokerAdapter;
import com.kista.domain.port.out.broker.DailyTradeCapable;
import com.kista.domain.port.out.broker.ExecutionCapable;
import com.kista.domain.port.out.broker.MarginCapable;
import com.kista.domain.port.out.broker.PortfolioCapable;
import com.kista.domain.port.out.broker.SellableQuantityCapable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// KIS 브로커 어댑터 — 공통 6개 Capability 구현, BalanceCapable 미구현(DB 스냅샷 사용)
@Component
@RequiredArgsConstructor
public class KisBrokerAdapter implements BrokerAdapter,
        PortfolioCapable, MarginCapable, SellableQuantityCapable,
        DailyTradeCapable, ExecutionCapable {

    private final KisPortfolioPort kisPortfolioPort;
    private final KisMarginPort kisMarginPort;
    private final KisSellableQuantityPort kisSellableQuantityPort;
    private final KisDailyTransactionPort kisDailyTransactionPort;
    private final KisExecutionPort kisExecutionPort;

    @Override
    public Account.Broker supports() {
        return Account.Broker.KIS;
    }

    // CTRP6504R 결과에 TTTC2101R(margin)에서 예수금·환율 보정
    @Override
    public PresentBalanceResult getPresentBalance(Account account) {
        PresentBalanceResult portfolio = kisPortfolioPort.getPresentBalance(account);
        List<MarginItem> margins = kisMarginPort.getMargin(account);
        BigDecimal usdDeposit = margins.stream()
                .filter(m -> m.currency() == Currency.USD)
                .map(MarginItem::purchasableAmount)
                .findFirst().orElse(BigDecimal.ZERO);
        BigDecimal rate = margins.stream()
                .filter(m -> m.currency() == Currency.USD)
                .map(MarginItem::usdToKrwRate)
                .findFirst().orElse(BigDecimal.ZERO);
        return new PresentBalanceResult(
                portfolio.items(), portfolio.totalAssetUsd(), portfolio.totalEvalProfit(),
                portfolio.totalReturnRate(), usdDeposit, rate
        );
    }

    @Override
    public List<MarginItem> getMargin(Account account) {
        return kisMarginPort.getMargin(account);
    }

    @Override
    public BigDecimal getUsdBuyableAmount(Account account) {
        return kisMarginPort.getUsdBuyableAmount(account);
    }

    @Override
    public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return kisSellableQuantityPort.getSellableQuantity(ticker, account);
    }

    @Override
    public DailyTransactionResult getDailyTransactions(LocalDate from, LocalDate to, Account account) {
        return kisDailyTransactionPort.getDailyTransactions(from, to, account);
    }

    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        return kisExecutionPort.getExecutions(from, to, ticker, account);
    }
}
