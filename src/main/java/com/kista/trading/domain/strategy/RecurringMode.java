package com.kista.trading.domain.strategy;

// 전략 등록 시 VR 정기 입출금 방향 — com.kista.admin.domain.model.RecurringMode의
// trading 자체 복제본. 상수명 byte-identical이라 매핑은 valueOf(name())으로 충분.
// broker Direction/OrderType·privacy PrivacyOrderType와 동일한 모듈 경계 own-type 패턴
// (constraints.md "모듈 경계 포트 시그니처 — 각 모듈은 자기 소유 타입만 사용").
public enum RecurringMode {
    DEPOSIT, // 정기 적립
    HOLD, // 정기 입출금 없음
    WITHDRAW // 정기 인출
}
