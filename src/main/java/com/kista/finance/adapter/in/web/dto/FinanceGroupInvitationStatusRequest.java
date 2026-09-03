package com.kista.finance.adapter.in.web.dto;

import com.kista.finance.domain.model.FinanceGroupInvitation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record FinanceGroupInvitationStatusRequest(
        @Schema(description = "ACCEPTED 또는 DECLINED", example = "ACCEPTED")
        @NotNull FinanceGroupInvitation.Status status
) {}
