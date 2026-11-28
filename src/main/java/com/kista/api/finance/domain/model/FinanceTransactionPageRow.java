package com.kista.api.finance.domain.model;

import org.jspecify.annotations.Nullable;

// 페이지 조회 행 — 카테고리 정렬 커서에 쓸 카테고리 이름을 함께 싣는다
public record FinanceTransactionPageRow(
        FinanceTransaction transaction, // 거래
        @Nullable String categoryName   // 카테고리 이름(카테고리 정렬 커서용)
) {}
