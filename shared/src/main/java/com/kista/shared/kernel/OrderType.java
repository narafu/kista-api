package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 주문 유형 — broker/privacy/matching이 공유하는 전역 어휘(2026-09-09 own-type 원장 재판정으로 승격)
// 상수 순서 유지 — kista-ui 필터 선택지가 메타 orderTypes 순서를 따른다
@Getter
@RequiredArgsConstructor
public enum OrderType {
    LOC("LOC(종가지정가)"),     // Limit On Close: 종가 지정가 주문
    MOC("MOC(종가시장가)"),     // Market On Close: 종가 시장가 주문
    LIMIT("LIMIT(지정가)"),     // 일반 지정가 주문
    MOO("MOO(시가시장가)"),     // Market On Open: 장개시 시장가 — 매수는 브로커 어댑터가 캡 지정가(KIS LOO)로 변환
    MARKET("MARKET(시장가)");   // 즉시 시장가 — 코인 거래소 전용(업비트 매수 ord_type=price, 매도 ord_type=market). 주식 어댑터는 거부

    private final String label; // 한국어 표시 이름 (메타 orderTypes)
}
