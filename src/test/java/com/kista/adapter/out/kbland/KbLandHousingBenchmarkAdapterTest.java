package com.kista.adapter.out.kbland;

import com.kista.domain.model.stats.HousingBenchmarkPrice;
import com.kista.domain.model.stats.HousingPriceIndex;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KbLandHousingBenchmarkAdapterTest {

    @Test
    void fetchAptQteSalePrices_parsesRegionQuintileMonthlyPrices() {
        RestClient.Builder builder = RestClient.builder().requestFactory(KbLandConfig.kbLandRequestFactory()).requestInterceptor(KbLandConfig.kbLandHeaderInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KbLandProperties properties = new KbLandProperties("https://data-api.kbland.kr");
        KbLandHousingBenchmarkAdapter adapter = new KbLandHousingBenchmarkAdapter(builder.build(), properties);

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

    @Test
    void fetchAptQteSalePrices_skipsRowsWithMissingQuintileData() {
        RestClient.Builder builder = RestClient.builder().requestFactory(KbLandConfig.kbLandRequestFactory()).requestInterceptor(KbLandConfig.kbLandHeaderInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KbLandProperties properties = new KbLandProperties("https://data-api.kbland.kr");
        KbLandHousingBenchmarkAdapter adapter = new KbLandHousingBenchmarkAdapter(builder.build(), properties);

        String responseBody = """
                {
                  "dataHeader": {"resultCode": "10000"},
                  "dataBody": {
                    "data": {
                      "업데이트일자": "20260715",
                      "날짜리스트": ["202607"],
                      "데이터리스트": [
                        {
                          "지역코드": "1100000000",
                          "지역명": "서울",
                          "dataList": [
                            {
                              "기준날짜": "202607",
                              "1분위": 52600.99,
                              "2분위": 86950.46,
                              "3분위": 126352.96,
                              "4분위": 181363.60,
                              "5분위": 344468.13,
                              "5분위배율": 6.55
                            }
                          ]
                        },
                        {
                          "지역코드": "2900000000",
                          "지역명": "광주",
                          "dataList": [
                            {
                              "기준날짜": "202607",
                              "1분위": 10000.00,
                              "2분위": 20000.00,
                              "3분위": 30000.00,
                              "4분위": 40000.00,
                              "5분위": null,
                              "5분위배율": null
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

        // 정상 데이터만 포함되고 결측 데이터는 제외됨을 검증한다.
        assertThat(prices).hasSize(1);
        assertThat(prices).allMatch(p -> p.regionCode().equals("1100000000"), "only Seoul data should be included");
        HousingBenchmarkPrice seoul = prices.get(0);
        assertThat(seoul.regionName()).isEqualTo("서울");
        assertThat(seoul.fifthQuintilePrice()).isEqualByComparingTo(new BigDecimal("344468.13"));
        assertThat(seoul.fifthQuintileRatio()).isEqualByComparingTo(new BigDecimal("6.55"));
        server.verify();
    }

    private static final String WEEKLY_INDEX_URL = "https://data-api.kbland.kr/bfmstat/weekMnthlyHuseTrnd/priceIndex"
            + "?%EB%A7%A4%EB%AC%BC%EC%A2%85%EB%B3%84%EA%B5%AC%EB%B6%84=01"
            + "&%EB%A7%A4%EB%A7%A4%EC%A0%84%EC%84%B8%EC%BD%94%EB%93%9C=01"
            + "&%EC%9B%94%EA%B0%84%EC%A3%BC%EA%B0%84%EA%B5%AC%EB%B6%84%EC%BD%94%EB%93%9C=02"
            + "&%EA%B8%B0%EA%B0%84=5";

    @Test
    void fetchWeeklyAptSalePriceIndex_parsesRegionWeeklyIndexAndDropsTrailingChangeRateElement() {
        RestClient.Builder builder = RestClient.builder().requestFactory(KbLandConfig.kbLandRequestFactory()).requestInterceptor(KbLandConfig.kbLandHeaderInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KbLandProperties properties = new KbLandProperties("https://data-api.kbland.kr");
        KbLandHousingBenchmarkAdapter adapter = new KbLandHousingBenchmarkAdapter(builder.build(), properties);

        // 날짜리스트 3개, dataList 4개 — 마지막 원소(전주대비 변동률)는 zip에서 잘려야 한다.
        String responseBody = """
                {
                  "dataHeader": {"resultCode": "10000"},
                  "dataBody": {
                    "data": {
                      "업데이트일자": "20260803",
                      "날짜리스트": ["20260706", "20260713", "20260720"],
                      "데이터리스트": [
                        {
                          "지역코드": "1100000000",
                          "지역명": "서울",
                          "dataList": [100.0, 101.5, 102.75, 0.5]
                        }
                      ]
                    }
                  }
                }
                """;

        server.expect(requestTo(WEEKLY_INDEX_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("osType", "HUB"))
                .andExpect(header("Referer", "https://data.kbland.kr/"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<HousingPriceIndex> indices = adapter.fetchWeeklyAptSalePriceIndex(5);

        // 변동률 원소가 잘려 날짜리스트 개수(3개)만큼만 도메인 행이 생성된다.
        assertThat(indices).hasSize(3);
        assertThat(indices).extracting(HousingPriceIndex::baseDate).containsExactly(
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 20));
        assertThat(indices).extracting(HousingPriceIndex::indexValue).containsExactly(
                new BigDecimal("100.0"), new BigDecimal("101.5"), new BigDecimal("102.75"));
        HousingPriceIndex first = indices.get(0);
        assertThat(first.source()).isEqualTo("KBLAND");
        assertThat(first.metricCode()).isEqualTo("WEEKLY_APT_SALE_PRICE_INDEX");
        assertThat(first.regionCode()).isEqualTo("1100000000");
        assertThat(first.regionName()).isEqualTo("서울");
        assertThat(first.sourceUpdatedDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(first.fetchedAt()).isNotNull();
        server.verify();
    }

    @Test
    void fetchWeeklyAptSalePriceIndex_skipsNullIndexValues() {
        RestClient.Builder builder = RestClient.builder().requestFactory(KbLandConfig.kbLandRequestFactory()).requestInterceptor(KbLandConfig.kbLandHeaderInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KbLandProperties properties = new KbLandProperties("https://data-api.kbland.kr");
        KbLandHousingBenchmarkAdapter adapter = new KbLandHousingBenchmarkAdapter(builder.build(), properties);

        String responseBody = """
                {
                  "dataHeader": {"resultCode": "10000"},
                  "dataBody": {
                    "data": {
                      "업데이트일자": "20260803",
                      "날짜리스트": ["20260706", "20260713"],
                      "데이터리스트": [
                        {
                          "지역코드": "2900000000",
                          "지역명": "광주",
                          "dataList": [null, 50.25, 0.1]
                        }
                      ]
                    }
                  }
                }
                """;

        server.expect(requestTo(WEEKLY_INDEX_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        List<HousingPriceIndex> indices = adapter.fetchWeeklyAptSalePriceIndex(5);

        // null 값 첫 원소는 스킵되고 두 번째 값만 남는다.
        assertThat(indices).hasSize(1);
        assertThat(indices.get(0).baseDate()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(indices.get(0).indexValue()).isEqualByComparingTo(new BigDecimal("50.25"));
        server.verify();
    }

    @Test
    void fetchWeeklyAptSalePriceIndex_throwsWhenResultCodeIsNotSuccess() {
        RestClient.Builder builder = RestClient.builder().requestFactory(KbLandConfig.kbLandRequestFactory()).requestInterceptor(KbLandConfig.kbLandHeaderInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KbLandProperties properties = new KbLandProperties("https://data-api.kbland.kr");
        KbLandHousingBenchmarkAdapter adapter = new KbLandHousingBenchmarkAdapter(builder.build(), properties);

        String responseBody = """
                {
                  "dataHeader": {"resultCode": "90000"},
                  "dataBody": {"data": null}
                }
                """;

        server.expect(requestTo(WEEKLY_INDEX_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.fetchWeeklyAptSalePriceIndex(5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("90000");
        server.verify();
    }
}
