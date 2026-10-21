package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.platform.persistence.BaseCreatedAtEntity;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Entity
@Table(name = "strategy_dca_condition", schema = "trading")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyDcaConditionEntity extends BaseCreatedAtEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id; // PK

    @Column(name = "rule_id", nullable = false, columnDefinition = "UUID")
    private UUID ruleId; // FK → strategy_dca_rule.id (ON DELETE CASCADE)

    @Column(name = "sort_order", nullable = false)
    private int sortOrder; // 입력 순서

    @Enumerated(EnumType.STRING)
    @Column(name = "indicator", nullable = false, length = 20)
    private DcaIndicator indicator; // 지표 종류

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false, length = 20)
    private DcaOperator operator; // 비교 연산자

    @Column(name = "sma_period")
    private Integer smaPeriod; // SMA 전용 기간

    @Column(name = "threshold", precision = 10, scale = 2)
    private BigDecimal threshold; // SMA 외 기준값

    static StrategyDcaConditionEntity of(UUID ruleId, int sortOrder, DcaCondition c) {
        StrategyDcaConditionEntity e = new StrategyDcaConditionEntity();
        e.ruleId = ruleId;
        e.sortOrder = sortOrder;
        e.indicator = c.indicator();
        e.operator = c.operator();
        e.smaPeriod = c.smaPeriod();
        // DB numeric(10,2)와 같은 scale로 정규화 — save() 반환값과 조회값을 일치시킨다
        e.threshold = c.threshold() == null ? null : c.threshold().setScale(2, RoundingMode.HALF_UP);
        return e;
    }

    DcaCondition toDomain() {
        return new DcaCondition(indicator, operator, smaPeriod, threshold);
    }
}
