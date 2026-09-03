package com.kista.privacy.domain.model;

// FIDA 기준 매매표 주문 유형 — trading.domain.model.Order.OrderType 값 집합만 동일한 privacy 자체 소유 타입.
// 모듈 경계상 privacy가 trading 타입을 참조할 수 없어 복제(broker의 Direction/OrderType 복제와 동일 패턴).
// 상수명은 Order.OrderType과 반드시 byte-identical — privacy_trade_base_orders.order_type이 이 이름을
// @Enumerated(STRING)으로 저장하고, PrivacyStrategy가 valueOf(name())으로 trading 타입에 매핑한다.
public enum PrivacyOrderType {
    LOC,   // Limit On Close
    MOC,   // Market On Close
    LIMIT  // 일반 지정가
}
