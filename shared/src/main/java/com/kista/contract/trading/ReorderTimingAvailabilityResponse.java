package com.kista.contract.trading;

// 재주문 시점 가용성 — 주문시점 셀렉터 활성화 판단 SSOT
public record ReorderTimingAvailabilityResponse(
        boolean atOpen,     // AT_OPEN 접수 가능 — 개장 전에만
        boolean atClose,    // AT_CLOSE 접수 가능 — 마감 전에만
        boolean immediate   // 즉시 접수 가능 — 정규장 중에만
) {}
