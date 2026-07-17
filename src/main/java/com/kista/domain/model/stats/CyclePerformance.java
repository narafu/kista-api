package com.kista.domain.model.stats;

import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 사이클 1개의 성과 — 진행 중이면 endAmount=최신 스냅샷 평가액, closed=false
// 근사 한계: 수수료 미반영. VR 적립금(recurringAmount)은 V' 목표값 계산(VrPosition.nextValue)과
// 주문 사이징 입력으로만 쓰이고, 사이클 도중 usd_deposit/CyclePosition에 직접 가산되는 경로는
// 확인되지 않았다(Step 0 grep 결과) — 다만 향후 그런 경로가 추가되면 pnl이 과대평가될 수 있음
public record CyclePerformance(
        UUID cycleId,
        Strategy.Type strategyType,
        Strategy.Ticker ticker,
        LocalDate startDate,
        LocalDate endDate,          // 진행 중이면 null
        BigDecimal startAmount,
        BigDecimal endAmount,       // 진행 중 + 스냅샷 없으면 null
        BigDecimal pnl,             // endAmount 없으면 null
        BigDecimal returnRate,      // scale 4, endAmount 없으면 null
        Integer durationDays,       // 진행 중이면 오늘(KST) 기준
        boolean closed,
        Instant createdAt           // 커서 페이지네이션 키
) {}
