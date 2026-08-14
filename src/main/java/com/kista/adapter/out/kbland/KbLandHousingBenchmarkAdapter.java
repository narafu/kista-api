package com.kista.adapter.out.kbland;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.stats.HousingBenchmarkPrice;
import com.kista.domain.model.stats.HousingPriceIndex;
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
import java.time.format.DateTimeParseException;
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

    // years는 요청마다 동적으로 붙는다 (매주는 최근 구간만, 매월 풀 리프레시는 20년 전체)
    private static final String WEEKLY_APT_SALE_PRICE_INDEX_PATH = "/bfmstat/weekMnthlyHuseTrnd/priceIndex"
            + "?%EB%A7%A4%EB%AC%BC%EC%A2%85%EB%B3%84%EA%B5%AC%EB%B6%84=01"
            + "&%EB%A7%A4%EB%A7%A4%EC%A0%84%EC%84%B8%EC%BD%94%EB%93%9C=01"
            + "&%EC%9B%94%EA%B0%84%EC%A3%BC%EA%B0%84%EA%B5%AC%EB%B6%84%EC%BD%94%EB%93%9C=02"
            + "&%EA%B8%B0%EA%B0%84=";

    private static final DateTimeFormatter BASE_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter UPDATED_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RestTemplate kbLandRestTemplate; // 빈 이름: kbLandRestTemplate
    private final KbLandProperties properties;

    @Override
    public List<HousingBenchmarkPrice> fetchAptQteSalePrices() {
        KbLandResponse response = kbLandRestTemplate.getForObject(requestUri(), KbLandResponse.class);
        if (response == null) {
            throw new IllegalStateException("KB Land 주택 벤치마크 API 응답이 비어있음");
        }
        // resultCode 검증을 먼저 수행 — 실패 시 body가 비어있는 게 정상이라 body 검증보다 우선한다.
        if (response.dataHeader() == null || !"10000".equals(response.dataHeader().resultCode())) {
            throw new IllegalStateException("KB Land 주택 벤치마크 API 오류: resultCode="
                    + (response.dataHeader() == null ? null : response.dataHeader().resultCode()));
        }
        if (response.dataBody() == null || response.dataBody().data() == null) {
            throw new IllegalStateException("KB Land 주택 벤치마크 API 응답이 비어있음");
        }

        KbLandData data = response.dataBody().data();
        LocalDate sourceUpdatedDate = parseUpdatedDate(data.updatedDate());
        Instant fetchedAt = Instant.now();
        List<HousingBenchmarkPrice> prices = new ArrayList<>();

        // 지역별 dataList를 월별 도메인 행으로 평탄화한다.
        for (KbLandRegion region : data.regionList()) {
            if (region.dataList() == null) continue;
            for (KbLandMonthlyPrice monthlyPrice : region.dataList()) {
                // 5분위 필드 중 하나라도 결측이면 해당 행을 건너뛴다.
                if (!hasCompleteQuintileData(monthlyPrice)) {
                    log.warn("KB Land 5분위 가격 결측 스킵: region={}({}), baseMonth={}",
                            region.regionCode(), region.regionName(), monthlyPrice.baseMonth());
                    continue;
                }
                prices.add(toDomain(region, monthlyPrice, sourceUpdatedDate, fetchedAt));
            }
        }

        log.info("KB Land 주택 벤치마크 조회 완료: rows={}", prices.size());
        return prices;
    }

    @Override
    public List<HousingPriceIndex> fetchWeeklyAptSalePriceIndex(int years) {
        KbLandIndexResponse response = kbLandRestTemplate.getForObject(weeklyIndexRequestUri(years), KbLandIndexResponse.class);
        if (response == null) {
            throw new IllegalStateException("KB Land 주간 아파트 매매가격지수 API 응답이 비어있음");
        }
        // resultCode 검증을 먼저 수행 — 실패 시 body가 비어있는 게 정상이라 body 검증보다 우선한다.
        if (response.dataHeader() == null || !"10000".equals(response.dataHeader().resultCode())) {
            throw new IllegalStateException("KB Land 주간 아파트 매매가격지수 API 오류: resultCode="
                    + (response.dataHeader() == null ? null : response.dataHeader().resultCode()));
        }
        if (response.dataBody() == null || response.dataBody().data() == null) {
            throw new IllegalStateException("KB Land 주간 아파트 매매가격지수 API 응답이 비어있음");
        }

        KbLandIndexData data = response.dataBody().data();
        List<String> baseDates = data.baseDates();
        LocalDate sourceUpdatedDate = parseUpdatedDate(data.updatedDate());
        Instant fetchedAt = Instant.now();
        List<HousingPriceIndex> indices = new ArrayList<>();
        if (baseDates == null || data.regionList() == null) {
            return indices;
        }

        // 지역마다 반복 파싱하지 않도록 baseDates를 1회만 LocalDate로 변환한다(형식 오류 시 해당 인덱스는 null로 남겨 skip).
        List<LocalDate> parsedBaseDates = parseBaseDates(baseDates);

        // 지역별 dataList를 주별 도메인 행으로 평탄화한다. dataList 마지막 원소는 지수가 아닌 전주대비 변동률이라 baseDates 길이로 zip한다.
        for (KbLandIndexRegion region : data.regionList()) {
            if (region.dataList() == null) continue;
            int size = Math.min(parsedBaseDates.size(), region.dataList().size());
            for (int i = 0; i < size; i++) {
                BigDecimal indexValue = region.dataList().get(i);
                LocalDate baseDate = parsedBaseDates.get(i);
                if (indexValue == null || baseDate == null) {
                    log.warn("KB Land 주간 아파트 매매가격지수 결측 스킵: region={}({}), baseDate={}",
                            region.regionCode(), region.regionName(), baseDates.get(i));
                    continue;
                }
                indices.add(new HousingPriceIndex(
                        HousingPriceIndex.SOURCE_KBLAND,
                        HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX,
                        region.regionCode(),
                        region.regionName(),
                        baseDate,
                        indexValue,
                        sourceUpdatedDate,
                        fetchedAt
                ));
            }
        }

        log.info("KB Land 주간 아파트 매매가격지수 조회 완료: rows={}", indices.size());
        return indices;
    }

    private URI requestUri() {
        return URI.create(trimTrailingSlash(properties.baseUrl()) + APT_QTE_SALE_PRICE_PATH);
    }

    private URI weeklyIndexRequestUri(int years) {
        return URI.create(trimTrailingSlash(properties.baseUrl()) + WEEKLY_APT_SALE_PRICE_INDEX_PATH + years);
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

    // 날짜리스트를 1회만 파싱 — 형식 오류나 null은 해당 인덱스만 null로 남겨 skip 대상으로 취급한다.
    private static List<LocalDate> parseBaseDates(List<String> baseDates) {
        List<LocalDate> parsed = new ArrayList<>(baseDates.size());
        for (String baseDate : baseDates) {
            try {
                parsed.add(baseDate == null ? null : LocalDate.parse(baseDate, UPDATED_DATE_FORMATTER));
            } catch (DateTimeParseException e) {
                log.warn("KB Land 주간 아파트 매매가격지수 날짜 파싱 실패 스킵: baseDate={}", baseDate);
                parsed.add(null);
            }
        }
        return parsed;
    }

    private static String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    // 월별 데이터가 모든 필수 5분위 필드를 포함하는지 확인한다.
    private static boolean hasCompleteQuintileData(KbLandMonthlyPrice monthlyPrice) {
        return monthlyPrice.firstQuintilePrice() != null
                && monthlyPrice.secondQuintilePrice() != null
                && monthlyPrice.thirdQuintilePrice() != null
                && monthlyPrice.fourthQuintilePrice() != null
                && monthlyPrice.fifthQuintilePrice() != null
                && monthlyPrice.fifthQuintileRatio() != null;
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

    // KB Land 주간 아파트 매매가격지수 응답 래퍼 (5분위 응답과 구조가 달라 별도 record로 분리)
    record KbLandIndexResponse(
            @JsonProperty("dataHeader") KbLandHeader dataHeader,
            @JsonProperty("dataBody") KbLandIndexBody dataBody
    ) {}

    record KbLandIndexBody(@JsonProperty("data") KbLandIndexData data) {}

    record KbLandIndexData(
            @JsonProperty("업데이트일자") String updatedDate,
            @JsonProperty("날짜리스트") List<String> baseDates,
            @JsonProperty("데이터리스트") List<KbLandIndexRegion> regionList
    ) {}

    record KbLandIndexRegion(
            @JsonProperty("지역코드") String regionCode,
            @JsonProperty("지역명") String regionName,
            @JsonProperty("dataList") List<BigDecimal> dataList
    ) {}
}
