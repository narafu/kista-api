package com.kista.tradingcore.account.adapter.in.web.dto;

import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.AccountNumberMasker;
import com.kista.shared.kernel.Broker;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        @Schema(description = "계좌 고유 ID")
        UUID id,
        @Schema(description = "계좌 별명", example = "내 메인 계좌")
        String nickname,
        @Schema(description = "마스킹된 계좌번호 (업비트는 null)", example = "****0614")
        String accountNoMasked,
        @Schema(description = "전체 계좌번호 (본인 계좌, 토글 표시용 — 업비트는 null)", example = "74420614")
        String accountNo,
        @Schema(description = "증권사", example = "KIS")
        String broker,
        @Schema(description = "계좌 기준 통화(증권사 파생)", example = "USD")
        String currency,
        @Schema(description = "거래소 API 키 만료 시각 (업비트만, 그 외 null)")
        Instant apiKeyExpiresAt
) {
    public static AccountResponse from(Account a) {
        // accountNo가 이미 전체 표시 형식 — KIS: "74420614-01", TOSS: "131-01-001931", 업비트: null
        Broker broker = a.broker();
        return new AccountResponse(
                a.id(),
                a.nickname(),
                a.accountNo() != null ? AccountNumberMasker.mask(a.accountNo()) : null, // mask(null)은 "****"라 직접 분기
                a.accountNo(),
                broker != null ? broker.name() : null,
                broker != null ? broker.getCurrency().name() : null,
                a.apiKeyExpiresAt()
        );
    }
}
