package com.kista.adapter.out.kbland;

import com.kista.domain.model.stats.HousingBenchmarkPrice;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KbLandHousingBenchmarkAdapterTest {

    @Test
    void fetchAptQteSalePrices_parsesRegionQuintileMonthlyPrices() {
        RestTemplate restTemplate = new KbLandConfig().kbLandRestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        KbLandProperties properties = new KbLandProperties("https://data-api.kbland.kr");
        KbLandHousingBenchmarkAdapter adapter = new KbLandHousingBenchmarkAdapter(restTemplate, properties);

        String responseBody = """
                {
                  "dataHeader": {"resultCode": "10000"},
                  "dataBody": {
                    "data": {
                      "업데이트일자": "20260615",
                      "날짜리스트": ["202606"],
                      "데이터리스트": [
                        {
                          "지역코드": "1100000000",
                          "지역명": "서울",
                          "dataList": [
                            {
                              "기준날짜": "202606",
                              "1분위": 52600.99032935,
                              "2분위": 86950.46024049,
                              "3분위": 126352.960785,
                              "4분위": 181363.60544355,
                              "5분위": 344468.13329238,
                              "5분위배율": 6.548700530837
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;

        server.expect(requestTo("https://data-api.kbland.kr/bfmstat/weekMnthlyHuseTrnd/avgPrcPerPorela?title=%EC%95%84%ED%8C%8C%ED%8A%B8+5%EB%B6%84%EC%9C%84+%EB%A7%A4%EB%A7%A4%ED%8F%89%EA%B7%A0%EA%B0%80%EA%B2%A9&%EB%A7%A4%EB%A7%A4%EC%A0%84%EC%84%B8%EC%BD%94%EB%93%9C=01&%EB%A9%94%EB%89%B4%EC%BD%94%EB%93%9C=01&%EA%B8%B0%EA%B0%84=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("osType", "HUB"))
                .andExpect(header("Referer", "https://data.kbland.kr/"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<HousingBenchmarkPrice> prices = adapter.fetchAptQteSalePrices();

        assertThat(prices).hasSize(1);
        HousingBenchmarkPrice seoul = prices.get(0);
        assertThat(seoul.source()).isEqualTo("KBLAND");
        assertThat(seoul.metricCode()).isEqualTo("APT_QTE_SALE_PRICE");
        assertThat(seoul.regionCode()).isEqualTo("1100000000");
        assertThat(seoul.regionName()).isEqualTo("서울");
        assertThat(seoul.baseMonth()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(seoul.firstQuintilePrice()).isEqualByComparingTo(new BigDecimal("52600.99032935"));
        assertThat(seoul.secondQuintilePrice()).isEqualByComparingTo(new BigDecimal("86950.46024049"));
        assertThat(seoul.thirdQuintilePrice()).isEqualByComparingTo(new BigDecimal("126352.960785"));
        assertThat(seoul.fourthQuintilePrice()).isEqualByComparingTo(new BigDecimal("181363.60544355"));
        assertThat(seoul.fifthQuintilePrice()).isEqualByComparingTo(new BigDecimal("344468.13329238"));
        assertThat(seoul.fifthQuintileRatio()).isEqualByComparingTo(new BigDecimal("6.548700530837"));
        assertThat(seoul.sourceUpdatedDate()).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(seoul.fetchedAt()).isNotNull();
        server.verify();
    }
}
