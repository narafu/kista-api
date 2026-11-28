package com.kista.api.finance.domain.model;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

// 거래 keyset 페이지 조회 조건 — 포트 파라미터
public record FinanceTransactionPageQuery(
        UUID userId, // 요청 사용자
        @Nullable UUID groupId, // 현재 그룹
        @Nullable LocalDate from, // 시작일
        @Nullable LocalDate to, // 종료일
        @Nullable Set<UUID> categoryIds, // null=제한 없음
        @Nullable UUID filterUserId, // 작성자 필터
        @Nullable String memo, // 메모 부분 일치어
        FinanceTransactionSort sort, // 정렬 기준
        @Nullable FinanceTransactionCursor cursor, // 이전 페이지 마지막 행 커서
        int limit // 조회 건수(hasMore용 +1 포함)
) {}
