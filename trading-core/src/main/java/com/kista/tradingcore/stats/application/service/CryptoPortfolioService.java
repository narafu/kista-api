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
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.stats.application.usecase.CryptoPortfolioUseCase;
import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        // 보유 코인이 없으면 시세 조회 생략
        Map<StrategyTicker, BigDecimal> prices = balance.holdings().isEmpty() ? Map.of()
                : BrokerCallGuard.wrap("코인 시세 조회", () -> exchange.getCurrentPrices(balance.holdings().keySet()));
        // 거래소 평단이 없는 종목(바이낸스)만 원장 평단 조회 — DB 조회라 BrokerCallGuard 밖
        Set<StrategyTicker> noExchangeAvg = balance.holdings().entrySet().stream()
                .filter(e -> e.getValue().avgBuyPrice() == null).map(Map.Entry::getKey).collect(Collectors.toSet());
        Map<StrategyTicker, BigDecimal> ledgerAvg = ledgerAvgPrices(accountId, noExchangeAvg);
        // 계좌 통화 — 평가액 반올림 scale 기준
        Currency currency = account.broker().getCurrency();
        List<CryptoPortfolio.Holding> holdings = balance.holdings().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> toHolding(e.getKey(), e.getValue(), prices.get(e.getKey()), ledgerAvg.get(e.getKey()), currency))
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

    private CryptoPortfolio.Holding toHolding(StrategyTicker ticker, CryptoBalance.Holding h, BigDecimal price, BigDecimal ledgerAvg, Currency currency) {
        BigDecimal quantity = h.total();
        // 시세 없으면 평가액 null, 있으면 계좌 통화 scale HALF_UP 반올림
        BigDecimal eval = price == null ? null : currency.round(quantity.multiply(price));
        BigDecimal avg = h.avgBuyPrice() != null ? h.avgBuyPrice() : ledgerAvg;
        return new CryptoPortfolio.Holding(ticker, quantity, avg, price, eval);
    }

    // 종목별 원장 평단 — 종목당 전략 1개(등록 시 중복 거부), 진행 중 사이클의 최신 포지션만(종료 사이클은 청산된 포지션). 종목 수와 무관하게 쿼리 3회
    private Map<StrategyTicker, BigDecimal> ledgerAvgPrices(UUID accountId, Set<StrategyTicker> tickers) {
        if (tickers.isEmpty()) return Map.of();
        Map<UUID, StrategyTicker> tickerByStrategy = strategyPort.findByAccountId(accountId).stream()
                .filter(s -> tickers.contains(s.ticker()))
                .collect(Collectors.toMap(Strategy::id, Strategy::ticker));
        if (tickerByStrategy.isEmpty()) return Map.of();
        Map<UUID, StrategyTicker> tickerByCycle = strategyCyclePort.findLatestByStrategyIds(tickerByStrategy.keySet()).values().stream()
                .filter(c -> c.endDate() == null)
                .collect(Collectors.toMap(StrategyCycle::id, c -> tickerByStrategy.get(c.strategyId())));
        if (tickerByCycle.isEmpty()) return Map.of();
        Map<StrategyTicker, BigDecimal> result = new EnumMap<>(StrategyTicker.class);
        cyclePositionPort.findLatestByCycleIds(tickerByCycle.keySet()).forEach((cycleId, position) -> {
            if (position.avgPrice() != null) result.put(tickerByCycle.get(cycleId), position.avgPrice());
        });
        return result;
    }
}
