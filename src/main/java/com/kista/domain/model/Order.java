package com.kista.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Order(
        LocalDate tradeDate,      // 거래일
        String symbol,            // 종목 코드 (예: SOXL)
        OrderType orderType,      // 주문 유형 (LOC/MOC/LIMIT)
        OrderDirection direction, // 매수/매도 방향
        int qty,                  // 주문 수량
        BigDecimal price,         // 주문 가격 (LOC/MOC는 참고용, 실제 체결가 아님)
        OrderStatus status,       // 주문 상태
        String kisOrderId         // KIS 시스템 부여 주문 번호 (ODNO)
) {
    public enum OrderType {
        LOC,   // Limit On Close: 종가 지정가 주문
        MOC,   // Market On Close: 종가 시장가 주문
        LIMIT  // 일반 지정가 주문/
    }
    public enum OrderDirection { BUY, SELL }
    public enum OrderStatus {
        PLACED,  // 주문 접수 완료 (체결 대기)
        FILLED,  // 체결 완료
        FAILED   // 주문 실패
    }
}
