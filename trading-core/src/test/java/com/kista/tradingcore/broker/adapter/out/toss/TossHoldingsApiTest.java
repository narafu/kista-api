package com.kista.tradingcore.broker.adapter.out.toss;

import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.broker.domain.model.PresentBalanceResult;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.toss.TossApiException;
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

import static com.kista.shared.support.FixedClockTestConfig.FIXED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("TossHoldingsApi 단위 테스트")
class TossHoldingsApiTest {

    @Mock TossHttpClient tossHttpClient;
    TossHoldingsApi tossHoldingsApi;

    static final BrokerAccountRef ACCOUNT = new BrokerAccountRef(
        UUID.randomUUID(), "cid", "csecret",
        "12345678901", "1", Broker.TOSS
    );

    @BeforeEach
    void setUp() {
        tossHoldingsApi = new TossHoldingsApi(tossHttpClient, FIXED);
    }

    // exchange-rate API는 getCommon 사용 — 개별 테스트에서 직접 stub
    private void stubNoAccountHeader() {}

    @Test
    @DisplayName("getUsdBuyableAmount: 정상 금액 반환")
    void getUsdBuyableAmount_returnsAmount() {
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.BuyableAmountResponse("1234.56")));

        BigDecimal amount = tossHoldingsApi.getUsdBuyableAmount(ACCOUNT);

        assertThat(amount).isEqualByComparingTo("1234.56");
    }

    @Test
    @DisplayName("getUsdBuyableAmount: wrapper null → TossApiException")
    void getUsdBuyableAmount_nullWrapper_throwsTossApiException() {
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(null);

        assertThatThrownBy(() -> tossHoldingsApi.getUsdBuyableAmount(ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("getUsdBuyableAmount: cashBuyingPower 필드 null → TossApiException")
    void getUsdBuyableAmount_nullCashBuyingPower_throwsTossApiException() {
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.BuyableAmountResponse(null)));

        assertThatThrownBy(() -> tossHoldingsApi.getUsdBuyableAmount(ACCOUNT))
            .isInstanceOf(TossApiException.class);
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
    @DisplayName("getPresentBalance: 병렬 호출 중 TossApiException 발생 시 언래핑되어 그대로 전파된다")
    void getPresentBalance_exceptionInParallelTask_propagatesUnwrapped() {
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.HoldingsResponse(List.of())));
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenThrow(new TossApiException("Toss 매수가능금액 조회 실패", null));
        when(tossHttpClient.getCommon(eq("/api/v1/exchange-rate"), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.ExchangeRateResult("1400.00", "1400.00")));

        assertThatThrownBy(() -> tossHoldingsApi.getPresentBalance(ACCOUNT))
            .isInstanceOf(TossApiException.class)
            .hasMessage("Toss 매수가능금액 조회 실패");
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
                return new TossResult<>(new TossHoldingsApi.BuyableAmountResponse(amount));
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
    @DisplayName("판매 가능 수량은 미체결 매도를 빼지 않은 보유 수량 — /sellable-quantity(순수량)를 쓰면 배정이 PLACED SELL을 이중 차감한다")
    void getSellableQuantity_returnsHoldingQuantityNotNetSellable() {
        // 실측(2026-10-17): 보유 30·미체결 매도 1일 때 /sellable-quantity=29 — 포트 계약(Toss ovrs_cblc_qty·Mock과 동일)은 총량 30
        var item = new TossHoldingsApi.HoldingItem("SOXL", "30", "101", "100.33");
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.HoldingsResponse(List.of(item))));

        assertThat(tossHoldingsApi.getSellableQuantity(StrategyTicker.SOXL, ACCOUNT).quantity()).isEqualTo(30);
        verify(tossHttpClient, never()).get(eq("/api/v1/sellable-quantity"), any(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("판매 가능 수량 — 미보유 종목은 0")
    void getSellableQuantity_noHolding_returnsZero() {
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.HoldingsResponse(List.of())));

        assertThat(tossHoldingsApi.getSellableQuantity(StrategyTicker.SOXL, ACCOUNT).quantity()).isZero();
    }

    // 스펙 HoldingsItem.quantity는 format decimal 문자열 — 소수점 보유(예: "10.5")가 오면 정수 파싱이 보유 조회 전체를 죽였다
    @Test
    @DisplayName("소수 수량 보유는 정수 내림 — 1주 미만 종목은 보유 현황에서 제외")
    void fractionalQuantity_isFlooredInsteadOfFailing() {
        var soxl = new TossHoldingsApi.HoldingItem("SOXL", "10.5", "101", "100.33");
        var tqqq = new TossHoldingsApi.HoldingItem("TQQQ", "0.3", "50", "55");
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.HoldingsResponse(List.of(soxl, tqqq))));
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.BuyableAmountResponse("100.00")));
        when(tossHttpClient.getCommon(eq("/api/v1/exchange-rate"), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.ExchangeRateResult("1400.00", "1400.00")));

        assertThat(tossHoldingsApi.getSellableQuantity(StrategyTicker.SOXL, ACCOUNT).quantity()).isEqualTo(10);
        assertThat(tossHoldingsApi.getPresentBalance(ACCOUNT).items()).singleElement()
            .satisfies(i -> assertThat(i.holdings()).isEqualTo(10));
    }
}
