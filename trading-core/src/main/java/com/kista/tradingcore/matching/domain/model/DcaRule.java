package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;

import java.math.BigDecimal;
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
        OrderType orderType,         // 주식 MOO·MOC, 코인 MARKET
        Integer executionHour,       // 코인 실행 시각(KST 0~23) — MARKET에서만 필수
        DcaSizing sizing,            // 규모
        DcaShortfallPolicy shortfall, // 가용 부족 정책
        List<DcaCondition> conditions // 추가 조건 (AND, 최대 10개)
) {
    public static final int MAX_CONDITIONS = 10; // 규칙당 조건 상한
    public static final BigDecimal CRYPTO_MIN_AMOUNT = new BigDecimal("5000"); // 업비트 KRW 마켓 최소 주문 금액
    // ponytail: 업비트 KRW 수수료율 고정 — 거래소별 수수료 조회가 필요해지면 확장
    public static final BigDecimal CRYPTO_FEE_FACTOR = new BigDecimal("1.0005"); // 1 + 수수료율 0.05%

    public DcaRule {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(sizing, "sizing");
        Objects.requireNonNull(shortfall, "shortfall");
        if (sizing.type() == DcaSizingType.HOLDING_PERCENT && direction != OrderDirection.SELL) {
            throw new IllegalArgumentException("보유 비율 규모는 매도 규칙에서만 쓸 수 있습니다");
        }
        if (orderType != OrderType.MOO && orderType != OrderType.MOC && orderType != OrderType.MARKET) {
            throw new IllegalArgumentException("DCA 주문유형은 MOO·MOC·MARKET만 허용합니다");
        }
        boolean market = orderType == OrderType.MARKET;
        if (market != (executionHour != null)) throw new IllegalArgumentException("executionHour는 MARKET(코인) 주문에서만 필수입니다");
        if (executionHour != null && (executionHour < 0 || executionHour > 23)) throw new IllegalArgumentException("executionHour는 0~23입니다");
        boolean weekly = frequency == DcaFrequency.WEEKLY;
        boolean monthly = frequency == DcaFrequency.MONTHLY;
        if (weekly != (dayOfWeek != null)) throw new IllegalArgumentException("dayOfWeek는 WEEKLY에서만 필수입니다");
        // 코인(24/7)만 주말 요일 허용
        if (weekly && !market && (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY)) throw new IllegalArgumentException("주말 요일은 지정할 수 없습니다");
        if (!monthly && dayOfMonth != null) throw new IllegalArgumentException("dayOfMonth는 MONTHLY에서만 허용합니다");
        if (dayOfMonth != null && (dayOfMonth < 1 || dayOfMonth > 31)) throw new IllegalArgumentException("dayOfMonth는 1~31입니다");
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        if (conditions.size() > MAX_CONDITIONS) throw new IllegalArgumentException("DCA 조건은 최대 " + MAX_CONDITIONS + "개입니다.");
    }

    // 계좌 시장별 규칙 — 등록·수정 시 계좌 증권사 시장으로 호출한다
    public void requireCompatible(Market accountMarket) {
        if (accountMarket == Market.CRYPTO_KRW) {
            if (orderType != OrderType.MARKET) throw new IllegalArgumentException("코인 DCA 주문유형은 MARKET만 허용합니다");
            if (sizing.type() == DcaSizingType.AMOUNT) {
                BigDecimal amount = sizing.amount();
                if (amount.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("코인 DCA 금액은 원 단위 정수여야 합니다");
                if (amount.compareTo(CRYPTO_MIN_AMOUNT) < 0) throw new IllegalArgumentException("코인 DCA 금액은 5,000원 이상이어야 합니다");
            }
        } else if (orderType == OrderType.MARKET) {
            throw new IllegalArgumentException("주식 DCA 주문유형은 MOO 또는 MOC만 허용합니다");
        }
    }

    // 해당 지표 조건 존재 여부
    public boolean hasIndicator(DcaIndicator indicator) {
        return conditions.stream().anyMatch(c -> c.indicator() == indicator);
    }

    // MOO는 개장 배치(AT_OPEN), MOC·MARKET은 마감 배치(AT_CLOSE)
    public OrderTiming timing() {
        return orderType == OrderType.MOO ? OrderTiming.AT_OPEN : OrderTiming.AT_CLOSE;
    }

    // 규칙별 concrete leg — 슬롯 중복 방지 키
    public String orderLeg() {
        return "DCA_" + ruleKey;
    }

    public DcaRule withRuleKey(String key) {
        return new DcaRule(key, direction, frequency, dayOfWeek, dayOfMonth, orderType, executionHour, sizing, shortfall, conditions);
    }
}
