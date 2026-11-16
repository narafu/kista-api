package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Objects;

// DCA 규칙 1개 — 전략당 1~10개. ruleKey는 저장 전 신규 규칙이면 null
public record DcaRule(
        String ruleKey,              // 규칙 식별자(UUID 문자열) — 주문 leg 키
        OrderDirection direction,    // 매수/매도
        DcaFrequency frequency,      // 실행 주기
        DayOfWeek dayOfWeek,         // WEEKLY 전용
        Integer dayOfMonth,          // MONTHLY 전용 1~31, null이면 말일
        OrderType orderType,         // MOO·MOC
        DcaSizing sizing,            // 규모
        DcaShortfallPolicy shortfall, // 가용 부족 정책
        List<DcaCondition> conditions // 추가 조건 (AND, 최대 10개)
) {
    public static final int MAX_CONDITIONS = 10; // 규칙당 조건 상한

    public DcaRule {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(sizing, "sizing");
        Objects.requireNonNull(shortfall, "shortfall");
        if (sizing.type() == DcaSizingType.HOLDING_PERCENT && direction != OrderDirection.SELL) {
            throw new IllegalArgumentException("보유 비율 규모는 매도 규칙에서만 쓸 수 있습니다.");
        }
        if (orderType != OrderType.MOO && orderType != OrderType.MOC) {
            throw new IllegalArgumentException("DCA 주문유형은 장 시작 시장가(MOO) 또는 장 마감 시장가(MOC)만 허용합니다.");
        }
        boolean weekly = frequency == DcaFrequency.WEEKLY;
        boolean monthly = frequency == DcaFrequency.MONTHLY;
        if (weekly != (dayOfWeek != null)) throw new IllegalArgumentException("요일은 매주 반복에서만 필수입니다.");
        if (weekly && (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY)) throw new IllegalArgumentException("주말 요일은 지정할 수 없습니다.");
        if (!monthly && dayOfMonth != null) throw new IllegalArgumentException("일자는 매월 반복에서만 지정할 수 있습니다.");
        if (dayOfMonth != null && (dayOfMonth < 1 || dayOfMonth > 31)) throw new IllegalArgumentException("일자는 1~31일이어야 합니다.");
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        if (conditions.size() > MAX_CONDITIONS) throw new IllegalArgumentException("DCA 조건은 최대 " + MAX_CONDITIONS + "개까지 지정할 수 있습니다.");
    }

    // 해당 지표 조건 존재 여부
    public boolean hasIndicator(DcaIndicator indicator) {
        return conditions.stream().anyMatch(c -> c.indicator() == indicator);
    }

    // MOO는 개장 배치(AT_OPEN), MOC는 마감 배치(AT_CLOSE)
    public OrderTiming timing() {
        return orderType == OrderType.MOO ? OrderTiming.AT_OPEN : OrderTiming.AT_CLOSE;
    }

    private static final String DCA_LEG_PREFIX = "DCA_"; // DCA 주문 leg = "DCA_" + ruleKey

    // 규칙별 concrete leg — 슬롯 중복 방지 키
    public String orderLeg() {
        return DCA_LEG_PREFIX + ruleKey;
    }

    // DCA 주문 leg 여부 — 비DCA leg(전략 주문·UNKNOWN)는 false
    public static boolean isDcaLeg(String orderLeg) {
        return orderLeg.startsWith(DCA_LEG_PREFIX);
    }

    // DCA 주문 leg → ruleKey (orderLeg()의 역변환)
    public static String ruleKeyOf(String orderLeg) {
        return orderLeg.substring(DCA_LEG_PREFIX.length());
    }

    // 규모만 바꾼 사본 — 응답 조립용(금액 반올림)
    public DcaRule withSizing(DcaSizing newSizing) {
        return new DcaRule(ruleKey, direction, frequency, dayOfWeek, dayOfMonth, orderType, newSizing, shortfall, conditions);
    }

    public DcaRule withRuleKey(String key) {
        return new DcaRule(key, direction, frequency, dayOfWeek, dayOfMonth, orderType, sizing, shortfall, conditions);
    }
}
