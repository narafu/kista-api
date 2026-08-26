package com.kista.adapter.in.web.dto;

import java.util.List;

// 항목별 성공/실패를 구분 반환 — 한 항목 실패가 전체를 막지 않음
public record BulkFinanceRegisterResponse(
        int assetSuccessCount,
        int transactionSuccessCount,
        List<String> failures
) {}
