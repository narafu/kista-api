package com.kista.adapter.out.kis;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.broker.*;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// KIS 증권사 어댑터 — 공통 7개 Port 구현 (BrokerPricePort + LiveBalancePort 추가)
@Component
@RequiredArgsConstructor
public class KisBrokerAdapter implements BrokerAdapterPort,
        PortfolioPort, MarginPort, SellableQuantityPort,
        BrokerOrderCorrectionPort,
        ExecutionPort,
        BrokerPricePort, LiveBalancePort {

    private final KisPortfolioPort kisPortfolioPort;
    private final KisMarginPort kisMarginPort;
    private final KisSellableQuantityPort kisSellableQuantityPort;
    private final KisExecutionPort kisExecutionPort;
    private final KisOrderPort kisOrderPort;
    private final KisPricePort kisPricePort;       // 현재가·스냅샷 조회
    private final KisAccountPort kisAccountPort;   // live 잔고 조회

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
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        return kisExecutionPort.getExecutions(from, to, ticker, account);
    }

    @Override
    public void cancel(com.kista.domain.model.order.Order order, Account account) {
        kisOrderPort.cancel(order, account);
    }

    @Override
    public com.kista.domain.model.order.Order place(com.kista.domain.model.order.Order order, Account account) {
        return kisOrderPort.place(order, account);
    }

    @Override
    public BigDecimal getPrice(Ticker ticker, Account account) {
        return kisPricePort.getPrice(ticker, account);
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        return kisPricePort.getPrices(tickers, account);
    }

    @Override
    public PriceSnapshot getPriceSnapshot(Ticker ticker, Account account) {
        return kisPricePort.getPriceSnapshot(ticker, account);
    }

    @Override
    public Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account) {
        return kisPricePort.getPriceSnapshots(tickers, account);
    }

    @Override
    public AccountBalance getLiveBalance(Account account, Ticker ticker) {
        return kisAccountPort.getBalance(account, ticker);
    }
}
