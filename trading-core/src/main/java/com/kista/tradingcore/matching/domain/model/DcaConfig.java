package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.Objects;

// DCA 전략 버전 설정 — strategy_dca_version 1행. 조건(conditions)은 단계 ②에서 추가
public record DcaConfig(
        OrderDirection direction, // 매수/매도 단방향
        BigDecimal amountUsd,     // 회차당 고정 금액 (USD, > 0)
        OrderType orderType,      // MOO(개장 배치) 또는 MOC(마감 배치)
        DcaFrequency frequency,   // 실행 주기
        DayOfWeek dayOfWeek,      // WEEKLY 전용 (월~금)
        Integer dayOfMonth        // MONTHLY 전용 1~31, null이면 말일
) {
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
