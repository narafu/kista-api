package com.kista.domain.model.stats;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

// KB Land 주간 아파트 매매가격지수
public record HousingPriceIndex(
        String source, // 데이터 출처: KBLAND
        String metricCode, // 지표 코드: WEEKLY_APT_SALE_PRICE_INDEX
        String regionCode, // KB Land 지역 코드
        String regionName, // KB Land 지역명
        LocalDate baseDate, // 기준일(주 단위, 매주 월요일)
        BigDecimal indexValue, // 매매가격지수
        LocalDate sourceUpdatedDate, // KB Land 업데이트일자
        Instant fetchedAt // API 조회 시각
) {
    public static final String SOURCE_KBLAND = "KBLAND"; // 데이터 출처: KB Land
    public static final String METRIC_WEEKLY_APT_SALE_PRICE_INDEX = "WEEKLY_APT_SALE_PRICE_INDEX"; // 주간 아파트 매매가격지수
}
