package com.kista.tradingcore.account.domain.model;

import com.kista.shared.kernel.TimeZones;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

// 거래소 API 키 만료 판정 SSOT — 계좌 응답 apiKeyExpiry와 만료 알림 스케쥴러가 같이 쓴다. 날짜는 KST 기준
public record ApiKeyExpiry(
        Status status,       // 만료 상태
        @Nullable LocalDate expiresOn, // 만료일(KST) — 만료일 없으면 null
        @Nullable Integer daysLeft     // 오늘(KST)부터 만료일까지 남은 일수 (0 = 오늘 만료, 음수 = 지남) — 만료일 없으면 null
) {
    static final int EXPIRING_DAYS = 30; // 만료 임박 기준 — 만료 알림(D-30)과 같은 값

    @Getter
    @RequiredArgsConstructor
    public enum Status {
        OK("정상"),                       // 30일 넘게 남음
        EXPIRING("API 키 만료 임박"),      // 1~30일 남음
        EXPIRED("API 키 재등록 필요"),     // 남은 일수 0 이하
        NONE("만료일 없음");               // 만료일 정보 없음

        private final String label; // 한국어 표시 이름 (메타 번들)
    }

    public static ApiKeyExpiry of(Instant expiresAt, LocalDate todayKst) {
        if (expiresAt == null) return new ApiKeyExpiry(Status.NONE, null, null);
        LocalDate expiresOn = expiresAt.atZone(TimeZones.KST).toLocalDate();
        int daysLeft = (int) ChronoUnit.DAYS.between(todayKst, expiresOn);
        Status status = daysLeft <= 0 ? Status.EXPIRED : daysLeft <= EXPIRING_DAYS ? Status.EXPIRING : Status.OK;
        return new ApiKeyExpiry(status, expiresOn, daysLeft);
    }
}
