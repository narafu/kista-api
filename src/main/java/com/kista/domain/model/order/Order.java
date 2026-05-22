package com.kista.domain.model.order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record Order(
        UUID id,                   // PK (null = 신규 PLANNED)
        UUID accountId,            // FK → accounts.id
        LocalDate tradeDate,       // 거래일
        Ticker ticker,             // 거래 종목
        OrderType orderType,       // 주문 유형 (LOC/MOC/LIMIT)
        OrderDirection direction,  // 매수/매도 방향
        Integer quantity,          // 주문 수량(nullable)
        BigDecimal price,          // 주문 가격 (LOC/MOC는 참고용, 실제 체결가 아님)
        OrderStatus status,        // 주문 상태
        String kisOrderId          // KIS 시스템 부여 주문 번호 (ODNO), PLANNED 상태에서는 null
) {
    public enum OrderType {
        LOC,   // Limit On Close: 종가 지정가 주문
        MOC,   // Market On Close: 종가 시장가 주문
        LIMIT  // 일반 지정가 주문
    }
    public enum OrderDirection { BUY, SELL }
    public enum OrderStatus {
        PLANNED,  // DB 저장, KIS 접수 대기
        PLACED,   // KIS 접수 완료
        FILLED,   // 체결 완료
        FAILED    // 실패
    }

    // 전략 계산 결과(template)를 특정 계좌의 PLANNED 주문으로 변환
    public static Order plan(Order template, UUID accountId) {
        return new Order(null, accountId, template.tradeDate(), template.ticker(),
                template.orderType(), template.direction(), template.quantity(),
                template.price(), OrderStatus.PLANNED, null);
    }
}
