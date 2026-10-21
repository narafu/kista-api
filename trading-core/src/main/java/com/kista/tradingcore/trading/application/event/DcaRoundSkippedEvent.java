package com.kista.tradingcore.trading.application.event;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;

import java.time.LocalDate;
import java.util.UUID;

// DCA 규칙 회차 미주문 — tradingcore.notify가 사용자 알림. dca_execution 신규 삽입 시에만 발행(EPR 재발행 대상)
public record DcaRoundSkippedEvent(
        UUID userId,             // 알림 수신자
        UUID strategyId,         // 대상 전략
        String accountNickname,  // 알림 표기
        StrategyTicker ticker,   // 종목
        String ruleKey,          // 미주문 규칙 키
        Integer ruleNumber,      // 규칙 순번(1부터, 활성 규칙 입력 순서) — 삭제된 규칙이면 null
        OrderDirection direction, // 규칙 방향 — 삭제된 규칙이면 null
        LocalDate tradeDate,     // KST 거래일
        DcaSkipReason reason,    // 사유
        String detail            // 사유 상세
) {}
