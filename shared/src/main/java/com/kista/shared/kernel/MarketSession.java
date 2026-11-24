package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 미국 시장 주문 접수 세션 — market·marketcalendar·trading(DstInfo)이 공유하는 어휘
@Getter
@RequiredArgsConstructor
public enum MarketSession {
    DIRECT("주문 가능"),  // 프리마켓+정규장: 주문 가능 (DST: 17:00~05:00, 비DST: 18:00~06:00)
    BLOCKED("주문 불가");  // 장마감 후~프리마켓 전: 주문 불가 (DST: 05:00~17:00, 비DST: 06:00~18:00)

    private final String label; // 한국어 표시 이름 (메타 번들)
}
