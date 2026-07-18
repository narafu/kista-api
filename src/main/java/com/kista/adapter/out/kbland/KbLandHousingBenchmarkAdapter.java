package com.kista.adapter.out.kbland;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.stats.HousingBenchmarkPrice;
import com.kista.domain.port.out.HousingBenchmarkFeedPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
class KbLandHousingBenchmarkAdapter implements HousingBenchmarkFeedPort {

    private static final String APT_QTE_SALE_PRICE_PATH = "/bfmstat/weekMnthlyHuseTrnd/avgPrcPerPorela"
            + "?title=%EC%95%84%ED%8C%8C%ED%8A%B8+5%EB%B6%84%EC%9C%84+%EB%A7%A4%EB%A7%A4%ED%8F%89%EA%B7%A0%EA%B0%80%EA%B2%A9"
            + "&%EB%A7%A4%EB%A7%A4%EC%A0%84%EC%84%B8%EC%BD%94%EB%93%9C=01"
            + "&%EB%A9%94%EB%89%B4%EC%BD%94%EB%93%9C=01"
            + "&%EA%B8%B0%EA%B0%84=1"; // 최근 1년치

    private static final DateTimeFormatter BASE_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter UPDATED_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RestTemplate kbLandRestTemplate; // 빈 이름: kbLandRestTemplate
    private final KbLandProperties properties;

    @Override
    public List<HousingBenchmarkPrice> fetchAptQteSalePrices() {
        KbLandResponse response = kbLandRestTemplate.getForObject(requestUri(), KbLandResponse.class);
        if (response == null || response.dataBody() == null || response.dataBody().data() == null) {
            throw new IllegalStateException("KB Land 주택 벤치마크 API 응답이 비어있음");
        }
        if (response.dataHeader() == null || !"10000".equals(response.dataHeader().resultCode())) {
            throw new IllegalStateException("KB Land 주택 벤치마크 API 오류: resultCode="
                    + (response.dataHeader() == null ? null : response.dataHeader().resultCode()));
        }

        KbLandData data = response.dataBody().data();
        LocalDate sourceUpdatedDate = parseUpdatedDate(data.updatedDate());
        Instant fetchedAt = Instant.now();
        List<HousingBenchmarkPrice> prices = new ArrayList<>();

        // 지역별 dataList를 월별 도메인 행으로 평탄화한다.
        for (KbLandRegion region : data.regionList()) {
            if (region.dataList() == null) continue;
            for (KbLandMonthlyPrice monthlyPrice : region.dataList()) {
                prices.add(toDomain(region, monthlyPrice, sourceUpdatedDate, fetchedAt));
            }
        }

        log.info("KB Land 주택 벤치마크 조회 완료: rows={}", prices.size());
        return prices;
    }

    private URI requestUri() {
        return URI.create(trimTrailingSlash(properties.baseUrl()) + APT_QTE_SALE_PRICE_PATH);
    }

    private static HousingBenchmarkPrice toDomain(
            KbLandRegion region,
            KbLandMonthlyPrice monthlyPrice,
            LocalDate sourceUpdatedDate,
            Instant fetchedAt) {
        return new HousingBenchmarkPrice(
                HousingBenchmarkPrice.SOURCE_KBLAND,
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE,
                region.regionCode(),
                region.regionName(),
                parseBaseMonth(monthlyPrice.baseMonth()),
                monthlyPrice.firstQuintilePrice(),
                monthlyPrice.secondQuintilePrice(),
                monthlyPrice.thirdQuintilePrice(),
                monthlyPrice.fourthQuintilePrice(),
                monthlyPrice.fifthQuintilePrice(),
                monthlyPrice.fifthQuintileRatio(),
                sourceUpdatedDate,
                fetchedAt
        );
    }

    private static LocalDate parseBaseMonth(String value) {
        return YearMonth.parse(value, BASE_MONTH_FORMATTER).atDay(1);
    }

    private static LocalDate parseUpdatedDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value, UPDATED_DATE_FORMATTER);
    }

    private static String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    // KB Land 공통 응답 래퍼
    record KbLandResponse(
            @JsonProperty("dataHeader") KbLandHeader dataHeader,
            @JsonProperty("dataBody") KbLandBody dataBody
    ) {}

    record KbLandHeader(@JsonProperty("resultCode") String resultCode) {}

    record KbLandBody(@JsonProperty("data") KbLandData data) {}

    record KbLandData(
            @JsonProperty("업데이트일자") String updatedDate,
            @JsonProperty("데이터리스트") List<KbLandRegion> regionList
    ) {}

    record KbLandRegion(
            @JsonProperty("지역코드") String regionCode,
            @JsonProperty("지역명") String regionName,
            @JsonProperty("dataList") List<KbLandMonthlyPrice> dataList
    ) {}

    record KbLandMonthlyPrice(
            @JsonProperty("기준날짜") String baseMonth,
            @JsonProperty("1분위") BigDecimal firstQuintilePrice,
            @JsonProperty("2분위") BigDecimal secondQuintilePrice,
            @JsonProperty("3분위") BigDecimal thirdQuintilePrice,
            @JsonProperty("4분위") BigDecimal fourthQuintilePrice,
            @JsonProperty("5분위") BigDecimal fifthQuintilePrice,
            @JsonProperty("5분위배율") BigDecimal fifthQuintileRatio
    ) {}
}
