package com.kista.tradingcore.account.adapter.in.web.dto;

import com.kista.tradingcore.account.domain.model.RegisterAccountCommand;
import com.kista.tradingcore.account.domain.model.UpdateAccountCommand;
import com.kista.shared.kernel.Broker;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

public record AccountRequest(
        @Schema(description = "계좌 별명", example = "내 메인 계좌")
        @NotBlank(message = "계좌 별명은 필수입니다.") String nickname,
        @Schema(description = "계좌번호 — Toss: XXX-XX-XXXXXX (예: 131-01-001931), MOCK: 생략 가능(서버 자동 생성), MERITZ: 받지 않음(오면 무시)", example = "131-01-001931")
        @Pattern(regexp = "\\d{8}-\\d{2}|\\d{3}-\\d{2}-\\d{6}", message = "계좌번호는 XXXXXXXX-XX 또는 XXX-XX-XXXXXX 형식이어야 합니다.")
        @Nullable String accountNo,
        @Schema(description = "API 앱 키 (Toss Client ID / 메리츠 App Key)", example = "PSxxxxxxxxxx")
        @Nullable String appKey,
        @Schema(description = "API 앱 시크릿 (Toss Client Secret / 메리츠 App Secret)")
        @Nullable String secretKey,
        @Schema(description = "증권사 — null이면 MOCK 기본값 적용", example = "TOSS", allowableValues = {"TOSS", "MOCK", "MERITZ"})
        @Nullable Broker broker,
        @Schema(description = "API 키 만료일(KST, 선택) — 토스·메리츠만, 비우면 등록·교체 시점 + 1년 추정", example = "2027-11-10")
        @Nullable LocalDate apiKeyExpiresOn
) {
    public RegisterAccountCommand toRegisterCommand() {
        // accountNo를 그대로 저장 — TOSS: "131-01-001931"
        // brokerAccountCode(TOSS accountSeq)는 AccountService에서 API 호출로 채움
        return new RegisterAccountCommand(nickname, accountNo, appKey, secretKey, broker, apiKeyExpiresOn);
    }

    public UpdateAccountCommand toUpdateCommand() {
        return new UpdateAccountCommand(nickname, apiKeyExpiresOn);
    }
}
