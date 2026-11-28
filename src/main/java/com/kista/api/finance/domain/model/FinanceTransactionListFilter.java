package com.kista.api.finance.domain.model;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.UUID;

// 거래 목록 조회 필터 — 모두 선택
public record FinanceTransactionListFilter(
        @Nullable LocalDate from, // 시작일
        @Nullable LocalDate to, // 종료일
        FinanceCategory.@Nullable Type type, // 카테고리 유형
        @Nullable UUID categoryId, // 하위 카테고리 포함
        @Nullable String memo,     // 대소문자 무시 부분 일치
        @Nullable UUID filterUserId // 작성자
) {}
