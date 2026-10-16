package com.kista.tradingcore.trading.domain.model;

import java.time.Instant;

// 접수 진행 중(SUBMITTING) 주문 + 선점 시각(orders.updated_at) — 잔류 판정·Toss 멱등 재요청 창 계산용
public record SubmittingOrder(Order order, Instant submittedAt) {}
