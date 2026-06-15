package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.RegisterAccountCommand;
import com.kista.domain.model.account.UpdateAccountCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AccountRequest(
        @Schema(description = "계좌 별명", example = "내 메인 계좌")
        @NotBlank String nickname,
        @Schema(description = "계좌번호 — KIS: XXXXXXXX-XX (예: 74420614-01), Toss: XXX-XX-XXXXXX (예: 131-01-001931)", example = "74420614-01")
        @NotBlank @Pattern(regexp = "\\d{8}-\\d{2}|\\d{3}-\\d{2}-\\d{6}", message = "계좌번호는 KIS XXXXXXXX-XX 또는 Toss XXX-XX-XXXXXX 형식이어야 합니다")
        String accountNo,
        @Schema(description = "API 앱 키 (KIS App Key / Toss Client ID)", example = "PSxxxxxxxxxx")
        String appKey,
        @Schema(description = "API 앱 시크릿 (KIS App Secret / Toss Client Secret)")
        String secretKey,
        @Schema(description = "증권사 — null이면 KIS 기본값 적용", example = "KIS", allowableValues = {"KIS", "TOSS"})
        Account.Broker broker
) {
    public RegisterAccountCommand toRegisterCommand() {
        // accountNo를 그대로 저장 — KIS: "74420614-01", TOSS: "131-01-001931"
        // KIS CANO/ACNT_PRDT_CD 파싱은 KIS 어댑터가 담당
        // brokerAccountCode(TOSS accountSeq)는 AccountService에서 API 호출로 채움
        return new RegisterAccountCommand(nickname, accountNo, appKey, secretKey, null, broker);
    }

    public UpdateAccountCommand toUpdateCommand() {
        return new UpdateAccountCommand(nickname);
    }
}
