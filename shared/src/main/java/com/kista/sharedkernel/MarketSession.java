package com.kista.sharedkernel;

// 미국 시장 주문 접수 세션 — market·marketcalendar·trading(DstInfo)이 공유하는 어휘
public enum MarketSession {
    DIRECT,  // 프리마켓+정규장: 주문 가능 (DST: 17:00~05:00, 비DST: 18:00~06:00)
    BLOCKED  // 장마감 후~프리마켓 전: 주문 불가 (DST: 05:00~17:00, 비DST: 06:00~18:00)
}
