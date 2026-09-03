package com.kista.trading.application.event;

import java.util.UUID;

// 사이클 단위 주문 취소 중 브로커 취소 실패 — 트랜잭션 커밋 후에만 발행됨 (관리자 알림용)
public record OrderCancelFailedEvent(UUID strategyId, int failedCount, String summary) {}
