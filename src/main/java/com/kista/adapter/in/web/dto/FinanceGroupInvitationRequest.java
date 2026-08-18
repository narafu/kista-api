package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

public record FinanceGroupInvitationRequest(
        @Schema(description = "초대 유효 시간 (시간 단위)", example = "72")
        @Positive long expiresInHours
) {}
