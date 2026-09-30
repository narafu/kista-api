package com.kista.matching.domain.model;

import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// PRIVACY 전략 커널 입력 — privacy 모듈의 기준 매매표에서 커널이 실제로 읽는 값만 담은 커널 소유 타입
// 변환은 데이터 소유자가 담당한다(PrivacyTradeBase.toPlan()) — matching은 privacy를 참조하지 않는다
public record PrivacyPlan(
        int holdings,                      // 기준표 목표 보유 수량
        BigDecimal currentCycleStart,      // 현재 사이클 기준가 — 배수(initialUsdDeposit ÷ currentCycleStart) 산출 기준
        List<PrivacyPlannedTrade> trades   // 기준표 계획 주문 목록
) {
    public PrivacyPlan {
        if (currentCycleStart == null || currentCycleStart.signum() <= 0) {
            throw new IllegalStateException("[PRIVACY] currentCycleStart 이상: " + currentCycleStart);
        }
    }

    // 기준표 계획 주문 1건
    public record PrivacyPlannedTrade(
            LocalDate tradeDate,       // 거래일
            StrategyTicker ticker,     // 거래 종목
            OrderType orderType,       // 주문 유형 (LOC/MOC/LIMIT)
            OrderDirection direction,  // 매수/매도 방향
            Integer quantity,          // 주문 수량(nullable — 수량 미확정 허용)
            BigDecimal price           // 주문 가격 (LOC/MOC는 참고용)
    ) {
    }
}
