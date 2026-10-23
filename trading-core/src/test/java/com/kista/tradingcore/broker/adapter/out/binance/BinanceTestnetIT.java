package com.kista.tradingcore.broker.adapter.out.binance;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.platform.http.HttpClientTimeouts;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 바이낸스 테스트넷 실측 — 키가 없으면 건너뛴다. 테스트넷은 /sapi(apiRestrictions)를 지원하지 않아 연결 테스트는 범위 밖
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "BINANCE_TESTNET_API_KEY", matches = ".+")
@DisplayName("바이낸스 테스트넷 통합 테스트")
class BinanceTestnetIT {

    private static final String BASE_URL = "https://testnet.binance.vision"; // 테스트넷 호스트
    private static final BigDecimal BUY_AMOUNT = new BigDecimal("10.50");    // 소수 2자리 USDT 매수 금액

    private BinanceHttpClient http;
    private BinanceExchangeAdapter adapter;
    private BrokerAccountRef account;

    @BeforeEach
    void setUp() {
        RestClient rest = RestClient.builder().requestFactory(HttpClientTimeouts.jdkTimeouts(3_000, 10_000)).build();
        http = new BinanceHttpClient(rest, JsonMapper.builder().build(), BASE_URL);
        adapter = new BinanceExchangeAdapter(http, new BinanceSymbolRegistry());
        account = new BrokerAccountRef(UUID.randomUUID(), System.getenv("BINANCE_TESTNET_API_KEY"),
                System.getenv("BINANCE_TESTNET_SECRET_KEY"), null, null, Broker.BINANCE);
    }

    // 새 UUID로 BTC 시장가 매수 후 체결 반영을 기다린 clientOrderId 조회 결과
    private CryptoOrderResult buyAndLookup(UUID id) throws InterruptedException {
        long t0 = System.nanoTime();
        CryptoOrderResult placed = adapter.buyMarketByAmount(account, StrategyTicker.BTC, BUY_AMOUNT, id.toString());
        Thread.sleep(2_000);
        Optional<CryptoOrderResult> found = adapter.findOrderByClientOrderId(account, StrategyTicker.BTC, id.toString());
        System.out.printf("[binance] placed=%s lookup=%s elapsedMs=%d%n", placed, found, (System.nanoTime() - t0) / 1_000_000);
        assertThat(found).isPresent();
        return found.get();
    }

    @Test
    @DisplayName("quote 금액 매수 후 clientOrderId로 조회하면 체결 완료·순수량이 나온다")
    void buyByQuoteAmountThenQueryByClientOrderId() throws InterruptedException {
        UUID id = UUID.randomUUID();
        CryptoOrderResult r = buyAndLookup(id);

        assertThat(r.state()).isEqualTo(State.DONE);
        assertThat(r.executedVolume()).isPositive();
        assertThat(r.netExecutedVolume()).isLessThanOrEqualTo(r.executedVolume());
        assertThat(r.clientOrderId()).isEqualTo(id.toString());
    }

    @Test
    @DisplayName("거래소 주문 ID 조회는 myTrades 수수료를 채운다")
    void queryByExchangeOrderIdFetchesMyTradesFee() throws InterruptedException {
        CryptoOrderResult bought = buyAndLookup(UUID.randomUUID());

        CryptoOrderResult r = adapter.findOrderByExchangeOrderId(account, StrategyTicker.BTC, bought.exchangeOrderId()).orElseThrow();
        System.out.printf("[binance] byOrderId=%s%n", r);
        assertThat(r.feeAsset()).isNotNull();
        assertThat(r.paidFee()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("방금 산 순수량을 시장가 매도하면 체결 완료다")
    void sellFlooredQuantity() throws InterruptedException {
        CryptoOrderResult bought = buyAndLookup(UUID.randomUUID());
        UUID sellId = UUID.randomUUID();

        adapter.sellMarketByVolume(account, StrategyTicker.BTC, bought.netExecutedVolume(), sellId.toString());
        Thread.sleep(2_000);
        CryptoOrderResult sold = adapter.findOrderByClientOrderId(account, StrategyTicker.BTC, sellId.toString()).orElseThrow();
        System.out.printf("[binance] sold=%s%n", sold);
        assertThat(sold.state()).isEqualTo(State.DONE);
    }

    @Test
    @DisplayName("없는 clientOrderId 조회는 empty다(-2013)")
    void unknownClientOrderIdIsEmpty() {
        Optional<CryptoOrderResult> r = adapter.findOrderByClientOrderId(account, StrategyTicker.BTC, UUID.randomUUID().toString());
        System.out.printf("[binance] unknown=%s%n", r);
        assertThat(r).isEmpty();
    }

    @Test
    @DisplayName("하이픈 포함 36자 clientOrderId 수락 여부를 출력한다(실측 기록용, 단언 없음)")
    void hyphenatedClientOrderIdAcceptance() {
        LinkedHashMap<String, String> q = new LinkedHashMap<>();
        q.put("symbol", "BTCUSDT");
        q.put("side", "BUY");
        q.put("type", "MARKET");
        q.put("quoteOrderQty", BUY_AMOUNT.toPlainString());
        q.put("newClientOrderId", UUID.randomUUID().toString()); // 하이픈 포함 36자
        try {
            Object res = http.postSigned("/api/v3/order", q, account, new ParameterizedTypeReference<Object>() {});
            System.out.printf("[binance] 36자 수락: %s%n", res);
        } catch (RuntimeException e) {
            System.out.printf("[binance] 36자 거부: %s%n", e.getMessage());
        }
    }

    @Test
    @DisplayName("잔고 USDT·거래 규칙 수량 단위·완성 일봉 마지막 일자를 확인한다")
    void balanceAndRules() {
        CryptoBalance balance = adapter.getBalance(account);
        assertThat(balance.quoteAvailable()).isPositive();
        assertThat(adapter.getTradingRules(StrategyTicker.BTC).quantityScale()).isEqualTo(5);

        var candles = adapter.getCompletedDailyCandles(StrategyTicker.BTC, 3);
        assertThat(candles.getLast().kstDate()).isEqualTo(CryptoCandle.latestCompletedKstDate(LocalDateTime.now(com.kista.shared.kernel.TimeZones.KST)));
    }
}
