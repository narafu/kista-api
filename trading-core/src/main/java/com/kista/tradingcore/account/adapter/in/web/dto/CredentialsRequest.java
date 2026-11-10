package com.kista.tradingcore.account.adapter.in.web.dto;

import com.kista.tradingcore.account.domain.model.UpdateCredentialsCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

// 계좌 API 키 교체 요청 — 모든 증권사 공통(MOCK 제외)
public record CredentialsRequest(
        @Schema(description = "새 API 앱 키 (KIS App Key / Toss Client ID / 업비트 Access Key)")
        @NotBlank String appKey,
        @Schema(description = "새 API 앱 시크릿 (KIS App Secret / Toss Client Secret / 업비트 Secret Key)")
        @NotBlank String secretKey,
        @Schema(description = "API 키 만료일(KST, 선택) — KIS·토스·메리츠만, 비우면 등록·교체 시점 + 1년 추정", example = "2027-11-10")
        LocalDate apiKeyExpiresOn
) {
    public UpdateCredentialsCommand toCommand() {
        return new UpdateCredentialsCommand(appKey.strip(), secretKey.strip(), apiKeyExpiresOn);
    }
}
