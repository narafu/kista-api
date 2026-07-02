package com.kista.adapter.in.web.dto;

import java.util.List;

// 이상 징후 응답 DTO — 일시정지·비활성 계좌 목록
public record AnomaliesResponse(
        List<AdminAccountItem> pausedAccounts,
        List<AdminAccountItem> inactiveAccounts
) {}
