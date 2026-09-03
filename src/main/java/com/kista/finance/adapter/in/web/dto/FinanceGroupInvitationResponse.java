package com.kista.finance.adapter.in.web.dto;

import com.kista.finance.domain.model.FinanceGroupInvitation;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record FinanceGroupInvitationResponse(
        @Schema(description = "공유용 초대 코드")
        String code,
        @Schema(description = "초대 만료 시각")
        Instant expiresAt
) {
    public static FinanceGroupInvitationResponse from(FinanceGroupInvitation invitation) {
        return new FinanceGroupInvitationResponse(invitation.code(), invitation.expiresAt());
    }
}
