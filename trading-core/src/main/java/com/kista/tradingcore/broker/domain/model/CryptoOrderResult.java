package com.kista.tradingcore.broker.domain.model;

import java.math.BigDecimal;

// 코인 주문 접수·조회 결과 — 벤더 중립. 체결 판정은 상태가 아니라 executedVolume > 0
public record CryptoOrderResult(
        String exchangeOrderId,     // 거래소 주문 ID
        String clientOrderId,       // 우리가 지정한 멱등 식별자 (주문 UUID)
        State state,                // 주문 상태
        BigDecimal executedVolume,  // 체결 수량 (0이면 미체결)
        BigDecimal netExecutedVolume, // 원장 반영 순 체결 수량 — 받은 자산에서 수수료가 빠지면 차감 후, 업비트는 executedVolume과 동일
        BigDecimal avgPrice,        // 체결 평균가 (견적 통화, 체결 없으면 null)
        BigDecimal paidFee,         // 지불 수수료 (feeAsset 단위)
        String feeAsset             // 수수료 자산 코드 (바이낸스 BNB·BTC·USDT 등, 업비트 "KRW" — Currency로 표현 불가라 String)
) {
    public enum State {
        WAIT,   // 열린 주문(체결 대기·부분 체결) — 바이낸스 NEW/PARTIALLY_FILLED/PENDING_NEW
        WATCH,  // 예약 대기
        DONE,   // 체결 완료 — 바이낸스 FILLED
        CANCEL  // 체결 없이 또는 일부 체결 후 종료(취소·만료·거부) — 바이낸스 CANCELED/EXPIRED/EXPIRED_IN_MATCH/REJECTED
    }
}
