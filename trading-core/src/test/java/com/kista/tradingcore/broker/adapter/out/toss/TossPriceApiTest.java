package com.kista.tradingcore.broker.adapter.out.toss;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.toss.TossApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TossPriceApi 단위 테스트")
class TossPriceApiTest {

    @Mock TossHttpClient tossHttpClient;
    TossPriceApi tossPriceApi;
    BrokerAccountRef account = new BrokerAccountRef(UUID.randomUUID(), "key", "secret", "12345678", null, Broker.TOSS);

    @BeforeEach
    void setUp() {
        tossPriceApi = new TossPriceApi(tossHttpClient);
    }

    // Toss API 응답: TossResult<List<PriceItem>> 래퍼 헬퍼
    private static TossResult<List<TossPriceApi.PriceItem>> wrap(TossPriceApi.PriceItem... items) {
        return new TossResult<>(List.of(items));
    }

    @Test
    @DisplayName("계좌 토큰 현재가는 get(계좌)으로 호출하고 getCommon을 쓰지 않으며 누락 종목은 키가 없다")
    void currentPrices_usesAccountTokenAndOmitsMissing() {
        when(tossHttpClient.get(eq("/api/v1/prices"), eq(account), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap(new TossPriceApi.PriceItem("SOXL", "33.14")));

        Map<StrategyTicker, BigDecimal> result =
                tossPriceApi.currentPrices(account, List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ));

        assertThat(result).containsOnlyKeys(StrategyTicker.SOXL);
        assertThat(result.get(StrategyTicker.SOXL)).isEqualByComparingTo("33.14");
        verify(tossHttpClient, never()).getCommon(any(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("lastPrice null·비숫자·0 이하인 종목만 건너뛰고 나머지는 반환")
    void currentPrices_badLastPrice_skipsOnlyThatTicker() {
        when(tossHttpClient.get(eq("/api/v1/prices"), eq(account), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap(new TossPriceApi.PriceItem("SOXL", "abc"),
                    new TossPriceApi.PriceItem("TQQQ", "30.10"),
                    new TossPriceApi.PriceItem("UPRO", null),
                    new TossPriceApi.PriceItem("QLD", "0")));

        Map<StrategyTicker, BigDecimal> result = tossPriceApi.currentPrices(account, List.of(StrategyTicker.SOXL, StrategyTicker.TQQQ));

        assertThat(result).containsOnlyKeys(StrategyTicker.TQQQ);
    }

    @Test
    @DisplayName("null 응답 시 예외 (전체 실패)")
    void currentPrices_nullResponse_throws() {
        when(tossHttpClient.get(any(), any(), any(), any(ParameterizedTypeReference.class))).thenReturn(null);

        assertThatThrownBy(() -> tossPriceApi.currentPrices(account, List.of(StrategyTicker.SOXL)))
                .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("빈 tickers 목록 요청 시 HTTP 미호출 후 빈 Map 반환")
    void currentPrices_emptyTickers_returnsEmptyMapWithoutHttpCall() {
        assertThat(tossPriceApi.currentPrices(account, List.of())).isEmpty();
    }
}
