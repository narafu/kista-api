package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

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
        when(tossHttpClient.buildHeaders(any())).thenReturn(new HttpHeaders());
        tosHoldingsApi = new TosHoldingsApi(tossHttpClient);
    }

    @Test
    @DisplayName("보유 종목 있음: 정상 AccountBalance 반환")
    void getBalance_holdingFound_returnsBalance() {
        var item = new TosHoldingsApi.HoldingItem("SOXL", "5", "20.00", "22.00");
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), eq(TosHoldingsApi.HoldingsResponse.class)))
            .thenReturn(new TosHoldingsApi.HoldingsResponse(List.of(item)));
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), eq(TosHoldingsApi.BuyableAmountResponse.class)))
            .thenReturn(new TosHoldingsApi.BuyableAmountResponse("1000.00", "USD"));

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
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), eq(TosHoldingsApi.BuyableAmountResponse.class)))
            .thenReturn(new TosHoldingsApi.BuyableAmountResponse("500.00", "USD"));

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
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), eq(TosHoldingsApi.BuyableAmountResponse.class)))
            .thenReturn(null);

        AccountBalance balance = tosHoldingsApi.getBalance(ACCOUNT, Ticker.SOXL);

        assertThat(balance.holdings()).isEqualTo(0);
        assertThat(balance.usdDeposit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("getBuyableAmount: 정상 금액 반환")
    void getBuyableAmount_returnsAmount() {
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), eq(TosHoldingsApi.BuyableAmountResponse.class)))
            .thenReturn(new TosHoldingsApi.BuyableAmountResponse("1234.56", "USD"));

        BigDecimal amount = tosHoldingsApi.getBuyableAmount(ACCOUNT);

        assertThat(amount).isEqualByComparingTo("1234.56");
    }
}
