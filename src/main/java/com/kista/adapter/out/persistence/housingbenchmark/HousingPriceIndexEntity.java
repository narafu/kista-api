package com.kista.adapter.out.persistence.housingbenchmark;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.stats.HousingPriceIndex;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "housing_price_indices",
    schema = "reference",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_housing_price_indices_source_metric_region_date",
        columnNames = {"source", "metric_code", "region_code", "base_date"}
    )
)
@Getter
@NoArgsConstructor
class HousingPriceIndexEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source", nullable = false, length = 20)
    private String source; // 데이터 출처: KBLAND

    @Column(name = "metric_code", nullable = false, length = 40)
    private String metricCode; // 지표 코드: WEEKLY_APT_SALE_PRICE_INDEX

    @Column(name = "region_code", nullable = false, length = 20)
    private String regionCode; // KB Land 지역 코드

    @Column(name = "region_name", nullable = false, length = 50)
    private String regionName; // KB Land 지역명

    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate; // 기준일(주 단위, 매주 월요일)

    @Column(name = "index_value", nullable = false, precision = 18, scale = 12)
    private BigDecimal indexValue; // 매매가격지수

    @Column(name = "source_updated_date")
    private LocalDate sourceUpdatedDate; // KB Land 업데이트일자

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt; // API 조회 시각

    HousingPriceIndex toDomain() {
        return new HousingPriceIndex(
                source,
                metricCode,
                regionCode,
                regionName,
                baseDate,
                indexValue,
                sourceUpdatedDate,
                fetchedAt
        );
    }
}
