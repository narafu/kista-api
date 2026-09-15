package com.kista.market.domain.model;

// marketcalendar.domain.model.MarketSessionSnapshot.MarketSession own-type 복제 — 2값 뿐이라
// enum 그대로 복제(DIRECT: 정규장 직접 접수, BLOCKED: 개장전/장마감 등 접수 차단). market↔marketcalendar
// 순환 방지 목적 own-type — broker.PriceSnapshot과 동일 패턴
public enum MarketSession {
    DIRECT,
    BLOCKED
}
