package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// 전략 패턴 진입점 — TradingService/TradingPreviewService/CycleRotationService 의 switch(strategy.type()) 분기를 다형성으로 대체
// 각 구현체는 cycleType()으로 자기 타입을 선언하며, 서비스는 Map<Strategy.Type, CycleOrderStrategy>로 주입받아 사용
public interface CycleOrderStrategy {

    // 이 전략이 담당하는 사이클 타입
    Strategy.Type cycleType();

    // 미리보기 실행 전 전일종가 조회 필요 여부 (INFINITE만 true — 0회차 평단가 대용)
    default boolean requiresPrevClose() { return false; }

    // 미리보기 실행 전 기준매매표 조회 필요 여부 (PRIVACY만 true)
    default boolean requiresPrivacyBase() { return false; }

    // 리버스모드(소진 후 모드) 지원 여부 (INFINITE만 true) — UI 배지/표시 가드
    default boolean supportsReverseMode() { return false; }

    // 지원하는 분할 수 옵션 — 빈 목록이면 분할 개념 없음 (PRIVACY). UI 분할 선택지·divisionCount 전송 여부 결정
    default List<Integer> availableDivisionCounts() { return List.of(); }

    // 주문 계획 — Optional.empty()는 "전략 차원에서 skip" (예: PRIVACY 기준매매표 미수신)
    // INFINITE: position non-null / PRIVACY: position null
    Optional<OrderPlan> plan(PlanContext ctx);

    // 사이클 재등록 최소금액 — null이면 가드 미적용
    BigDecimal minRequiredDeposit(BigDecimal price, PrivacyTradeBase privacyBase, int divisionCount);

    // 전략 계산 입력 — execute/preview 공통
    // 공통 4필드 + 전략 전용 입력 묶음(infinite/privacy)으로 그룹핑 — 각 구현체는 자기 묶음만 소비
    // label: 로그 식별자 (계좌 닉네임 또는 "preview:<accountId>")
    record PlanContext(
            AccountBalance balance,
            Strategy strategy,
            LocalDate tradeDate,
            String label,
            InfiniteInputs infinite,  // INFINITE 전용 입력 (PRIVACY는 무시)
            PrivacyInputs privacy     // PRIVACY 전용 입력 (INFINITE은 무시)
    ) {

        // INFINITE 전략 전용 입력 묶음
        // divisionCount: 전략 버전 상세값 (없으면 기본 분할 수)
        // prevClosePrice: 전일종가 (0회차 진입 방향 판단용)
        // starPointPrice: 리버스모드 별지점 (직전 5거래일 종가 평균, 리버스모드 2일차+에서만 non-null)
        // isReverseMode: 오늘의 리버스모드 여부 (cycle_position 최신 행에서 판단)
        // isFirstReverseDay: 리버스모드 진입 첫날 여부 (직전 행이 일반모드였음)
        public record InfiniteInputs(
                Integer divisionCount,
                BigDecimal prevClosePrice,
                BigDecimal starPointPrice,
                boolean isReverseMode,
                boolean isFirstReverseDay
        ) {}

        // PRIVACY 전략 전용 입력 묶음
        // initialUsdDeposit: 현재 StrategyCycle의 시작 시드 (buildOrders 호출 시 필요)
        // privacyBase: 당일 기준매매표 (미수신 시 null → 전략 차원 skip)
        // currentPrice: 스케쥴러 시작 시점 현재가 (allocateRemainingBudget 분모 산출용 — preview/수동실행 시 null)
        public record PrivacyInputs(
                BigDecimal initialUsdDeposit,
                PrivacyTradeBase privacyBase,
                BigDecimal currentPrice
        ) {}
    }

    // 전략 계산 결과 — position은 INFINITE만 non-null (preview의 INSUFFICIENT_BALANCE 케이스에서도 보존)
    record OrderPlan(InfinitePosition position, List<Order> orders) {}
}
