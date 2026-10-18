package com.kista.tradingcore.account.application.event;

import com.kista.shared.kernel.Broker;

import java.time.LocalDate;
import java.util.UUID;

// 거래소 API 키 만료 임박(30·7·0일 전) — 일일 점검 스케쥴러가 발행
public record ApiKeyExpiringEvent(
        UUID userId,            // 알림 수신자
        UUID accountId,         // 대상 계좌
        String accountNickname, // 알림 표기
        Broker broker,          // 증권사(거래소)
        LocalDate expiresOn,    // 만료일 (KST)
        int daysLeft            // 남은 일수 (0 = 오늘 만료)
) {}
