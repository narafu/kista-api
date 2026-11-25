package com.kista.tradingcore.account.adapter.in.web.dto;

import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.account.domain.model.ApiKeyExpiry;
import com.kista.shared.kernel.AccountNumberMasker;
import com.kista.shared.kernel.Broker;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record AccountResponse(
        @Schema(description = "계좌 고유 ID")
        UUID id,
        @Schema(description = "계좌 별명", example = "내 메인 계좌")
        String nickname,
        @Schema(description = "마스킹된 계좌번호", example = "****0614")
        @Nullable String accountNoMasked,
        @Schema(description = "전체 계좌번호 (본인 계좌, 토글 표시용)", example = "74420614")
        @Nullable String accountNo,
        @Schema(description = "증권사", example = "TOSS")
        String broker,
        @Schema(description = "계좌 기준 통화(증권사 파생)", example = "USD")
        String currency,
        @Schema(description = "증권사 API 키 만료 시각 (토스·메리츠는 추정(+1년)·사용자 입력, 그 외 null)")
        @Nullable Instant apiKeyExpiresAt,
        @Schema(description = "API 키 만료 판정 (KST 날짜 기준 — 남은 일수 0 이하 EXPIRED, 30일 이내 EXPIRING, 만료일 없음 NONE)")
        ApiKeyExpiryResponse apiKeyExpiry
) {
    // API 키 만료 판정 응답 — 서버 판정값(ApiKeyExpiry)을 그대로 싣는다
    public record ApiKeyExpiryResponse(
            @Schema(description = "만료 상태", example = "EXPIRING")
            ApiKeyExpiry.Status status,
            @Schema(description = "만료일 (KST, 만료일 없으면 null)", example = "2026-12-01")
            @Nullable LocalDate expiresOn,
            @Schema(description = "오늘(KST)부터 만료일까지 남은 일수 (0=오늘 만료, 음수=지남, 만료일 없으면 null)", example = "6")
            @Nullable Integer daysLeft
    ) {
        static ApiKeyExpiryResponse from(ApiKeyExpiry e) {
            return new ApiKeyExpiryResponse(e.status(), e.expiresOn(), e.daysLeft());
        }
    }

    // todayKst — 만료 판정 기준일(컨트롤러가 Clock으로 구한 KST 오늘)
    public static AccountResponse from(Account a, LocalDate todayKst) {
        // accountNo가 이미 전체 표시 형식 — TOSS: "131-01-001931"
        Broker broker = a.broker();
        return new AccountResponse(
                a.id(),
                a.nickname(),
                a.accountNo() != null ? AccountNumberMasker.mask(a.accountNo()) : null, // mask(null)은 "****"라 직접 분기
                a.accountNo(),
                broker != null ? broker.name() : null,
                broker != null ? broker.getCurrency().name() : null,
                a.apiKeyExpiresAt(),
                ApiKeyExpiryResponse.from(ApiKeyExpiry.of(a.apiKeyExpiresAt(), todayKst))
        );
    }
}
