package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.KisPricePort;
import com.kista.domain.port.out.TosPricePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

// account.broker() 기반으로 KIS/Toss 가격 포트 선택
@Component
@RequiredArgsConstructor
public class BrokerPriceRouter {

    private final KisPricePort kisPricePort;
    private final TosPricePort tosPricePort;

    public BigDecimal getPrice(Ticker ticker, Account account) {
        return switch (account.broker()) {
            case KIS  -> kisPricePort.getPrice(ticker, account);
            case TOSS -> tosPricePort.getPrice(ticker);          // 공통 API — account 불필요
        };
    }

    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        return switch (account.broker()) {
            case KIS  -> kisPricePort.getPrices(tickers, account);
            case TOSS -> tosPricePort.getPrices(tickers);        // 공통 API — account 불필요
        };
    }

    public PriceSnapshot getPriceSnapshot(Ticker ticker, Account account) {
        return switch (account.broker()) {
            case KIS  -> kisPricePort.getPriceSnapshot(ticker, account);
            case TOSS -> tosPricePort.getPriceSnapshot(ticker);  // 공통 API — account 불필요
        };
    }

    public Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account) {
        return switch (account.broker()) {
            case KIS  -> kisPricePort.getPriceSnapshots(tickers, account);
            case TOSS -> tosPricePort.getPriceSnapshots(tickers);// 공통 API — account 불필요
        };
    }
}
