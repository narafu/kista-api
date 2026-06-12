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

// package-private — account.broker() 기반으로 KIS/Toss 가격 포트 선택
@Component
@RequiredArgsConstructor
class BrokerPriceRouter {

    private final KisPricePort kisPricePort;
    private final TosPricePort tosPricePort;

    BigDecimal getPrice(Ticker ticker, Account account) {
        return switch (account.broker()) {
            case KIS -> kisPricePort.getPrice(ticker, account);
            case TOSS -> tosPricePort.getPrice(ticker, account);
        };
    }

    Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        return switch (account.broker()) {
            case KIS -> kisPricePort.getPrices(tickers, account);
            case TOSS -> tosPricePort.getPrices(tickers, account);
        };
    }

    PriceSnapshot getPriceSnapshot(Ticker ticker, Account account) {
        return switch (account.broker()) {
            case KIS -> kisPricePort.getPriceSnapshot(ticker, account);
            case TOSS -> tosPricePort.getPriceSnapshot(ticker, account);
        };
    }

    Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account) {
        return switch (account.broker()) {
            case KIS -> kisPricePort.getPriceSnapshots(tickers, account);
            case TOSS -> tosPricePort.getPriceSnapshots(tickers, account);
        };
    }
}
