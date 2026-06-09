package com.kista.domain.model.order;

import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record Order(
        UUID id,                     // PK (null = 신규 PLANNED)
        UUID accountId,              // FK → accounts.id
        LocalDate tradeDate,         // 거래일
        Ticker ticker,               // 거래 종목
        OrderType orderType,         // 주문 유형 (LOC/MOC/LIMIT)
        OrderDirection direction,    // 매수/매도 방향
        Integer quantity,            // 주문 수량(nullable)
        BigDecimal price,            // 주문 가격 (LOC/MOC는 참고용, 실제 체결가 아님)
        OrderStatus status,          // 주문 상태
        String kisOrderId,           // KIS 시스템 부여 주문 번호 (ODNO), PLACED 이후 설정
        Integer filledQuantity,      // 체결 수량 (null=미확인, 0=미체결)
        BigDecimal filledPrice       // 체결 가중평균가 (null=미체결)
) {
    public enum OrderType {
        LOC,   // Limit On Close: 종가 지정가 주문
        MOC,   // Market On Close: 종가 시장가 주문
        LIMIT  // 일반 지정가 주문
    }

    public enum OrderDirection {
        BUY,
        SELL;

        // KIS SLL_TYPE 파라미터: 매도=00, 매수="" (빈 문자열)
        public String kisSllType() { return this == SELL ? "00" : ""; }
    }

    public enum OrderStatus {
        PLANNED,           // DB 저장, KIS 접수 대기
        PLACED,            // KIS 접수 완료
        FILLED,            // 전량 체결
        PARTIALLY_FILLED,  // 부분 체결 (filledQuantity < quantity)
        FAILED,            // 실패
        CANCELLED          // 사용자 취소 요청으로 KIS 취소 접수 완료
    }

    // 전략 계산 결과(template)를 특정 계좌의 PLANNED 주문으로 변환
    public static Order plan(Order template, UUID accountId) {
        return new Order(null, accountId, template.tradeDate(), template.ticker(),
                template.orderType(), template.direction(), template.quantity(),
                template.price(), OrderStatus.PLANNED, null, null, null);
    }
}
