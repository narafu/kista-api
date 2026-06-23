package com.kista.adapter.out.kis;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisPriceApi 전일종가(prevClose) 산정 검증")
class KisPriceApiTest {

    @Mock KisHttpClient kisHttpClient;
    @Spy KisExchangeRegistry exchangeRegistry = new KisExchangeRegistry();
    @InjectMocks KisPriceApi api;

    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "appKey", "appSecret", null,
            Account.Broker.KIS, null
    );

    @Test
    @DisplayName("일별시세(dailyprice) 종가가 있으면 base 대신 그 값을 prevClose로 사용")
    void getPriceSnapshot_usesDailyPriceCloseAsPrevClose() {
        var priceResponse = new KisPriceApi.PriceResponse(
                new KisPriceApi.PriceResponse.Output("73.72", "76.27"));
        when(kisHttpClient.pricingGet(eq("HHDFS00000300"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.PriceResponse.class), any())).thenReturn(priceResponse);

        var dailyResponse = new KisPriceApi.DailyPriceResponse(
                List.of(new KisPriceApi.DailyPriceResponse.Output2("73.72")));
        when(kisHttpClient.pricingGet(eq("HHDFS76240000"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.DailyPriceResponse.class), any())).thenReturn(dailyResponse);

        PriceSnapshot snapshot = api.getPriceSnapshot(Ticker.TQQQ, ACCOUNT);

        assertThat(snapshot.current()).isEqualByComparingTo("73.72");
        assertThat(snapshot.prevClose()).isEqualByComparingTo("73.72");
    }

    @Test
    @DisplayName("일별시세 조회 실패 시 base 필드로 fallback")
    void getPriceSnapshot_fallsBackToBaseWhenDailyPriceFails() {
        var priceResponse = new KisPriceApi.PriceResponse(
                new KisPriceApi.PriceResponse.Output("73.72", "76.27"));
        when(kisHttpClient.pricingGet(eq("HHDFS00000300"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.PriceResponse.class), any())).thenReturn(priceResponse);

        when(kisHttpClient.pricingGet(eq("HHDFS76240000"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.DailyPriceResponse.class), any()))
                .thenReturn(new KisPriceApi.DailyPriceResponse(List.of()));

        PriceSnapshot snapshot = api.getPriceSnapshot(Ticker.TQQQ, ACCOUNT);

        assertThat(snapshot.current()).isEqualByComparingTo("73.72");
        assertThat(snapshot.prevClose()).isEqualByComparingTo("76.27");
    }
}
