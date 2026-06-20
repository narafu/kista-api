package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TosHoldingsApi 단위 테스트")
class TosHoldingsApiTest {

    @Mock TossHttpClient tossHttpClient;
    TosHoldingsApi tosHoldingsApi;

    static final Account ACCOUNT = new Account(
        UUID.randomUUID(), UUID.randomUUID(), "테스트",
        "12345678901", "cid", "csecret", "1", Account.Broker.TOSS
    );

    @BeforeEach
    void setUp() {
        tosHoldingsApi = new TosHoldingsApi(tossHttpClient);
    }

    // getMarginItems는 exchange-rate API에 getNoAccountHeader를 사용 — 별도 stub 불필요
    private void stubNoAccountHeader() {
        // buildHeadersNoAccount 제거됨 — getNoAccountHeader stub은 개별 테스트에서 직접 설정
    }

    @Test
    @DisplayName("보유 종목 있음: 정상 AccountBalance 반환")
    void getBalance_holdingFound_returnsBalance() {
        var item = new TosHoldingsApi.HoldingItem("SOXL", "5", "20.00", "22.00");
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), eq(TosHoldingsApi.HoldingsResponse.class)))
            .thenReturn(new TosHoldingsApi.HoldingsResponse(List.of(item)));
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), eq(TosHoldingsApi.BuyingPowerWrapper.class)))
            .thenReturn(new TosHoldingsApi.BuyingPowerWrapper(new TosHoldingsApi.BuyableAmountResponse("1000.00", "USD")));

        AccountBalance balance = tosHoldingsApi.getBalance(ACCOUNT, Ticker.SOXL);

        assertThat(balance.holdings()).isEqualTo(5);
        assertThat(balance.avgPrice()).isEqualByComparingTo("20.00");
        assertThat(balance.usdDeposit()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("보유 종목 없음: holdings=0, avgPrice=null")
    void getBalance_noHolding_returnsZeroBalance() {
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), eq(TosHoldingsApi.HoldingsResponse.class)))
            .thenReturn(new TosHoldingsApi.HoldingsResponse(List.of()));
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), eq(TosHoldingsApi.BuyingPowerWrapper.class)))
            .thenReturn(new TosHoldingsApi.BuyingPowerWrapper(new TosHoldingsApi.BuyableAmountResponse("500.00", "USD")));

        AccountBalance balance = tosHoldingsApi.getBalance(ACCOUNT, Ticker.SOXL);

        assertThat(balance.holdings()).isEqualTo(0);
        assertThat(balance.avgPrice()).isNull();
        assertThat(balance.usdDeposit()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("null 응답: holdings=0, usdDeposit=0")
    void getBalance_nullResponse_returnsZeroBalance() {
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), eq(TosHoldingsApi.HoldingsResponse.class)))
            .thenReturn(null);
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), eq(TosHoldingsApi.BuyingPowerWrapper.class)))
            .thenReturn(null);

        AccountBalance balance = tosHoldingsApi.getBalance(ACCOUNT, Ticker.SOXL);

        assertThat(balance.holdings()).isEqualTo(0);
        assertThat(balance.usdDeposit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("getBuyableAmount: 정상 금액 반환")
    void getBuyableAmount_returnsAmount() {
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), eq(TosHoldingsApi.BuyingPowerWrapper.class)))
            .thenReturn(new TosHoldingsApi.BuyingPowerWrapper(new TosHoldingsApi.BuyableAmountResponse("1234.56", "USD")));

        BigDecimal amount = tosHoldingsApi.getBuyableAmount(ACCOUNT);

        assertThat(amount).isEqualByComparingTo("1234.56");
    }

    @Test
    @DisplayName("getMarginItems: USD·KRW 통화별 2건 반환 (통합 아님)")
    void getMarginItems_returnsUsdAndKrwSeparately() {
        stubNoAccountHeader();
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), eq(TosHoldingsApi.BuyingPowerWrapper.class)))
            .thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                org.springframework.util.MultiValueMap<String, String> params =
                    (org.springframework.util.MultiValueMap<String, String>) inv.getArgument(2);
                String currency = params.getFirst("currency");
                String amount = "USD".equals(currency) ? "100.00" : "140000";
                return new TosHoldingsApi.BuyingPowerWrapper(new TosHoldingsApi.BuyableAmountResponse(amount, currency));
            });
        when(tossHttpClient.getNoAccountHeader(eq("/api/v1/exchange-rate"), any(), any(), eq(TosHoldingsApi.ExchangeRateWrapper.class)))
            .thenReturn(new TosHoldingsApi.ExchangeRateWrapper(new TosHoldingsApi.ExchangeRateResult("1400.00", "1400.00")));

        List<MarginItem> items = tosHoldingsApi.getMarginItems(ACCOUNT);

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
    @DisplayName("getMarginItems: 환율 조회 실패 시 USD·KRW 금액은 원본 반환")
    void getMarginItems_exchangeRateZero_returnsOriginalAmounts() {
        stubNoAccountHeader();
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), eq(TosHoldingsApi.BuyingPowerWrapper.class)))
            .thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                org.springframework.util.MultiValueMap<String, String> params =
                    (org.springframework.util.MultiValueMap<String, String>) inv.getArgument(2);
                String currency = params.getFirst("currency");
                String amount = "USD".equals(currency) ? "100.00" : "50000";
                return new TosHoldingsApi.BuyingPowerWrapper(new TosHoldingsApi.BuyableAmountResponse(amount, currency));
            });
        when(tossHttpClient.getNoAccountHeader(eq("/api/v1/exchange-rate"), any(), any(), eq(TosHoldingsApi.ExchangeRateWrapper.class)))
            .thenReturn(null);

        List<MarginItem> items = tosHoldingsApi.getMarginItems(ACCOUNT);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).currency()).isEqualTo(Currency.USD);
        assertThat(items.get(0).purchasableAmount()).isEqualByComparingTo("100.00");
        assertThat(items.get(1).currency()).isEqualTo(Currency.KRW);
        assertThat(items.get(1).purchasableAmount()).isEqualByComparingTo("50000");
    }
}
