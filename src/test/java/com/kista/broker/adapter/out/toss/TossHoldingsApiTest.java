package com.kista.broker.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.Currency;
import com.kista.broker.domain.model.MarginItem;
import com.kista.broker.domain.model.PresentBalanceResult;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.broker.domain.model.toss.TossApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("TossHoldingsApi 단위 테스트")
class TossHoldingsApiTest {

    @Mock TossHttpClient tossHttpClient;
    TossHoldingsApi tossHoldingsApi;

    static final Account ACCOUNT = new Account(
        UUID.randomUUID(), UUID.randomUUID(), "테스트",
        "12345678901", "cid", "csecret", "1", Account.Broker.TOSS, null
    );

    @BeforeEach
    void setUp() {
        tossHoldingsApi = new TossHoldingsApi(tossHttpClient);
    }

    // exchange-rate API는 getCommon 사용 — 개별 테스트에서 직접 stub
    private void stubNoAccountHeader() {}

    @Test
    @DisplayName("보유 종목 있음: 정상 AccountBalance 반환")
    void getBalance_holdingFound_returnsBalance() {
        var item = new TossHoldingsApi.HoldingItem("SOXL", "5", "20.00", "22.00");
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.HoldingsResponse(List.of(item))));
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.BuyableAmountResponse("1000.00", "USD")));

        AccountBalance balance = tossHoldingsApi.getBalance(ACCOUNT, Ticker.SOXL);

        assertThat(balance.holdings()).isEqualTo(5);
        assertThat(balance.avgPrice()).isEqualByComparingTo("20.00");
        assertThat(balance.usdDeposit()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("보유 종목 없음: holdings=0, avgPrice=null")
    void getBalance_noHolding_returnsZeroBalance() {
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.HoldingsResponse(List.of())));
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.BuyableAmountResponse("500.00", "USD")));

        AccountBalance balance = tossHoldingsApi.getBalance(ACCOUNT, Ticker.SOXL);

        assertThat(balance.holdings()).isEqualTo(0);
        assertThat(balance.avgPrice()).isNull();
        assertThat(balance.usdDeposit()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("null 응답: holdings=0, usdDeposit=0")
    void getBalance_nullResponse_returnsZeroBalance() {
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(null);
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(null);

        AccountBalance balance = tossHoldingsApi.getBalance(ACCOUNT, Ticker.SOXL);

        assertThat(balance.holdings()).isEqualTo(0);
        assertThat(balance.usdDeposit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("getUsdBuyableAmount: 정상 금액 반환")
    void getUsdBuyableAmount_returnsAmount() {
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.BuyableAmountResponse("1234.56", "USD")));

        BigDecimal amount = tossHoldingsApi.getUsdBuyableAmount(ACCOUNT);

        assertThat(amount).isEqualByComparingTo("1234.56");
    }

    @Test
    @DisplayName("getMargin: USD·KRW 통화별 2건 반환 (통합 아님)")
    void getMargin_returnsUsdAndKrwSeparately() {
        stubNoAccountHeader();
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                org.springframework.util.MultiValueMap<String, String> params =
                    (org.springframework.util.MultiValueMap<String, String>) inv.getArgument(2);
                String currency = params.getFirst("currency");
                String amount = "USD".equals(currency) ? "100.00" : "140000";
                return new TossResult<>(new TossHoldingsApi.BuyableAmountResponse(amount, currency));
            });
        when(tossHttpClient.getCommon(eq("/api/v1/exchange-rate"), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.ExchangeRateResult("1400.00", "1400.00")));

        List<MarginItem> items = tossHoldingsApi.getMargin(ACCOUNT);

        // 통합 아님 — USD·KRW 원본 금액 각각 반환
        assertThat(items).hasSize(2);
        MarginItem usdItem = items.get(0);
        assertThat(usdItem.currency()).isEqualTo(Currency.USD);
        assertThat(usdItem.purchasableAmount()).isEqualByComparingTo("100.00");
        assertThat(usdItem.usdToKrwRate()).isEqualByComparingTo("1400.00");
        MarginItem krwItem = items.get(1);
        assertThat(krwItem.currency()).isEqualTo(Currency.KRW);
        assertThat(krwItem.purchasableAmount()).isEqualByComparingTo("140000");
    }

    @Test
    @DisplayName("getMargin: 환율 조회 실패 시 USD·KRW 금액은 원본 반환")
    void getMargin_exchangeRateZero_returnsOriginalAmounts() {
        stubNoAccountHeader();
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                org.springframework.util.MultiValueMap<String, String> params =
                    (org.springframework.util.MultiValueMap<String, String>) inv.getArgument(2);
                String currency = params.getFirst("currency");
                String amount = "USD".equals(currency) ? "100.00" : "50000";
                return new TossResult<>(new TossHoldingsApi.BuyableAmountResponse(amount, currency));
            });
        when(tossHttpClient.getCommon(eq("/api/v1/exchange-rate"), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(null);

        List<MarginItem> items = tossHoldingsApi.getMargin(ACCOUNT);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).currency()).isEqualTo(Currency.USD);
        assertThat(items.get(0).purchasableAmount()).isEqualByComparingTo("100.00");
        assertThat(items.get(1).currency()).isEqualTo(Currency.KRW);
        assertThat(items.get(1).purchasableAmount()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("현재 환율 조회: USD/KRW만 전달하고 과거 dateTime은 보내지 않는다")
    void getExchangeRate_usesCurrentUsdKrwQueryOnly() {
        when(tossHttpClient.getCommon(
                eq("/api/v1/exchange-rate"), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(new TossResult<>(
                        new TossHoldingsApi.ExchangeRateResult("1370.00", "1365.20")));

        var exchangeRate = tossHoldingsApi.getExchangeRate();

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<MultiValueMap<String, String>> paramsCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(MultiValueMap.class);
        verify(tossHttpClient).getCommon(
                eq("/api/v1/exchange-rate"), paramsCaptor.capture(),
                any(ParameterizedTypeReference.class));
        assertThat(paramsCaptor.getValue().toSingleValueMap())
                .containsExactlyInAnyOrderEntriesOf(
                        java.util.Map.of("baseCurrency", "USD", "quoteCurrency", "KRW"))
                .doesNotContainKey("dateTime");
        assertThat(exchangeRate.midRate()).isEqualByComparingTo("1365.20");
    }

    @Test
    @DisplayName("환율 캐시 히트: 60초 내 재조회 시 HTTP 호출은 1회만 발생한다")
    void getExchangeRate_cachedWithinTtl_callsHttpOnce() {
        when(tossHttpClient.getCommon(
                eq("/api/v1/exchange-rate"), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(new TossResult<>(
                        new TossHoldingsApi.ExchangeRateResult("1400.00", "1400.00")));

        var first = tossHoldingsApi.getExchangeRate();
        var second = tossHoldingsApi.getExchangeRate();

        assertThat(first.rate()).isEqualByComparingTo("1400.00");
        assertThat(second.rate()).isEqualByComparingTo("1400.00");
        verify(tossHttpClient, org.mockito.Mockito.times(1)).getCommon(
                eq("/api/v1/exchange-rate"), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("환율 조회 실패(ZERO 폴백)는 캐싱되지 않고 매번 재조회한다")
    void getExchangeRate_failureNotCached_callsHttpEveryTime() {
        when(tossHttpClient.getCommon(
                eq("/api/v1/exchange-rate"), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(null);

        var first = tossHoldingsApi.getExchangeRate();
        var second = tossHoldingsApi.getExchangeRate();

        assertThat(first.rate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(second.rate()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(tossHttpClient, org.mockito.Mockito.times(2)).getCommon(
                eq("/api/v1/exchange-rate"), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("getPresentBalance: holdings·USD·KRW·환율 4개 호출이 병렬 실행된다")
    void getPresentBalance_fourCallsRunInParallel() throws Exception {
        // 4개 독립 호출이 모두 barrier에 동시 도달해야만 진행 — 순차 실행이면 타임아웃으로 실패
        CyclicBarrier barrier = new CyclicBarrier(4);

        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenAnswer(inv -> {
                barrier.await(2, TimeUnit.SECONDS);
                return new TossResult<>(new TossHoldingsApi.HoldingsResponse(List.of()));
            });
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenAnswer(inv -> {
                barrier.await(2, TimeUnit.SECONDS);
                @SuppressWarnings("unchecked")
                MultiValueMap<String, String> params = (MultiValueMap<String, String>) inv.getArgument(2);
                String currency = params.getFirst("currency");
                String amount = "USD".equals(currency) ? "100.00" : "140000";
                return new TossResult<>(new TossHoldingsApi.BuyableAmountResponse(amount, currency));
            });
        when(tossHttpClient.getCommon(eq("/api/v1/exchange-rate"), any(), any(ParameterizedTypeReference.class)))
            .thenAnswer(inv -> {
                barrier.await(2, TimeUnit.SECONDS);
                return new TossResult<>(new TossHoldingsApi.ExchangeRateResult("1400.00", "1400.00"));
            });

        PresentBalanceResult result = tossHoldingsApi.getPresentBalance(ACCOUNT);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("getMargin: USD·KRW·환율 3개 호출이 병렬 실행된다")
    void getMargin_threeCallsRunInParallel() throws Exception {
        // 3개 독립 호출이 모두 barrier에 동시 도달해야만 진행 — 순차 실행이면 타임아웃으로 실패
        CyclicBarrier barrier = new CyclicBarrier(3);

        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenAnswer(inv -> {
                barrier.await(2, TimeUnit.SECONDS);
                @SuppressWarnings("unchecked")
                MultiValueMap<String, String> params = (MultiValueMap<String, String>) inv.getArgument(2);
                String currency = params.getFirst("currency");
                String amount = "USD".equals(currency) ? "100.00" : "140000";
                return new TossResult<>(new TossHoldingsApi.BuyableAmountResponse(amount, currency));
            });
        when(tossHttpClient.getCommon(eq("/api/v1/exchange-rate"), any(), any(ParameterizedTypeReference.class)))
            .thenAnswer(inv -> {
                barrier.await(2, TimeUnit.SECONDS);
                return new TossResult<>(new TossHoldingsApi.ExchangeRateResult("1400.00", "1400.00"));
            });

        List<MarginItem> items = tossHoldingsApi.getMargin(ACCOUNT);

        assertThat(items).hasSize(2);
    }

    @Test
    @DisplayName("병렬 호출 중 TossApiException 발생 시 언래핑되어 그대로 전파된다")
    void getMargin_exceptionInParallelTask_propagatesUnwrapped() {
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenThrow(new TossApiException("Toss 매수가능금액 조회 실패", null));
        when(tossHttpClient.getCommon(eq("/api/v1/exchange-rate"), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.ExchangeRateResult("1400.00", "1400.00")));

        assertThatThrownBy(() -> tossHoldingsApi.getMargin(ACCOUNT))
            .isInstanceOf(TossApiException.class)
            .hasMessage("Toss 매수가능금액 조회 실패");
    }
}
