package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Objects;

// DCA 전략 버전 설정 — strategy_dca_version 1행.
public record DcaConfig(
        OrderDirection direction, // 매수/매도 단방향
        BigDecimal amountUsd,     // 회차당 고정 금액 (USD, > 0)
        OrderType orderType,      // MOO(개장 배치) 또는 MOC(마감 배치)
        DcaFrequency frequency,   // 실행 주기
        DayOfWeek dayOfWeek,      // WEEKLY 전용 (월~금)
        Integer dayOfMonth,       // MONTHLY 전용 1~31, null이면 말일
        List<DcaCondition> conditions // 추가 조건 (AND, 입력 순서 보존, 최대 10개)
) {
    public static final int MAX_CONDITIONS = 10; // 전략당 조건 상한

    public DcaConfig {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(frequency, "frequency");
        if (amountUsd == null || amountUsd.signum() <= 0) throw new IllegalArgumentException("amountUsd는 0보다 커야 합니다");
        if (orderType != OrderType.MOO && orderType != OrderType.MOC) throw new IllegalArgumentException("DCA 주문유형은 MOO 또는 MOC만 허용합니다");
        boolean weekly = frequency == DcaFrequency.WEEKLY;
        boolean monthly = frequency == DcaFrequency.MONTHLY;
        if (weekly != (dayOfWeek != null)) throw new IllegalArgumentException("dayOfWeek는 WEEKLY에서만 필수입니다");
        if (weekly && (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY)) throw new IllegalArgumentException("주말 요일은 지정할 수 없습니다");
        if (!monthly && dayOfMonth != null) throw new IllegalArgumentException("dayOfMonth는 MONTHLY에서만 허용합니다");
        if (dayOfMonth != null && (dayOfMonth < 1 || dayOfMonth > 31)) throw new IllegalArgumentException("dayOfMonth는 1~31입니다");
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        if (conditions.size() > MAX_CONDITIONS) throw new IllegalArgumentException("DCA 조건은 최대 " + MAX_CONDITIONS + "개입니다.");
    }

    // 조건 없는 설정 — 기존 호출부(테스트·단계 ① 경로) 호환
    public DcaConfig(OrderDirection direction, BigDecimal amountUsd, OrderType orderType, DcaFrequency frequency,
                     DayOfWeek dayOfWeek, Integer dayOfMonth) {
        this(direction, amountUsd, orderType, frequency, dayOfWeek, dayOfMonth, List.of());
    }

    // 해당 지표 조건 존재 여부 — planner가 실잔고 조회 여부 판단에 사용
    public boolean hasIndicator(DcaIndicator indicator) {
        return conditions.stream().anyMatch(c -> c.indicator() == indicator);
    }

    // MOO는 개장 배치(AT_OPEN), MOC는 마감 배치(AT_CLOSE)
    public OrderTiming timing() {
        return orderType == OrderType.MOO ? OrderTiming.AT_OPEN : OrderTiming.AT_CLOSE;
    }

    // 단일 concrete leg — 슬롯 중복 방지(TradingOrderSlots.excludeExisting)의 키
    public String orderLeg() {
        return "DCA_" + direction.name();
    }
}
