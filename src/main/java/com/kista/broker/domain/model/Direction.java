package com.kista.broker.domain.model;

// 매수/매도 방향 — trading.Order.OrderDirection과 별개(모듈 경계상 공유 불가), 값 집합만 동일
public enum Direction {
    BUY,
    SELL
}
