package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.stats.application.usecase.CryptoPortfolioUseCase;
import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 코인 계좌 잔고 → KRW 평가 포트폴리오 (거래소 실잔고가 SSOT, DB 포지션 없음)
@Service
@RequiredArgsConstructor
class CryptoPortfolioService implements CryptoPortfolioUseCase {

    private final AccountPort accountPort;       // 소유권 검증
    private final CryptoExchanges exchanges;     // 코인 거래소 라우터

    @Override
    public CryptoPortfolio getCryptoPortfolio(UUID accountId, UUID requesterId) {
        Account account = requireCryptoAccount(accountId, requesterId);
        CryptoExchangePort exchange = exchanges.of(account.broker());
        CryptoBalance balance = BrokerCallGuard.wrap("코인 잔고 조회", () -> exchange.getBalance(account.toBrokerRef()));
        // 보유 코인이 없으면 시세 조회 생략
        Currency currency = account.broker().getCurrency();
        Map<StrategyTicker, BigDecimal> prices = balance.holdings().isEmpty() ? Map.of()
                : BrokerCallGuard.wrap("코인 시세 조회", () -> exchange.getCurrentPrices(balance.holdings().keySet()));
        List<CryptoPortfolio.Holding> holdings = balance.holdings().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> toHolding(e.getKey(), e.getValue(), prices.get(e.getKey()), currency))
                .toList();
        return new CryptoPortfolio(currency, balance.quoteAvailable().add(balance.quoteLocked()), holdings);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getCurrentPrices(UUID accountId, UUID requesterId, List<StrategyTicker> tickers) {
        // 종목 검증은 거래소 호출 전 — guard 안에서 매핑 누락 IAE가 나면 5xx로 바뀐다
        if (tickers.isEmpty() || tickers.stream().anyMatch(t -> t.getMarket() != Market.CRYPTO_KRW)) {
            throw new IllegalArgumentException("코인 종목을 1개 이상 지정해주세요.");
        }
        Account account = requireCryptoAccount(accountId, requesterId);
        CryptoExchangePort exchange = exchanges.of(account.broker());
        return BrokerCallGuard.wrap("코인 시세 조회", () -> exchange.getCurrentPrices(Set.copyOf(tickers)));
    }

    private Account requireCryptoAccount(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.broker().isCryptoExchange()) {
            throw new IllegalArgumentException("코인 계좌가 아닙니다. 주식 계좌는 /portfolio를 사용하세요.");
        }
        return account;
    }

    private static CryptoPortfolio.Holding toHolding(StrategyTicker ticker, CryptoBalance.Holding h, BigDecimal price, Currency currency) {
        BigDecimal quantity = h.total();
        // 시세 없으면 평가액 null, 있으면 계좌 통화 scale HALF_UP 반올림
        BigDecimal eval = price == null ? null : currency.round(quantity.multiply(price));
        return new CryptoPortfolio.Holding(ticker, quantity, h.avgBuyPrice(), price, eval);
    }
}
