package com.kista.adapter.in.web.dto;

import com.kista.domain.model.finance.FinanceGroupInvitation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record FinanceGroupInvitationStatusRequest(
        @Schema(description = "ACCEPTED 또는 DECLINED", example = "ACCEPTED")
        @NotNull FinanceGroupInvitation.Status status
) {}
