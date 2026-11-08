package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.OrderDirection;

import java.time.LocalDate;

// DCA 회차 이력 조회 필터 — 모든 필드 선택(null=조건 없음), AND 결합
public record DcaExecutionFilter(
        LocalDate from,           // 거래일 시작(포함)
        LocalDate to,             // 거래일 끝(포함)
        OrderDirection direction, // 기록 시점 방향
        String ruleKey            // 규칙 키 (삭제된 규칙 이력 포함)
) {
    public DcaExecutionFilter {
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
        ruleKey = ruleKey == null || ruleKey.isBlank() ? null : ruleKey;
    }

    public static final DcaExecutionFilter NONE = new DcaExecutionFilter(null, null, null, null);
}
