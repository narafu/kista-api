package com.kista.broker.domain.model;

import java.math.BigDecimal;

// MockBrokerAdapter 체결 시뮬레이션 전용 뷰 — trading 소유 Order 필드 중 실제로 읽는 것만 담는다
// (MockSimulationDataPort.findPlacedOrders 반환 타입 — trading 쪽 MockSimulationDataAdapter가 매핑해 생성)
public record PlacedOrderView(
        Direction direction,       // 매수/매도 방향
        OrderType orderType,       // LOC/MOC/LIMIT — fills() 판정 및 체결가 결정에 사용
        Integer quantity,          // 주문 수량
        BigDecimal price,          // 주문 가격 (LOC/LIMIT 지정가)
        String externalOrderId     // 증권사 부여 주문번호 (모의계좌는 MOCK- 접두사 합성값)
) {}
