package com.kista.tradingcore.trading.application.event;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;

import java.time.LocalDate;
import java.util.UUID;

// DCA 회차 미주문 — tradingcore.notify가 사용자 알림. dca_execution 신규 삽입 시에만 발행(EPR 재발행 대상)
public record DcaRoundSkippedEvent(
        UUID userId,             // 알림 수신자
        UUID strategyId,         // 대상 전략
        String accountNickname,  // 알림 표기
        StrategyTicker ticker,   // 종목
        OrderDirection direction, // 매수/매도 DCA
        LocalDate tradeDate,     // KST 거래일
        DcaSkipReason reason,    // 사유
        String detail            // 사유 상세
) {}
