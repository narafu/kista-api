package com.kista.tradingcore.stats.adapter.out.frankfurter;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FrankfurterFxRateAdapterTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FrankfurterFxRateAdapter adapter = new FrankfurterFxRateAdapter(builder.build());

    @Test
    void 기간_환율을_날짜별_KRW로_매핑한다() {
        server.expect(requestTo("https://api.frankfurter.dev/v1/2026-09-01..2026-09-04?base=USD&symbols=KRW"))
                .andRespond(withSuccess("""
                        {"amount":1.0,"base":"USD","start_date":"2026-09-01","end_date":"2026-09-04","rates":{
                        "2026-09-01":{"KRW":1374.61},"2026-09-02":{"KRW":1375.00},
                        "2026-09-03":{"KRW":1372.50},"2026-09-04":{"KRW":1370.25}}}
                        """, MediaType.APPLICATION_JSON));

        Map<LocalDate, BigDecimal> result = adapter.getUsdKrwRates(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4));

        assertThat(result).hasSize(4);
        assertThat(result.get(LocalDate.of(2026, 9, 1))).isEqualByComparingTo("1374.61");
        server.verify();
    }

    @Test
    void rates_없으면_빈_맵() {
        server.expect(requestTo("https://api.frankfurter.dev/v1/2026-09-01..2026-09-04?base=USD&symbols=KRW"))
                .andRespond(withSuccess("{\"rates\":{}}", MediaType.APPLICATION_JSON));

        assertThat(adapter.getUsdKrwRates(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4))).isEmpty();
    }
}
