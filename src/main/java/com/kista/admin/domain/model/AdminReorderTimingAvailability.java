package com.kista.admin.domain.model;

// trading.domain.model.DstInfo.ReorderTimingAvailability own-type — admin은 이 3개 boolean만
// 쓰므로 DstInfo 자체를 admin이 들고 있을 필요가 없다(constraints.md "own-type 정당화 게이트" (a))
public record AdminReorderTimingAvailability(
        boolean atOpen,     // AT_OPEN 접수 가능 — 개장 전에만
        boolean atClose,    // AT_CLOSE 접수 가능 — 마감 전에만
        boolean immediate   // 즉시 접수 가능 — 정규장 중에만
) {
}
