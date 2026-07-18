package com.kista.adapter.out.persistence.housingbenchmark;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.stats.HousingBenchmarkPrice;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "housing_benchmark_prices",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_housing_benchmark_prices_source_metric_region_month",
        columnNames = {"source", "metric_code", "region_code", "base_month"}
    )
)
@Getter
@NoArgsConstructor
class HousingBenchmarkPriceEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source", nullable = false, length = 20)
    private String source; // 데이터 출처: KBLAND

    @Column(name = "metric_code", nullable = false, length = 40)
    private String metricCode; // 지표 코드: APT_QTE_SALE_PRICE

    @Column(name = "region_code", nullable = false, length = 20)
    private String regionCode; // KB Land 지역 코드

    @Column(name = "region_name", nullable = false, length = 50)
    private String regionName; // KB Land 지역명

    @Column(name = "base_month", nullable = false)
    private LocalDate baseMonth; // 기준 월, YYYY-MM-01

    @Column(name = "first_quintile_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal firstQuintilePrice; // 1분위 매매평균가격

    @Column(name = "second_quintile_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal secondQuintilePrice; // 2분위 매매평균가격

    @Column(name = "third_quintile_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal thirdQuintilePrice; // 3분위 매매평균가격

    @Column(name = "fourth_quintile_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal fourthQuintilePrice; // 4분위 매매평균가격

    @Column(name = "fifth_quintile_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal fifthQuintilePrice; // 5분위 매매평균가격

    @Column(name = "fifth_quintile_ratio", nullable = false, precision = 18, scale = 12)
    private BigDecimal fifthQuintileRatio; // 5분위 배율

    @Column(name = "source_updated_date")
    private LocalDate sourceUpdatedDate; // KB Land 업데이트일자

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt; // API 조회 시각

    HousingBenchmarkPrice toDomain() {
        return new HousingBenchmarkPrice(
                source,
                metricCode,
                regionCode,
                regionName,
                baseMonth,
                firstQuintilePrice,
                secondQuintilePrice,
                thirdQuintilePrice,
                fourthQuintilePrice,
                fifthQuintilePrice,
                fifthQuintileRatio,
                sourceUpdatedDate,
                fetchedAt
        );
    }
}
