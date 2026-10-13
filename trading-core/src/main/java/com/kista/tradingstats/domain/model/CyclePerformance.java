package com.kista.tradingstats.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyTicker;

// 사이클 1개의 성과 — 진행 중이면 endAmount=최신 스냅샷 평가액, closed=false
// 근사 한계: 수수료 미반영. VR 적립금(recurringAmount)은 V' 목표값 계산과 주문 사이징 입력으로만 쓰이고
// 사이클 도중 usd_deposit에 가산되지 않는다는 전제 — 그런 경로가 생기면 pnl이 과대평가된다
public record CyclePerformance(
        UUID cycleId,
        UUID accountId,
        StrategyType strategyType,
        StrategyTicker ticker,
        LocalDate startDate,
        LocalDate endDate,          // 진행 중이면 null
        BigDecimal startAmount,
        BigDecimal endAmount,       // 진행 중 + 스냅샷 없으면 null
        BigDecimal pnl,             // endAmount 없으면 null
        BigDecimal returnRate,      // scale 4, endAmount 없으면 null
        Integer durationDays,       // 진행 중이면 오늘(KST) 기준, 시작 전이면 0
        boolean closed
) {}
