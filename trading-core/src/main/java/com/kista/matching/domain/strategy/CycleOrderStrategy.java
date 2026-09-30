package com.kista.matching.domain.strategy;

import com.kista.sharedkernel.OrderTiming;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.matching.domain.model.PrivacyPlan;
import com.kista.matching.domain.model.AccountBalance;
import com.kista.matching.domain.model.InfinitePosition;
import com.kista.matching.domain.model.VrPosition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.kista.sharedkernel.StrategyCapability;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

// 전략 패턴 진입점 — TradingService/TradingPreviewService/CycleRotationService 의 switch(strategy.type()) 분기를 다형성으로 대체
// 각 구현체는 cycleType()으로 자기 타입을 선언하며, 서비스는 Map<StrategyType, CycleOrderStrategy>로 주입받아 사용
public interface CycleOrderStrategy {

    // 이 전략이 담당하는 사이클 타입
    StrategyType cycleType();

    // 정적 capability 상수 — SSOT는 StrategyType.capability()(sharedkernel), 구현체는 재정의하지 않는다
    default StrategyCapability capability() { return cycleType().capability(); }

    // 미리보기 실행 전 전일종가 조회 필요 여부 (INFINITE만 true — 0회차 평단가 대용)
    default boolean requiresPrevClose() { return false; }

    // 미리보기 실행 전 기준매매표 조회 필요 여부 (PRIVACY만 true)
    default boolean requiresPrivacyBase() { return capability().requiresPrivacyBase(); }

    // 리버스모드(소진 후 모드) 지원 여부 (INFINITE만 true) — UI 배지/표시 가드
    default boolean supportsReverseMode() { return capability().supportsReverseMode(); }

    // 지원하는 분할 수 옵션 — 빈 목록이면 분할 개념 없음 (PRIVACY). UI 분할 선택지·divisionCount 전송 여부 결정
    default List<Integer> availableDivisionCounts() { return capability().divisionCounts(); }

    // 주문 계획 — Optional.empty()는 "전략 차원에서 skip" (예: PRIVACY 기준매매표 미수신)
    // INFINITE: position non-null / PRIVACY: position null
    Optional<OrderPlan> plan(PlanContext ctx);

    // 사이클 재등록 최소금액 — null이면 가드 미적용
    BigDecimal minRequiredDeposit(BigDecimal price, PrivacyPlan privacyPlan, int divisionCount);

    // holdings=0(전량 청산) 시 사이클 종료 여부 — VR만 false(사이클 유지), 나머지 true(종료)
    default boolean endsCycleOnLiquidation() { return true; }

    // 리버스모드(소진 후 모드) 상태를 cycle_position_infinite_detail에 저장할지 여부 (INFINITE만 true)
    default boolean tracksReverseMode() { return false; }

    // 포지션 저장 후 N주 롤오버 판정을 수행할지 여부 (VR만 true)
    default boolean requiresRolloverCheck() { return false; }

    // 캡 초과 BUY 재산정 — INFINITE/VR은 사다리 전체 재구성(개수·구성이 원본과 달라질 수 있음),
    // PRIVACY는 개별 주문 가격만 치환(개수·순서 불변). 캡 미적용 대상(position/vrPosition 없음, 부트스트랩 등)이면
    // 입력을 그대로(참조 동일) 반환한다 — 호출부가 이 "변경 없음"을 감지해 취소·재저장을 건너뛴다.
    default List<PlannedOrder> capBuyOrders(List<PlannedOrder> buyOrders, BigDecimal cap,
                                             InfinitePosition position, VrPosition vrPosition,
                                             StrategyTicker ticker, LocalDate tradeDate) {
        return buyOrders;
    }

    // true: capBuyOrders 결과를 buyOrders와 인덱스별로 비교해 값이 바뀐 주문만 개별 취소·재저장(PRIVACY)
    // false: buyOrders 전체를 한 번에 취소하고 결과 전체를 재저장(INFINITE/VR — 사다리 재구성은 개별 대응이 무의미)
    default boolean capsIndividualOrders() { return false; }

