package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.platform.persistence.BaseCreatedAtEntity;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaSizing;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "strategy_dca_rule", schema = "trading")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyDcaRuleEntity extends BaseCreatedAtEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id; // PK

    @Column(name = "strategy_version_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyVersionId; // FK → strategy_version.id (ON DELETE CASCADE)

    @Column(name = "rule_key", nullable = false, length = 36)
    private String ruleKey; // 버전 간 안정 식별자 (UUID 문자열)

    @Column(name = "sort_order", nullable = false)
    private int sortOrder; // 입력 순서

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private OrderDirection direction; // 매수/매도

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private DcaFrequency frequency; // 실행 주기

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 20)
    private DayOfWeek dayOfWeek; // WEEKLY 전용

    @Column(name = "day_of_month")
    private Integer dayOfMonth; // MONTHLY 전용 (null이면 말일)

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 20)
    private OrderType orderType; // MOO | MOC | MARKET

    @Column(name = "execution_hour")
    private Short executionHour; // 코인 실행 시각 (KST 0~23) — smallint라 Short로 매핑(Integer면 validate 불일치)

    @Enumerated(EnumType.STRING)
    @Column(name = "sizing_type", nullable = false, length = 20)
    private DcaSizingType sizingType; // AMOUNT | HOLDING_PERCENT

    @Column(name = "amount", precision = 20, scale = 2)
    private BigDecimal amount; // AMOUNT 전용 회차 금액 (계좌 통화)

    @Column(name = "percent", precision = 5, scale = 2)
    private BigDecimal percent; // HOLDING_PERCENT 전용 보유 비율 (0 초과 100 이하)

    @Enumerated(EnumType.STRING)
    @Column(name = "shortfall_policy", nullable = false, length = 20)
    private DcaShortfallPolicy shortfallPolicy; // 가용 부족 정책

    static StrategyDcaRuleEntity of(UUID strategyVersionId, int sortOrder, DcaRule r) {
        StrategyDcaRuleEntity e = new StrategyDcaRuleEntity();
        e.strategyVersionId = strategyVersionId;
        e.ruleKey = Objects.requireNonNull(r.ruleKey(), "ruleKey는 저장 전에 확정돼야 합니다");
        e.sortOrder = sortOrder;
        e.direction = r.direction();
        e.frequency = r.frequency();
        e.dayOfWeek = r.dayOfWeek();
        e.dayOfMonth = r.dayOfMonth();
        e.orderType = r.orderType();
        e.executionHour = r.executionHour() == null ? null : r.executionHour().shortValue();
        e.sizingType = r.sizing().type();
        // DB numeric(_,2)와 같은 scale로 정규화 — save() 반환값과 조회값을 일치시킨다
        e.amount = scale2(r.sizing().amount());
        e.percent = scale2(r.sizing().percent());
        e.shortfallPolicy = r.shortfall();
        return e;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    DcaRule toDomain(List<DcaCondition> conditions) {
        return new DcaRule(ruleKey, direction, frequency, dayOfWeek, dayOfMonth, orderType,
                executionHour == null ? null : executionHour.intValue(),
                new DcaSizing(sizingType, amount, percent), shortfallPolicy, conditions);
    }
}
