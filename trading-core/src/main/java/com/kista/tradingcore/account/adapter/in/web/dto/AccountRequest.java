package com.kista.tradingcore.account.adapter.in.web.dto;

import com.kista.tradingcore.account.domain.model.RegisterAccountCommand;
import com.kista.tradingcore.account.domain.model.UpdateAccountCommand;
import com.kista.shared.kernel.Broker;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AccountRequest(
        @Schema(description = "계좌 별명", example = "내 메인 계좌")
        @NotBlank String nickname,
        @Schema(description = "계좌번호 — KIS: XXXXXXXX-XX (예: 74420614-01), Toss: XXX-XX-XXXXXX (예: 131-01-001931), MOCK: 생략 가능(서버 자동 생성), UPBIT·MERITZ: 받지 않음(오면 무시)", example = "74420614-01")
        @Pattern(regexp = "\\d{8}-\\d{2}|\\d{3}-\\d{2}-\\d{6}", message = "계좌번호는 KIS XXXXXXXX-XX 또는 Toss XXX-XX-XXXXXX 형식이어야 합니다")
        String accountNo,
        @Schema(description = "API 앱 키 (KIS App Key / Toss Client ID / 업비트 Access Key)", example = "PSxxxxxxxxxx")
        String appKey,
        @Schema(description = "API 앱 시크릿 (KIS App Secret / Toss Client Secret / 업비트 Secret Key)")
        String secretKey,
        @Schema(description = "증권사 — null이면 KIS 기본값 적용", example = "KIS", allowableValues = {"KIS", "TOSS", "MOCK", "UPBIT", "MERITZ"})
        Broker broker
) {
    public RegisterAccountCommand toRegisterCommand() {
        // accountNo를 그대로 저장 — KIS: "74420614-01", TOSS: "131-01-001931"
        // KIS CANO/ACNT_PRDT_CD 파싱은 KIS 어댑터가 담당
        // brokerAccountCode(TOSS accountSeq)는 AccountService에서 API 호출로 채움
        return new RegisterAccountCommand(nickname, accountNo, appKey, secretKey, broker);
    }

    public UpdateAccountCommand toUpdateCommand() {
        return new UpdateAccountCommand(nickname);
    }
}