    // 가격 캡 로직을 시도하기 전에 필요한 입력(position/vrPosition)이 갖춰졌는지 여부 —
    // false면 호출부가 DB 조회·트랜잭션을 열지 않고 즉시 스킵한다(스케쥴러 매 틱 빈 트랜잭션 방지)
    default boolean needsCapCheck(InfinitePosition position, VrPosition vrPosition) { return true; }

    // 계좌별 주문 예산 배정 우선순위 — 값이 작을수록 먼저 승인
    default int allocationPriority() { return 100; }

    // 기존 주문만으로 오늘 생성 가능한 주문 슬롯이 모두 점유됐는지 여부
    // existingOrders: 호출부(TradingService)가 영속 Order를 Order::toPlanned로 강등해 전달한다
    default boolean canSkipOrderComputation(List<PlannedOrder> existingOrders, Set<OrderTiming> creatableTimings) {
        return false;
    }

    // 전략 계산 입력 — execute/preview 공통
    // 공통 4필드 + 전략 전용 입력 묶음(infinite/privacy/vr)으로 그룹핑 — 각 구현체는 자기 묶음만 소비
    // label: 로그 식별자 (계좌 닉네임 또는 "preview:<accountId>")
    record PlanContext(
            AccountBalance balance,
            StrategyTicker ticker,  // 거래 종목 — Strategy 전체가 아닌 커널이 실제로 쓰는 값만 주입(matching↔trading 순환 방지)
            LocalDate tradeDate,
            String label,
            InfiniteInputs infinite,  // INFINITE 전용 입력 (PRIVACY·VR은 무시)
            PrivacyInputs privacy,    // PRIVACY 전용 입력 (INFINITE·VR은 무시)
            VrInputs vr               // VR 전용 입력 (INFINITE·PRIVACY는 무시)
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
        // privacyPlan: 당일 기준매매표(커널 입력 변환본) (미수신 시 null → 전략 차원 skip)
        // currentPrice: 스케쥴러 시작 시점 현재가 (allocateRemainingBudget 분모 산출용 — preview/수동실행 시 null)
        public record PrivacyInputs(
                BigDecimal initialUsdDeposit,
                PrivacyPlan privacyPlan,
                BigDecimal currentPrice
        ) {}

        // VR 전략 전용 입력 묶음
        // value: 현재 V값 (사이클 시작 시 StrategyCycleVrDetail.value 스냅샷에서 로드)
        // bandWidth: 밴드 폭 % (StrategyVrDetail.bandWidth)
        // poolLimit: 개장 USD pool×StrategyCycleVrDetail.poolLimitRate로 파생한 pool 상한 금액
        // poolUsed: 이번 주기에 이미 사용한 pool 누적 금액
        // referencePrice: BUY bootstrap(V=0,pool>0)·일반 사다리 캡 판정 공용 기준가 — currentPrice 없으면 전일종가로 대체(fallback 허용)
        // currentPrice: 스케쥴러 시작 시점 실시간 현재가
        // recurringAmount: nextValue() 공식(롤오버 V′ 계산)용
        public record VrInputs(
                BigDecimal value,
                BigDecimal bandWidth,
                BigDecimal poolLimit,
                BigDecimal poolUsed,
                BigDecimal referencePrice,
                BigDecimal currentPrice,
                int recurringAmount
        ) {}
    }

    // 전략 계산 결과 — position은 INFINITE만 non-null, vrPosition은 VR만 non-null
    // (preview의 INSUFFICIENT_BALANCE 케이스에서도 보존 — BuyOrderPriceCapper 접수 전 보정에 재사용)
    record OrderPlan(InfinitePosition position, VrPosition vrPosition, List<PlannedOrder> orders) {}
}
