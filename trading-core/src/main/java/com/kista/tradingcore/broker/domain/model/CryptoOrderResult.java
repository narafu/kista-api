package com.kista.tradingcore.broker.domain.model;

import java.math.BigDecimal;

// 코인 주문 접수·조회 결과 — 벤더 중립(업비트 state 4종을 그대로 대응)
public record CryptoOrderResult(
        String uuid,                // 거래소 주문 ID
        String identifier,          // 우리가 지정한 멱등 식별자 (주문 UUID)
        State state,                // 주문 상태
        BigDecimal executedVolume,  // 체결 수량 (0이면 미체결)
        BigDecimal avgPrice,        // 체결 평균가 (KRW, 체결 없으면 null)
        BigDecimal paidFee          // 지불 수수료 (KRW)
) {
    public enum State {
        WAIT,   // 체결 대기
        WATCH,  // 예약 주문 대기
        DONE,   // 체결 완료
        CANCEL  // 취소 — 시장가 매수는 잔액 반환으로 체결 후에도 CANCEL일 수 있음(미확인, executedVolume으로 판정)
    }
}
