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
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
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
    private final StrategyPort strategyPort;             // 원장 평단 조회용 — 계좌·종목 전략
    private final StrategyCyclePort strategyCyclePort;   // 전략 최신 사이클
    private final CyclePositionPort cyclePositionPort;   // 사이클 최신 포지션(원장 평단)

    @Override
    public CryptoPortfolio getCryptoPortfolio(UUID accountId, UUID requesterId) {
        Account account = requireCryptoAccount(accountId, requesterId);
        CryptoExchangePort exchange = exchanges.of(account.broker());
        CryptoBalance balance = BrokerCallGuard.wrap("코인 잔고 조회", () -> exchange.getBalance(account.toBrokerRef()));
        // 계좌 통화 — 평가액 반올림 scale 기준
        Currency currency = account.broker().getCurrency();
        // 보유 코인이 없으면 시세 조회 생략
        Map<StrategyTicker, BigDecimal> prices = balance.holdings().isEmpty() ? Map.of()
                : BrokerCallGuard.wrap("코인 시세 조회", () -> exchange.getCurrentPrices(balance.holdings().keySet()));
        List<CryptoPortfolio.Holding> holdings = balance.holdings().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> toHolding(e.getKey(), e.getValue(), prices.get(e.getKey()), currency, accountId))
                .toList();
        return new CryptoPortfolio(currency, balance.quoteAvailable().add(balance.quoteLocked()), holdings);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getCurrentPrices(UUID accountId, UUID requesterId, List<StrategyTicker> tickers) {
        // 소유권·코인 계좌 검증 후 종목 검증 — 둘 다 거래소 호출 전 (guard 안에서 매핑 누락 IAE가 나면 5xx로 바뀐다)
        Account account = requireCryptoAccount(accountId, requesterId);
        Market market = account.broker().getMarket();
        if (tickers.isEmpty() || tickers.stream().anyMatch(t -> !t.tradableOn(market))) {
            throw new IllegalArgumentException("코인 종목을 1개 이상 지정해주세요.");
        }
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

    private CryptoPortfolio.Holding toHolding(StrategyTicker ticker, CryptoBalance.Holding h, BigDecimal price, Currency currency, UUID accountId) {
        BigDecimal quantity = h.total();
        // 시세 없으면 평가액 null, 있으면 계좌 통화 scale HALF_UP 반올림
        BigDecimal eval = price == null ? null : currency.round(quantity.multiply(price));
        // 거래소 평단이 없으면(바이낸스) 원장 평단으로 대체 — DB 조회라 BrokerCallGuard 밖
        BigDecimal avg = h.avgBuyPrice() != null ? h.avgBuyPrice() : ledgerAvgPrice(accountId, ticker);
        return new CryptoPortfolio.Holding(ticker, quantity, avg, price, eval);
    }

    // 그 계좌·종목 전략의 최신 사이클 최신 포지션 평단 — 없으면 null
    private BigDecimal ledgerAvgPrice(UUID accountId, StrategyTicker ticker) {
        for (var strategy : strategyPort.findByAccountIdAndTicker(accountId, ticker)) {
            BigDecimal avg = strategyCyclePort.findLatestByStrategyId(strategy.id())
                    .map(c -> cyclePositionPort.findLatestByCycleIds(List.of(c.id())).get(c.id()))
                    .map(CyclePosition::avgPrice).orElse(null);
            if (avg != null) return avg;
        }
        return null;
    }
}
