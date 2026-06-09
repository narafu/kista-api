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

    // 주문 계획 — Optional.empty()는 "전략 차원에서 skip" (예: PRIVACY 기준매매표 미수신)
    // INFINITE: position non-null / PRIVACY: position null
    Optional<OrderPlan> plan(PlanContext ctx);

    // 사이클 재등록 최소금액 — null이면 가드 미적용
    BigDecimal minRequiredDeposit(BigDecimal price, PrivacyTradeBase privacyBase);

    // 전략 계산 입력 — execute/preview 공통
    // label: 로그 식별자 (계좌 닉네임 또는 "preview:<accountId>")
    // initialUsdDeposit: 현재 StrategyCycle의 시작 시드 (PRIVACY에서 buildOrders 호출 시 필요)
    record PlanContext(
            AccountBalance balance,
            Strategy strategy,
            BigDecimal initialUsdDeposit,  // 현재 StrategyCycle.initialUsdDeposit (PRIVACY 전략 입력)
            BigDecimal prevClosePrice,     // 전일종가 (INFINITE 0회차 진입 방향 판단용, PRIVACY는 null)
            LocalDate tradeDate,
            PrivacyTradeBase privacyBase,  // INFINITE은 null 허용
            String label
    ) {}

    // 전략 계산 결과 — position은 INFINITE만 non-null (preview의 INSUFFICIENT_BALANCE 케이스에서도 보존)
    record OrderPlan(InfinitePosition position, List<Order> orders) {}
}
