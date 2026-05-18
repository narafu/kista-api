package com.kista.domain.model;

public record AdminStats(
    long totalUsers,    // 전체 사용자 수
    long pendingCount,  // 승인 대기 수
    long activeCount,   // 승인된 수
    long rejectedCount, // 거절된 수
    long totalAccounts  // 전체 계좌 수
) {}
