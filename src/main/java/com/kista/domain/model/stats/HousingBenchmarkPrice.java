package com.kista.domain.model.stats;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

// KB Land 주택 벤치마크 월별 분위 가격
public record HousingBenchmarkPrice(
        String source,
        String metricCode,
        String regionCode,
        String regionName,
        LocalDate baseMonth,
        BigDecimal firstQuintilePrice,
        BigDecimal secondQuintilePrice,
        BigDecimal thirdQuintilePrice,
        BigDecimal fourthQuintilePrice,
        BigDecimal fifthQuintilePrice,
        BigDecimal fifthQuintileRatio,
        LocalDate sourceUpdatedDate,
        Instant fetchedAt
) {
    public static final String SOURCE_KBLAND = "KBLAND"; // 데이터 출처: KB Land
    public static final String METRIC_APT_QTE_SALE_PRICE = "APT_QTE_SALE_PRICE"; // 아파트 5분위 매매평균가격
}
